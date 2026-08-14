package com.ideas.contracts.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import com.ideas.contracts.service.model.CheckRunCreateRequest;
import com.ideas.contracts.service.model.CheckRunCreateResponse;
import com.ideas.contracts.service.model.CheckRunLogResponse;
import com.ideas.contracts.service.model.CheckRunPageResponse;
import com.ideas.contracts.service.model.CheckRunResponse;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CheckRunStore implements MetadataStore {
  private static final String SQLITE_JDBC_PREFIX = "jdbc:sqlite:";
  private static final Set<String> ALLOWED_STRICT_SSL_MODES = Set.of("verify-ca", "verify-full");
  private static final Set<String> ALLOWED_SQLITE_SYNCHRONOUS =
      Set.of("OFF", "NORMAL", "FULL", "EXTRA");
  private static final Set<String> ALLOWED_COMPATIBILITY_MODES =
      Set.of("BACKWARD", "FORWARD", "FULL");
  private static final String STATUS_QUEUED = "QUEUED";
  private static final String STATUS_RUNNING = "RUNNING";
  private static final String LATEST_DEFAULT_MIGRATION_RESOURCE =
      "db/migration/V7__create_notification_deliveries.sql";
  private static final String LATEST_MYSQL_MIGRATION_RESOURCE =
      "db/migration-mysql/V7__create_notification_deliveries.sql";
  private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
  private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {};
  private static final Logger LOGGER = LoggerFactory.getLogger(CheckRunStore.class);

  private final String jdbcUrl;
  private final Path sqlitePath;
  private final String dbTarget;
  private final DatabaseBackend databaseBackend;
  private final HikariDataSource dataSource;
  private final Function<String, String> envLookup;
  private final int queryTimeoutSeconds;
  private final boolean failFastStartup;
  private final ObjectMapper objectMapper;
  private final CheckStoreProperties.Sqlite sqliteSettings;
  private final Object initLock = new Object();
  private volatile boolean initialized;

  @Autowired
  public CheckRunStore(CheckStoreProperties properties) {
    this(properties, System::getenv);
  }

  CheckRunStore(CheckStoreProperties properties, Function<String, String> envLookup) {
    this.envLookup = envLookup == null ? System::getenv : envLookup;
    String trimmedUrl = trimToEmpty(properties.getUrl());
    CheckStoreProperties.Ssl ssl = properties.getSsl();
    validatePostgresSecurityConstraints(trimmedUrl, ssl, properties.isEnforceSecurePostgres());
    if (trimmedUrl.isBlank()) {
      Path resolvedPath = Paths.get(defaultIfBlank(properties.getPath(), "checks.db"));
      this.jdbcUrl = SQLITE_JDBC_PREFIX + resolvedPath;
      this.sqlitePath = resolvedPath;
      this.dbTarget = resolvedPath.toAbsolutePath().toString();
    } else {
      this.jdbcUrl = withPostgresSslOptions(trimmedUrl, ssl);
      this.sqlitePath = resolveSqlitePath(jdbcUrl);
      this.dbTarget = sanitizeJdbcUrl(jdbcUrl);
    }
    this.databaseBackend = backendFromJdbcUrl(this.jdbcUrl);
    validateExpectedSchema(this.jdbcUrl, properties.getExpectedSchema());
    validatePoolAndTimeoutSettings(properties);
    this.sqliteSettings = properties.getSqlite();
    warmUpSqliteDriverIfPossible();
    String dbUsername = resolveUsername(properties);
    String dbPassword = resolvePassword(properties);
    this.dataSource = createDataSource(jdbcUrl, dbUsername, dbPassword, properties.getPool());
    this.queryTimeoutSeconds = toQueryTimeoutSeconds(properties.getQueryTimeout());
    this.failFastStartup = properties.isFailFastStartup();
    this.objectMapper = new ObjectMapper();
  }

  @PostConstruct
  public void initialize() {
    if (!tryInitialize(true)) {
      if (failFastStartup) {
        throw new IllegalStateException(
            "Failed to initialize check history store for configured database target: " + dbTarget);
      }
      LOGGER.warn(
          "event=check_store_init_deferred component=check_run_store db_target={} message=Will retry on first checks request",
          dbTarget);
    }
  }

  public List<CheckRunResponse> list(String contractId, String commitSha) {
    ensureInitialized();

    StringBuilder sql = new StringBuilder(CheckRunSqlQueries.LIST_CHECK_RUNS_BASE);

    List<Object> params = new ArrayList<>();
    if (contractId != null && !contractId.isBlank()) {
      sql.append(" AND contract_id = ?");
      params.add(contractId);
    }
    if (commitSha != null && !commitSha.isBlank()) {
      sql.append(" AND commit_sha = ?");
      params.add(commitSha);
    }
    sql.append(" ORDER BY created_at DESC, run_id DESC");

    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(sql.toString())) {
      applyQueryTimeout(statement);
      for (int i = 0; i < params.size(); i++) {
        statement.setObject(i + 1, params.get(i));
      }
      try (ResultSet rs = statement.executeQuery()) {
        List<CheckRunResponse> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(mapRow(rs));
        }
        return rows;
      }
    } catch (SQLException e) {
      logDbFailure("list_check_runs", e, contractId, commitSha);
      throw new CheckRunStoreException("Failed to query check runs from configured database.", e);
    }
  }

  public CheckRunPageResponse listPage(CheckRunQuery query) {
    ensureInitialized();
    if (query == null) {
      throw new IllegalArgumentException("query must not be null.");
    }

    StringBuilder sql = new StringBuilder(CheckRunSqlQueries.LIST_CHECK_RUNS_BASE);
    List<Object> params = new ArrayList<>();

    if (query.contractId() != null) {
      sql.append(" AND contract_id = ?");
      params.add(query.contractId());
    }
    if (query.commitSha() != null) {
      sql.append(" AND commit_sha = ?");
      params.add(query.commitSha());
    }
    if (query.status() != null) {
      sql.append(" AND UPPER(status) = ?");
      params.add(query.status());
    }
    sql.append(" ORDER BY created_at DESC, run_id DESC");
    sql.append(" LIMIT ? OFFSET ?");
    params.add(query.limit() + 1);
    params.add(query.offset());

    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(sql.toString())) {
      applyQueryTimeout(statement);
      bindParams(statement, params);

      try (ResultSet rs = statement.executeQuery()) {
        List<CheckRunResponse> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(mapRow(rs));
        }
        boolean hasMore = rows.size() > query.limit();
        if (hasMore) {
          rows.remove(rows.size() - 1);
        }
        return new CheckRunPageResponse(rows, query.limit(), query.offset(), hasMore);
      }
    } catch (SQLException e) {
      logDbFailure("list_check_runs_page", e, query.contractId(), query.commitSha());
      throw new CheckRunStoreException("Failed to query check run page from configured database.", e);
    }
  }

  public Optional<CheckRunResponse> findByRunId(String runId) {
    ensureInitialized();
    String normalizedRunId = trimToEmpty(runId);
    if (normalizedRunId.isBlank()) {
      throw new IllegalArgumentException("runId must not be blank.");
    }

    String sql = CheckRunSqlQueries.FIND_CHECK_RUN_BY_ID;
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      applyQueryTimeout(statement);
      statement.setString(1, normalizedRunId);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(mapRow(rs));
      }
    } catch (SQLException e) {
      logDbFailure("find_check_run_by_id", e, null, null);
      throw new CheckRunStoreException("Failed to query check run from configured database.", e);
    }
  }

  public List<CheckRunLogResponse> listLogs(String runId) {
    ensureInitialized();
    String normalizedRunId = trimToEmpty(runId);
    if (normalizedRunId.isBlank()) {
      throw new IllegalArgumentException("runId must not be blank.");
    }

    String sql = CheckRunSqlQueries.LIST_CHECK_RUN_LOGS;
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      applyQueryTimeout(statement);
      statement.setString(1, normalizedRunId);
      try (ResultSet rs = statement.executeQuery()) {
        List<CheckRunLogResponse> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(new CheckRunLogResponse(
              rs.getString("log_id"),
              rs.getString("run_id"),
              rs.getString("level"),
              rs.getString("message"),
              rs.getString("created_at")));
        }
        return rows;
      }
    } catch (SQLException e) {
      logDbFailure("list_check_run_logs", e, null, null);
      throw new CheckRunStoreException("Failed to query check run logs from configured database.", e);
    }
  }

  public Optional<QueuedCheckRun> claimNextQueuedRun() {
    ensureInitialized();

    String selectSql = CheckRunSqlQueries.SELECT_NEXT_QUEUED_RUN;
    String updateSql = CheckRunSqlQueries.UPDATE_RUN_TO_RUNNING;

    for (int attempt = 0; attempt < 3; attempt++) {
      try (Connection connection = openConnection()) {
        connection.setAutoCommit(false);
        QueuedCheckRun queuedRun = null;
        try (PreparedStatement select = connection.prepareStatement(selectSql)) {
          applyQueryTimeout(select);
          select.setString(1, STATUS_QUEUED);
          try (ResultSet rs = select.executeQuery()) {
            if (rs.next()) {
              queuedRun = new QueuedCheckRun(
                  rs.getString("run_id"),
                  rs.getString("contract_id"),
                  rs.getString("base_version"),
                  rs.getString("candidate_version"),
                  rs.getString("compatibility_mode"),
                  rs.getString("commit_sha"),
                  rs.getString("triggered_by"));
            }
          }
        }

        if (queuedRun == null) {
          connection.commit();
          return Optional.empty();
        }

        try (PreparedStatement update = connection.prepareStatement(updateSql)) {
          applyQueryTimeout(update);
          update.setString(1, STATUS_RUNNING);
          update.setString(2, Instant.now().toString());
          update.setString(3, queuedRun.runId());
          update.setString(4, STATUS_QUEUED);
          int updated = update.executeUpdate();
          if (updated == 0) {
            connection.rollback();
            continue;
          }
        }
        connection.commit();
        return Optional.of(queuedRun);
      } catch (SQLException e) {
        logDbFailure("claim_check_run", e, null, null);
        throw new CheckRunStoreException("Failed to claim queued check run.", e);
      }
    }

    return Optional.empty();
  }

  public boolean completeRun(String runId, String status, List<String> breakingChanges, List<String> warnings) {
    return updateRunResult(runId, status, breakingChanges, warnings, Instant.now().toString());
  }

  public boolean requeueRun(String runId) {
    ensureInitialized();
    String normalizedRunId = trimToEmpty(runId);
    if (normalizedRunId.isBlank()) {
      throw new IllegalArgumentException("runId must not be blank.");
    }
    String sql = CheckRunSqlQueries.REQUEUE_RUN;
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      applyQueryTimeout(statement);
      statement.setString(1, STATUS_QUEUED);
      statement.setString(2, normalizedRunId);
      statement.setString(3, STATUS_RUNNING);
      return statement.executeUpdate() > 0;
    } catch (SQLException e) {
      logDbFailure("requeue_check_run", e, null, null);
      throw new CheckRunStoreException("Failed to requeue check run.", e);
    }
  }

  public void appendLog(String runId, String level, String message) {
    ensureInitialized();
    String normalizedRunId = trimToEmpty(runId);
    if (normalizedRunId.isBlank()) {
      throw new IllegalArgumentException("runId must not be blank.");
    }
    String normalizedLevel = trimToEmpty(level);
    String normalizedMessage = trimToEmpty(message);
    if (normalizedLevel.isBlank() || normalizedMessage.isBlank()) {
      throw new IllegalArgumentException("log level and message must not be blank.");
    }

    String sql = CheckRunSqlQueries.INSERT_CHECK_RUN_LOG;
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      applyQueryTimeout(statement);
      statement.setString(1, UUID.randomUUID().toString());
      statement.setString(2, normalizedRunId);
      statement.setString(3, normalizedLevel);
      statement.setString(4, normalizedMessage);
      statement.setString(5, Instant.now().toString());
      statement.executeUpdate();
    } catch (SQLException e) {
      logDbFailure("append_check_run_log", e, null, null);
      throw new CheckRunStoreException("Failed to append check run log.", e);
    }
  }

  private boolean updateRunResult(
      String runId,
      String status,
      List<String> breakingChanges,
      List<String> warnings,
      String finishedAt) {
    ensureInitialized();
    String normalizedRunId = trimToEmpty(runId);
    if (normalizedRunId.isBlank()) {
      throw new IllegalArgumentException("runId must not be blank.");
    }
    String normalizedStatus = trimToEmpty(status).toUpperCase(Locale.ROOT);
    if (normalizedStatus.isBlank()) {
      throw new IllegalArgumentException("status must not be blank.");
    }

    String sql = CheckRunSqlQueries.UPDATE_RUN_RESULT;
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      applyQueryTimeout(statement);
      statement.setString(1, normalizedStatus);
      statement.setString(2, toJsonArray(breakingChanges));
      statement.setString(3, toJsonArray(warnings));
      statement.setString(4, finishedAt);
      statement.setString(5, normalizedRunId);
      statement.setString(6, STATUS_RUNNING);
      return statement.executeUpdate() > 0;
    } catch (SQLException e) {
      logDbFailure("complete_check_run", e, null, null);
      throw new CheckRunStoreException("Failed to update check run result.", e);
    }
  }

  public CheckRunCreateResponse createQueuedRun(CheckRunCreateRequest request) {
    ensureInitialized();
    if (request == null) {
      throw new IllegalArgumentException("request must not be null.");
    }

    String runId = UUID.randomUUID().toString();
    String status = STATUS_QUEUED;
    String createdAt = Instant.now().toString();
    String inputHash = computeInputHash(request);

    String sql = CheckRunSqlQueries.INSERT_CHECK_RUN;

    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      applyQueryTimeout(statement);
      int index = 1;
      statement.setString(index++, runId);
      statement.setString(index++, request.contractId());
      statement.setString(index++, request.baseVersion());
      statement.setString(index++, request.candidateVersion());
      statement.setString(index++, status);
      statement.setString(index++, "[]");
      statement.setString(index++, "[]");
      statement.setString(index++, request.commitSha());
      statement.setString(index++, createdAt);
      statement.setString(index++, request.triggeredBy());
      statement.setString(index++, request.mode());
      statement.setString(index++, inputHash);
      statement.setString(index++, null);
      statement.setString(index, null);
      statement.executeUpdate();
    } catch (SQLException e) {
      logDbFailure("create_check_run", e, request.contractId(), request.commitSha());
      throw new CheckRunStoreException("Failed to create check run in configured database.", e);
    }

    return new CheckRunCreateResponse(runId, status);
  }

  public void recordAuditLog(AuditLogEntry entry) {
    if (entry == null) {
      return;
    }
    try {
      ensureInitialized();
    } catch (RuntimeException ex) {
      logDbFailure("record_audit_log_init", ex, null, null);
      return;
    }
    String sql = CheckRunSqlQueries.INSERT_AUDIT_LOG;

    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      applyQueryTimeout(statement);
      int index = 1;
      statement.setString(index++, UUID.randomUUID().toString());
      statement.setString(index++, safeValue(entry.action()));
      statement.setString(index++, safeValue(entry.actor()));
      statement.setString(index++, safeValue(entry.actorRoles()));
      statement.setString(index++, safeValue(entry.source()));
      statement.setString(index++, nullIfBlank(entry.requestId()));
      statement.setString(index++, safeValue(entry.httpMethod()));
      statement.setString(index++, safeValue(entry.path()));
      statement.setString(index++, safeValue(entry.resourceType()));
      statement.setString(index++, nullIfBlank(entry.resourceId()));
      statement.setString(index++, safeValue(entry.status()));
      statement.setString(index++, serializeAuditDetail(entry.detail()));
      statement.setString(index, Instant.now().toString());
      statement.executeUpdate();
    } catch (Exception e) {
      logDbFailure("record_audit_log", e, null, null);
    }
  }

  @Override
  public NotificationEnqueueResult enqueueNotificationDelivery(
      NotificationEvent event, String sinkName) {
    ensureInitialized();
    if (event == null) {
      throw new IllegalArgumentException("event must not be null.");
    }
    if (event.eventType() == null || event.severity() == null) {
      throw new IllegalArgumentException("notification event type and severity must be set.");
    }
    String normalizedSinkName = trimToEmpty(sinkName).toLowerCase(Locale.ROOT);
    if (normalizedSinkName.isBlank()) {
      throw new IllegalArgumentException("sinkName must not be blank.");
    }

    Instant now = Instant.now();
    NotificationDelivery delivery = new NotificationDelivery(
        UUID.randomUUID().toString(),
        event,
        normalizedSinkName,
        NotificationDeliveryStatus.PENDING,
        0,
        now,
        null,
        null,
        now,
        null);
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(
             CheckRunSqlQueries.INSERT_NOTIFICATION_DELIVERY)) {
      applyQueryTimeout(statement);
      bindNotificationDelivery(statement, delivery);
      statement.executeUpdate();
      return new NotificationEnqueueResult(delivery, true);
    } catch (SQLException error) {
      Optional<NotificationDelivery> existing = findNotificationDeliveryByDedupe(
          event.dedupeKey(), normalizedSinkName);
      if (existing.isPresent()) {
        return new NotificationEnqueueResult(existing.get(), false);
      }
      logDbFailure("enqueue_notification_delivery", error, event.contractId(), event.commitSha());
      throw new CheckRunStoreException("Failed to enqueue notification delivery.", error);
    }
  }

  @Override
  public Optional<NotificationDelivery> claimNextNotificationDelivery(
      Instant now, Instant staleClaimBefore) {
    ensureInitialized();
    Instant claimedAt = now == null ? Instant.now() : now;
    Instant staleBefore = staleClaimBefore == null ? claimedAt : staleClaimBefore;

    for (int attempt = 0; attempt < 3; attempt++) {
      try (Connection connection = openConnection()) {
        connection.setAutoCommit(false);
        NotificationDelivery candidate = null;
        try (PreparedStatement select = connection.prepareStatement(
            CheckRunSqlQueries.SELECT_NEXT_NOTIFICATION_DELIVERY)) {
          applyQueryTimeout(select);
          select.setString(1, NotificationDeliveryStatus.PENDING.name());
          select.setString(2, NotificationDeliveryStatus.FAILED_RETRYABLE.name());
          select.setString(3, claimedAt.toString());
          select.setString(4, NotificationDeliveryStatus.IN_FLIGHT.name());
          select.setString(5, staleBefore.toString());
          try (ResultSet resultSet = select.executeQuery()) {
            if (resultSet.next()) {
              candidate = mapNotificationDelivery(resultSet);
            }
          }
        }

        if (candidate == null) {
          connection.commit();
          return Optional.empty();
        }

        try (PreparedStatement update = connection.prepareStatement(
            CheckRunSqlQueries.CLAIM_NOTIFICATION_DELIVERY)) {
          applyQueryTimeout(update);
          update.setString(1, NotificationDeliveryStatus.IN_FLIGHT.name());
          update.setString(2, claimedAt.toString());
          update.setString(3, candidate.deliveryId());
          update.setString(4, candidate.status().name());
          if (update.executeUpdate() == 0) {
            connection.rollback();
            continue;
          }
        }
        connection.commit();
        return Optional.of(new NotificationDelivery(
            candidate.deliveryId(),
            candidate.event(),
            candidate.sinkName(),
            NotificationDeliveryStatus.IN_FLIGHT,
            candidate.attemptCount() + 1,
            candidate.createdAt(),
            claimedAt,
            candidate.deliveredAt(),
            candidate.nextAttemptAt(),
            candidate.failureMessage()));
      } catch (SQLException error) {
        logDbFailure("claim_notification_delivery", error, null, null);
        throw new CheckRunStoreException("Failed to claim notification delivery.", error);
      }
    }

    return Optional.empty();
  }

  @Override
  public boolean markNotificationDeliveryDelivered(String deliveryId, Instant deliveredAt) {
    ensureInitialized();
    String normalizedDeliveryId = trimToEmpty(deliveryId);
    if (normalizedDeliveryId.isBlank()) {
      throw new IllegalArgumentException("deliveryId must not be blank.");
    }
    Instant completedAt = deliveredAt == null ? Instant.now() : deliveredAt;
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(
             CheckRunSqlQueries.MARK_NOTIFICATION_DELIVERY_DELIVERED)) {
      applyQueryTimeout(statement);
      statement.setString(1, NotificationDeliveryStatus.DELIVERED.name());
      statement.setString(2, completedAt.toString());
      statement.setString(3, normalizedDeliveryId);
      statement.setString(4, NotificationDeliveryStatus.IN_FLIGHT.name());
      return statement.executeUpdate() > 0;
    } catch (SQLException error) {
      logDbFailure("mark_notification_delivery_delivered", error, null, null);
      throw new CheckRunStoreException("Failed to mark notification delivery as delivered.", error);
    }
  }

  @Override
  public boolean markNotificationDeliveryFailed(
      String deliveryId,
      String failureMessage,
      Instant nextAttemptAt,
      boolean permanentlyFailed) {
    ensureInitialized();
    String normalizedDeliveryId = trimToEmpty(deliveryId);
    if (normalizedDeliveryId.isBlank()) {
      throw new IllegalArgumentException("deliveryId must not be blank.");
    }
    NotificationDeliveryStatus status = permanentlyFailed
        ? NotificationDeliveryStatus.FAILED_PERMANENT
        : NotificationDeliveryStatus.FAILED_RETRYABLE;
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(
             CheckRunSqlQueries.MARK_NOTIFICATION_DELIVERY_FAILED)) {
      applyQueryTimeout(statement);
      statement.setString(1, status.name());
      statement.setString(2, nullIfBlank(failureMessage));
      statement.setString(3, nextAttemptAt == null ? null : nextAttemptAt.toString());
      statement.setString(4, normalizedDeliveryId);
      statement.setString(5, NotificationDeliveryStatus.IN_FLIGHT.name());
      return statement.executeUpdate() > 0;
    } catch (SQLException error) {
      logDbFailure("mark_notification_delivery_failed", error, null, null);
      throw new CheckRunStoreException("Failed to mark notification delivery as failed.", error);
    }
  }

  @Override
  public List<NotificationDelivery> listNotificationDeliveries(int limit) {
    return listNotificationDeliveries(NotificationDeliveryQuery.from(null, null, null, null, limit));
  }

  @Override
  public List<NotificationDelivery> listNotificationDeliveries(NotificationDeliveryQuery query) {
    ensureInitialized();
    NotificationDeliveryQuery resolvedQuery = query == null
        ? NotificationDeliveryQuery.from(null, null, null, null, null)
        : query;
    StringBuilder sql = new StringBuilder(CheckRunSqlQueries.LIST_NOTIFICATION_DELIVERIES_BASE);
    List<Object> params = new ArrayList<>();
    if (resolvedQuery.status() != null) {
      sql.append(" AND UPPER(status) = ?");
      params.add(resolvedQuery.status());
    }
    if (resolvedQuery.contractId() != null) {
      sql.append(" AND contract_id = ?");
      params.add(resolvedQuery.contractId());
    }
    if (resolvedQuery.sinkName() != null) {
      sql.append(" AND LOWER(sink_name) = ?");
      params.add(resolvedQuery.sinkName());
    }
    if (resolvedQuery.eventType() != null) {
      sql.append(" AND UPPER(event_type) = ?");
      params.add(resolvedQuery.eventType());
    }
    if (resolvedQuery.runId() != null) {
      sql.append(" AND run_id = ?");
      params.add(resolvedQuery.runId());
    }
    sql.append(" ORDER BY created_at DESC, delivery_id DESC LIMIT ?");
    params.add(resolvedQuery.limit());

    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(sql.toString())) {
      applyQueryTimeout(statement);
      bindParams(statement, params);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<NotificationDelivery> deliveries = new ArrayList<>();
        while (resultSet.next()) {
          deliveries.add(mapNotificationDelivery(resultSet));
        }
        return deliveries;
      }
    } catch (SQLException error) {
      logDbFailure("list_notification_deliveries", error, null, null);
      throw new CheckRunStoreException("Failed to list notification deliveries.", error);
    }
  }

  @Override
  public Optional<NotificationDelivery> findNotificationDelivery(String deliveryId) {
    ensureInitialized();
    String normalizedDeliveryId = trimToEmpty(deliveryId);
    if (normalizedDeliveryId.isBlank()) {
      throw new IllegalArgumentException("deliveryId must not be blank.");
    }

    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(
             CheckRunSqlQueries.FIND_NOTIFICATION_DELIVERY_BY_ID)) {
      applyQueryTimeout(statement);
      statement.setString(1, normalizedDeliveryId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next()
            ? Optional.of(mapNotificationDelivery(resultSet))
            : Optional.empty();
      }
    } catch (SQLException error) {
      logDbFailure("find_notification_delivery", error, null, null);
      throw new CheckRunStoreException("Failed to find notification delivery.", error);
    }
  }

  @Override
  public boolean requeueNotificationDelivery(String deliveryId, Instant nextAttemptAt) {
    ensureInitialized();
    String normalizedDeliveryId = trimToEmpty(deliveryId);
    if (normalizedDeliveryId.isBlank()) {
      throw new IllegalArgumentException("deliveryId must not be blank.");
    }
    Instant scheduledAt = nextAttemptAt == null ? Instant.now() : nextAttemptAt;
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(
             CheckRunSqlQueries.REQUEUE_NOTIFICATION_DELIVERY)) {
      applyQueryTimeout(statement);
      statement.setString(1, NotificationDeliveryStatus.PENDING.name());
      statement.setString(2, scheduledAt.toString());
      statement.setString(3, normalizedDeliveryId);
      statement.setString(4, NotificationDeliveryStatus.FAILED_RETRYABLE.name());
      statement.setString(5, NotificationDeliveryStatus.FAILED_PERMANENT.name());
      return statement.executeUpdate() > 0;
    } catch (SQLException error) {
      logDbFailure("requeue_notification_delivery", error, null, null);
      throw new CheckRunStoreException("Failed to requeue notification delivery.", error);
    }
  }

  public int backfillLegacyRuns(
      Function<String, String> modeResolver,
      String defaultTriggeredBy,
      String defaultMode) {
    ensureInitialized();
    Function<String, String> resolver = modeResolver == null ? id -> null : modeResolver;
    String fallbackTriggeredBy = defaultIfBlank(defaultTriggeredBy, "legacy");
    String fallbackMode = normalizeCompatibilityMode(defaultMode, "BACKWARD");

    String selectSql = CheckRunSqlQueries.SELECT_LEGACY_RUNS_FOR_BACKFILL;
    String updateSql = CheckRunSqlQueries.UPDATE_LEGACY_RUN_BACKFILL;
    String logExistsSql = CheckRunSqlQueries.CHECK_LOG_EXISTS;
    String insertLogSql = CheckRunSqlQueries.INSERT_CHECK_RUN_LOG;

    int updated = 0;
    try (Connection connection = openConnection();
         PreparedStatement select = connection.prepareStatement(selectSql);
         PreparedStatement update = connection.prepareStatement(updateSql);
         PreparedStatement logExists = connection.prepareStatement(logExistsSql);
         PreparedStatement insertLog = connection.prepareStatement(insertLogSql)) {
      applyQueryTimeout(select);
      applyQueryTimeout(update);
      applyQueryTimeout(logExists);
      applyQueryTimeout(insertLog);

      boolean logTableAvailable = true;
      try (ResultSet rs = select.executeQuery()) {
        while (rs.next()) {
          String runId = rs.getString("run_id");
          String contractId = rs.getString("contract_id");
          String baseVersion = rs.getString("base_version");
          String candidateVersion = rs.getString("candidate_version");
          String commitSha = rs.getString("commit_sha");
          String createdAt = trimToEmpty(rs.getString("created_at"));
          String status = trimToEmpty(rs.getString("status")).toUpperCase(Locale.ROOT);
          String triggeredBy = trimToEmpty(rs.getString("triggered_by"));
          String compatibilityMode = trimToEmpty(rs.getString("compatibility_mode"));
          String inputHash = trimToEmpty(rs.getString("input_hash"));
          String startedAt = trimToEmpty(rs.getString("started_at"));
          String finishedAt = trimToEmpty(rs.getString("finished_at"));

          String resolvedTriggeredBy = triggeredBy.isBlank() ? fallbackTriggeredBy : triggeredBy;
          String resolvedMode = normalizeCompatibilityMode(compatibilityMode, "");
          if (resolvedMode.isBlank()) {
            resolvedMode = normalizeCompatibilityMode(resolver.apply(contractId), fallbackMode);
          }
          if (resolvedMode.isBlank()) {
            resolvedMode = fallbackMode;
          }

          String resolvedInputHash = inputHash.isBlank()
              ? computeInputHash(contractId, baseVersion, candidateVersion, resolvedMode, commitSha, resolvedTriggeredBy)
              : inputHash;

          String effectiveCreatedAt = createdAt.isBlank() ? Instant.now().toString() : createdAt;
          String resolvedStartedAt = startedAt;
          if (resolvedStartedAt.isBlank() && !STATUS_QUEUED.equals(status)) {
            resolvedStartedAt = effectiveCreatedAt;
          }
          String resolvedFinishedAt = finishedAt;
          if (resolvedFinishedAt.isBlank()
              && !STATUS_QUEUED.equals(status)
              && !STATUS_RUNNING.equals(status)) {
            resolvedFinishedAt = effectiveCreatedAt;
          }

          update.setString(1, resolvedTriggeredBy);
          update.setString(2, resolvedMode);
          update.setString(3, resolvedInputHash);
          update.setString(4, resolvedStartedAt.isBlank() ? null : resolvedStartedAt);
          update.setString(5, resolvedFinishedAt.isBlank() ? null : resolvedFinishedAt);
          update.setString(6, runId);
          updated += update.executeUpdate();

          if (logTableAvailable && !STATUS_QUEUED.equals(status) && !STATUS_RUNNING.equals(status)) {
            try {
              boolean hasLog = false;
              logExists.setString(1, runId);
              try (ResultSet logRs = logExists.executeQuery()) {
                hasLog = logRs.next();
              }
              if (!hasLog) {
                String logTimestamp = resolvedFinishedAt.isBlank() ? effectiveCreatedAt : resolvedFinishedAt;
                insertLog.setString(1, UUID.randomUUID().toString());
                insertLog.setString(2, runId);
                insertLog.setString(3, "INFO");
                insertLog.setString(4, "Legacy check run backfilled without original execution logs.");
                insertLog.setString(5, logTimestamp);
                insertLog.executeUpdate();
              }
            } catch (SQLException logError) {
              logTableAvailable = false;
              logDbFailure("backfill_check_run_logs", logError, contractId, commitSha);
            }
          }
        }
      }
    } catch (SQLException e) {
      logDbFailure("backfill_check_runs", e, null, null);
      throw new CheckRunStoreException("Failed to backfill legacy check run fields.", e);
    }

    return updated;
  }

  private void ensureInitialized() {
    if (initialized || tryInitialize(true)) {
      return;
    }
    throw new CheckRunStoreException("Check history store is currently unavailable.");
  }

  public String configuredDbTarget() {
    return dbTarget;
  }

  public PoolSnapshot poolSnapshot() {
    HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
    HikariConfigMXBean config = dataSource.getHikariConfigMXBean();
    if (pool == null || config == null) {
      return new PoolSnapshot(-1, -1, -1, -1, -1, -1, -1);
    }
    return new PoolSnapshot(
        pool.getTotalConnections(),
        pool.getActiveConnections(),
        pool.getIdleConnections(),
        pool.getThreadsAwaitingConnection(),
        config.getMaximumPoolSize(),
        config.getMinimumIdle(),
        config.getConnectionTimeout());
  }

  @PreDestroy
  public void shutdown() {
    dataSource.close();
  }

  public HealthSnapshot healthSnapshot() {
    if (!(initialized || tryInitialize(false))) {
      return new HealthSnapshot(false, "initialization_failed");
    }
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(CheckRunSqlQueries.HEALTH_CHECK)) {
      applyQueryTimeout(statement);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next()
            ? new HealthSnapshot(true, "ok")
            : new HealthSnapshot(false, "health_query_failed");
      }
    } catch (SQLException e) {
      return new HealthSnapshot(false, safeValue(e.getMessage()));
    }
  }

  private boolean tryInitialize(boolean logFailure) {
    synchronized (initLock) {
      if (initialized) {
        return true;
      }
      try {
        if (sqlitePath != null) {
          Path parent = sqlitePath.toAbsolutePath().getParent();
          if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
          }
        }
        applySqliteRuntimePragmas();
        migrateSchema();
        verifySqliteIntegrityIfEnabled();
        initialized = true;
        LOGGER.info(
            "event=check_store_initialized component=check_run_store db_target={} backend={}",
            dbTarget,
            databaseBackend.label());
        return true;
      } catch (Exception e) {
        if (logFailure) {
          logDbFailure("initialize_check_store", e, null, null);
        }
        return false;
      }
    }
  }

  private void warmUpSqliteDriverIfPossible() {
    if (!isSqliteUrl(jdbcUrl)) {
      return;
    }
    try {
      if (sqlitePath != null) {
        Path parent = sqlitePath.toAbsolutePath().getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
      }
      try (Connection ignored = DriverManager.getConnection(jdbcUrl)) {
        // Warm sqlite-jdbc native initialization before Hikari's acquisition timeout applies.
      }
    } catch (Exception e) {
      LOGGER.debug(
          "event=sqlite_driver_warmup_skipped component=check_run_store db_target={} error_type={} error_message={}",
          dbTarget,
          e.getClass().getSimpleName(),
          safeValue(e.getMessage()));
    }
  }

  private Connection openConnection() throws SQLException {
    return dataSource.getConnection();
  }

  private void migrateSchema() {
    String[] locations = resolveMigrationLocations(databaseBackend);
    LOGGER.info(
        "event=check_store_migrations_selected component=check_run_store backend={} locations={}",
        databaseBackend.label(),
        String.join(",", locations));
    Flyway.configure()
        .dataSource(dataSource)
        .locations(locations)
        .baselineOnMigrate(true)
        .baselineVersion(MigrationVersion.fromVersion("0"))
        .load()
        .migrate();
  }

  private void applySqliteRuntimePragmas() throws SQLException {
    if (!isSqliteUrl(jdbcUrl)) {
      return;
    }
    try (Connection connection = openConnection();
         Statement statement = connection.createStatement()) {
      if (sqliteSettings.isWalEnabled()) {
        try (ResultSet resultSet = statement.executeQuery("PRAGMA journal_mode=WAL")) {
          if (!resultSet.next()) {
            throw new IllegalStateException("SQLite did not return a journal_mode result.");
          }
          String mode = trimToEmpty(resultSet.getString(1)).toLowerCase(Locale.ROOT);
          if (!"wal".equals(mode)) {
            throw new IllegalStateException("SQLite journal_mode is '" + mode + "' instead of 'wal'.");
          }
        }
      }
      statement.execute("PRAGMA foreign_keys=ON");
      statement.execute("PRAGMA synchronous=" + normalizeSqliteSynchronous(sqliteSettings.getSynchronous()));
      statement.execute("PRAGMA busy_timeout=" + sqliteBusyTimeoutMillis(sqliteSettings.getBusyTimeout()));
    }
  }

  private void verifySqliteIntegrityIfEnabled() throws SQLException {
    if (!isSqliteUrl(jdbcUrl) || !sqliteSettings.isIntegrityCheckOnStartup()) {
      return;
    }

    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement("PRAGMA quick_check");
         ResultSet resultSet = statement.executeQuery()) {
      if (!resultSet.next()) {
        throw new IllegalStateException("SQLite quick_check did not return a result.");
      }
      String result = trimToEmpty(resultSet.getString(1));
      if (!"ok".equalsIgnoreCase(result)) {
        throw new IllegalStateException("SQLite integrity check failed: " + result);
      }
    }
  }

  private String[] resolveMigrationLocations(DatabaseBackend backend) {
    MigrationLocation migrationLocation = migrationLocation(backend);
    if (classpathMigrationAvailable(migrationLocation.latestMigrationResource())) {
      return new String[] {migrationLocation.classpathLocation()};
    }

    Path fallback = resolveFilesystemMigrationPath(migrationLocation.filesystemDirectory());
    if (fallback != null) {
      LOGGER.warn(
          "event=check_store_migrations_fallback component=check_run_store path={} location={} message=Using filesystem migrations",
          fallback.toAbsolutePath(),
          migrationLocation.classpathLocation());
      return new String[] {"filesystem:" + fallback.toAbsolutePath()};
    }

    throw new IllegalStateException(
        "No Flyway migrations found for check history store. Ensure "
            + migrationLocation.classpathLocation()
            + " resources are packaged.");
  }

  private MigrationLocation migrationLocation(DatabaseBackend backend) {
    if (backend == DatabaseBackend.MYSQL) {
      return new MigrationLocation(
          "classpath:db/migration-mysql",
          LATEST_MYSQL_MIGRATION_RESOURCE,
          "migration-mysql");
    }
    return new MigrationLocation(
        "classpath:db/migration",
        LATEST_DEFAULT_MIGRATION_RESOURCE,
        "migration");
  }

  private boolean classpathMigrationAvailable(String latestMigrationResource) {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    if (loader == null) {
      loader = CheckRunStore.class.getClassLoader();
    }
    return loader != null && loader.getResource(latestMigrationResource) != null;
  }

  private Path resolveFilesystemMigrationPath(String migrationDirectory) {
    Path rootRelative = Paths.get(
        "contract-core",
        "src",
        "main",
        "resources",
        "db",
        migrationDirectory);
    if (Files.isDirectory(rootRelative)) {
      return rootRelative;
    }
    Path moduleRelative = Paths.get(
        "..",
        "contract-core",
        "src",
        "main",
        "resources",
        "db",
        migrationDirectory);
    if (Files.isDirectory(moduleRelative)) {
      return moduleRelative;
    }
    return null;
  }

  private void logDbFailure(String operation, Exception error, String contractId, String commitSha) {
    String sqlState = "-";
    String sqlVendorCode = "-";
    if (error instanceof SQLException sqlError) {
      sqlState = safeValue(sqlError.getSQLState());
      sqlVendorCode = Integer.toString(sqlError.getErrorCode());
    }

    LOGGER.error(
        "event=db_operation_failed component=check_run_store operation={} db_target={} contract_id={} commit_sha={} sql_state={} sql_vendor_code={} error_type={} error_message={}",
        operation,
        dbTarget,
        safeValue(contractId),
        safeValue(commitSha),
        sqlState,
        sqlVendorCode,
        error.getClass().getSimpleName(),
        safeValue(error.getMessage()));
  }

  private String safeValue(String value) {
    return value == null || value.isBlank() ? "-" : value;
  }

  private String nullIfBlank(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String serializeAuditDetail(Map<String, Object> detail) {
    if (detail == null || detail.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(detail);
    } catch (JsonProcessingException e) {
      return detail.toString();
    }
  }

  private String normalizeCredential(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private void applyQueryTimeout(PreparedStatement statement) throws SQLException {
    statement.setQueryTimeout(queryTimeoutSeconds);
  }

  private void bindParams(PreparedStatement statement, List<Object> params) throws SQLException {
    for (int i = 0; i < params.size(); i++) {
      statement.setObject(i + 1, params.get(i));
    }
  }

  private String resolveUsername(CheckStoreProperties properties) {
    String configuredUsername = normalizeCredential(properties.getUsername());
    if (configuredUsername != null) {
      return configuredUsername;
    }
    return normalizeCredential(resolveSecretFromEnv(properties.getUsernameEnv()));
  }

  private String resolvePassword(CheckStoreProperties properties) {
    String configuredPassword = trimToEmpty(properties.getPassword());
    if (!configuredPassword.isBlank()) {
      return configuredPassword;
    }
    String fromEnv = resolveSecretFromEnv(properties.getPasswordEnv());
    return fromEnv == null ? "" : fromEnv;
  }

  private String resolveSecretFromEnv(String envVarName) {
    String normalizedEnvVarName = normalizeCredential(envVarName);
    if (normalizedEnvVarName == null) {
      return null;
    }
    String envValue = envLookup.apply(normalizedEnvVarName);
    if (envValue == null || envValue.isBlank()) {
      throw new IllegalStateException(
          "Environment variable '" + normalizedEnvVarName + "' configured for check store credentials is not set or blank.");
    }
    return envValue.trim();
  }

  private void validatePoolAndTimeoutSettings(CheckStoreProperties properties) {
    CheckStoreProperties.Pool pool = properties.getPool();
    if (pool == null) {
      return;
    }

    if (pool.getMaximumSize() < 1) {
      throw new IllegalStateException("checks.db.pool.maximum-size must be greater than 0.");
    }
    if (pool.getMinimumIdle() < 0 || pool.getMinimumIdle() > pool.getMaximumSize()) {
      throw new IllegalStateException(
          "checks.db.pool.minimum-idle must be between 0 and checks.db.pool.maximum-size.");
    }
    requirePositiveDuration("checks.db.query-timeout", properties.getQueryTimeout());
    requirePositiveDuration("checks.db.pool.connection-timeout", pool.getConnectionTimeout());
    requirePositiveDuration("checks.db.pool.idle-timeout", pool.getIdleTimeout());
    requirePositiveDuration("checks.db.pool.max-lifetime", pool.getMaxLifetime());
    requirePositiveDuration("checks.db.pool.validation-timeout", pool.getValidationTimeout());

    Duration initializationFailTimeout = pool.getInitializationFailTimeout();
    if (initializationFailTimeout != null && initializationFailTimeout.toMillis() == 0) {
      throw new IllegalStateException(
          "checks.db.pool.initialization-fail-timeout must be negative (disable) or greater than 0ms.");
    }

    if (isSqliteUrl(jdbcUrl)) {
      validateSqliteSettings(properties.getSqlite(), pool);
    }
  }

  private void validateSqliteSettings(CheckStoreProperties.Sqlite sqlite, CheckStoreProperties.Pool pool) {
    if (sqlite == null) {
      return;
    }

    requirePositiveDuration("checks.db.sqlite.busy-timeout", sqlite.getBusyTimeout());
    String synchronous = normalizeSqliteSynchronous(sqlite.getSynchronous());
    if (!ALLOWED_SQLITE_SYNCHRONOUS.contains(synchronous)) {
      throw new IllegalStateException(
          "checks.db.sqlite.synchronous must be one of " + ALLOWED_SQLITE_SYNCHRONOUS + ".");
    }

    if (sqlite.isEnforceSingleNode()) {
      if (pool.getMaximumSize() != 1) {
        throw new IllegalStateException(
            "checks.db.pool.maximum-size must be 1 when checks.db.sqlite.enforce-single-node=true.");
      }
      if (pool.getMinimumIdle() > 1) {
        throw new IllegalStateException(
            "checks.db.pool.minimum-idle must be <= 1 when checks.db.sqlite.enforce-single-node=true.");
      }
    }
  }

  private void validatePostgresSecurityConstraints(
      String configuredDbUrl,
      CheckStoreProperties.Ssl sslProperties,
      boolean enforceSecurePostgres) {
    if (!enforceSecurePostgres || !isPostgresUrl(configuredDbUrl)) {
      return;
    }
    if (sslProperties == null || !sslProperties.isEnabled()) {
      throw new IllegalStateException(
          "checks.db.ssl.enabled must be true when checks.db.enforce-secure-postgres=true.");
    }
    String sslMode = normalizeSslMode(sslProperties.getMode());
    if (!ALLOWED_STRICT_SSL_MODES.contains(sslMode)) {
      throw new IllegalStateException(
          "checks.db.ssl.mode must be one of "
              + ALLOWED_STRICT_SSL_MODES
              + " when checks.db.enforce-secure-postgres=true.");
    }
  }

  private void validateExpectedSchema(String jdbcUrl, String expectedSchema) {
    String expected = normalizeCredential(expectedSchema);
    if (expected == null || expected.isBlank()) {
      return;
    }
    if (!isPostgresUrl(jdbcUrl)) {
      return;
    }

    String rawSchema = queryParamValue(jdbcUrl, "currentSchema");
    if (rawSchema == null || rawSchema.isBlank()) {
      throw new IllegalStateException(
          "checks.db.expected-schema is set, but checks.db.url is missing currentSchema=.");
    }
    String decoded = URLDecoder.decode(rawSchema, StandardCharsets.UTF_8);
    boolean matches = false;
    for (String item : decoded.split(",")) {
      if (expected.equals(item.trim())) {
        matches = true;
        break;
      }
    }
    if (!matches) {
      throw new IllegalStateException(
          "checks.db.expected-schema is set to '" + expected + "', but currentSchema is '" + decoded + "'.");
    }
  }

  private String queryParamValue(String url, String key) {
    if (url == null || key == null || key.isBlank()) {
      return null;
    }
    int queryIndex = url.indexOf('?');
    if (queryIndex < 0 || queryIndex == url.length() - 1) {
      return null;
    }
    String query = url.substring(queryIndex + 1);
    for (String part : query.split("&")) {
      if (part.isBlank()) {
        continue;
      }
      int eqIndex = part.indexOf('=');
      String name = eqIndex >= 0 ? part.substring(0, eqIndex) : part;
      if (name.equalsIgnoreCase(key)) {
        return eqIndex >= 0 ? part.substring(eqIndex + 1) : "";
      }
    }
    return null;
  }

  private void requirePositiveDuration(String propertyName, Duration value) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalStateException(propertyName + " must be greater than 0.");
    }
  }

  private boolean isPostgresUrl(String value) {
    return value != null && value.startsWith("jdbc:postgresql:");
  }

  private boolean isMySqlUrl(String value) {
    return value != null && value.startsWith("jdbc:mysql:");
  }

  private boolean isSqliteUrl(String value) {
    return value != null && value.startsWith(SQLITE_JDBC_PREFIX);
  }

  private String normalizeSslMode(String value) {
    String normalized = normalizeCredential(value);
    return normalized == null ? "" : normalized.toLowerCase(Locale.ROOT);
  }

  private String normalizeSqliteSynchronous(String value) {
    String normalized = normalizeCredential(value);
    if (normalized == null || normalized.isBlank()) {
      return "NORMAL";
    }
    return normalized.toUpperCase(Locale.ROOT);
  }

  private String normalizeCompatibilityMode(String value, String fallback) {
    String normalized = normalizeCredential(value);
    if (normalized == null) {
      return defaultIfBlank(fallback, "");
    }
    String upper = normalized.toUpperCase(Locale.ROOT);
    if (!ALLOWED_COMPATIBILITY_MODES.contains(upper)) {
      return defaultIfBlank(fallback, "");
    }
    return upper;
  }

  private HikariDataSource createDataSource(
      String jdbcUrl,
      String username,
      String password,
      CheckStoreProperties.Pool poolProperties) {
    CheckStoreProperties.Pool pool = poolProperties == null ? new CheckStoreProperties.Pool() : poolProperties;
    int maxPoolSize = pool.getMaximumSize();
    int minIdle = pool.getMinimumIdle();

    HikariConfig config = new HikariConfig();
    config.setPoolName("check-run-store-pool");
    config.setJdbcUrl(jdbcUrl);
    if (username != null) {
      config.setUsername(username);
      config.setPassword(password);
    }
    config.setMaximumPoolSize(maxPoolSize);
    config.setMinimumIdle(minIdle);
    config.setConnectionTimeout(toPositiveMillis(pool.getConnectionTimeout(), Duration.ofSeconds(1), 250));
    config.setIdleTimeout(toPositiveMillis(pool.getIdleTimeout(), Duration.ofMinutes(2), 1000));
    config.setMaxLifetime(toPositiveMillis(pool.getMaxLifetime(), Duration.ofMinutes(30), 30000));
    config.setValidationTimeout(toPositiveMillis(pool.getValidationTimeout(), Duration.ofSeconds(3), 250));
    config.setInitializationFailTimeout(toInitializationFailTimeoutMillis(pool.getInitializationFailTimeout()));
    config.setAutoCommit(true);
    if (isSqliteUrl(jdbcUrl)) {
      config.setConnectionInitSql("PRAGMA busy_timeout=" + sqliteBusyTimeoutMillis(sqliteSettings.getBusyTimeout()));
    }
    return new HikariDataSource(config);
  }

  private int toQueryTimeoutSeconds(Duration timeout) {
    Duration normalized = timeout == null ? Duration.ofSeconds(5) : timeout;
    if (normalized.isNegative() || normalized.isZero()) {
      return 5;
    }
    long seconds = normalized.toSeconds();
    if (seconds <= 0) {
      return 1;
    }
    if (seconds > Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    return (int) seconds;
  }

  private long sqliteBusyTimeoutMillis(Duration timeout) {
    Duration normalized = timeout == null ? Duration.ofSeconds(5) : timeout;
    long millis = normalized.toMillis();
    if (millis <= 0) {
      millis = Duration.ofSeconds(5).toMillis();
    }
    return Math.max(1, millis);
  }

  private long toPositiveMillis(Duration value, Duration fallback, long minimum) {
    Duration normalized = value == null ? fallback : value;
    long millis = normalized.toMillis();
    if (millis <= 0) {
      millis = fallback.toMillis();
    }
    return Math.max(minimum, millis);
  }

  private long toInitializationFailTimeoutMillis(Duration value) {
    Duration normalized = value == null ? Duration.ofMillis(-1) : value;
    long millis = normalized.toMillis();
    if (millis < 0) {
      return -1;
    }
    return Math.max(1, millis);
  }

  private String withPostgresSslOptions(String url, CheckStoreProperties.Ssl sslProperties) {
    if (sslProperties == null || !sslProperties.isEnabled() || !url.startsWith("jdbc:postgresql:")) {
      return url;
    }

    String withMode = appendQueryParamIfMissing(url, "sslmode", defaultIfBlank(sslProperties.getMode(), "require"));
    String withRootCert = appendQueryParamIfMissing(withMode, "sslrootcert", sslProperties.getRootCertPath());
    String withClientCert = appendQueryParamIfMissing(withRootCert, "sslcert", sslProperties.getCertPath());
    return appendQueryParamIfMissing(withClientCert, "sslkey", sslProperties.getKeyPath());
  }

  private String appendQueryParamIfMissing(String url, String key, String value) {
    if (value == null || value.isBlank() || containsQueryParam(url, key)) {
      return url;
    }
    String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
    char delimiter = url.contains("?") ? '&' : '?';
    return url + delimiter + key + "=" + encoded;
  }

  private boolean containsQueryParam(String url, String key) {
    return Pattern.compile("(?i)([?&])" + Pattern.quote(key) + "=").matcher(url).find();
  }

  private String trimToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  private String defaultIfBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private Path resolveSqlitePath(String value) {
    if (!value.startsWith(SQLITE_JDBC_PREFIX)) {
      return null;
    }

    String raw = value.substring(SQLITE_JDBC_PREFIX.length());
    int queryStart = raw.indexOf('?');
    if (queryStart >= 0) {
      raw = raw.substring(0, queryStart);
    }
    if (raw.isBlank() || raw.equals(":memory:") || raw.startsWith("file::memory:")) {
      return null;
    }
    if (raw.startsWith("file:")) {
      raw = raw.substring("file:".length());
    }

    try {
      return Paths.get(raw);
    } catch (InvalidPathException ignored) {
      return null;
    }
  }

  private DatabaseBackend backendFromJdbcUrl(String value) {
    if (isPostgresUrl(value)) {
      return DatabaseBackend.POSTGRESQL;
    }
    if (isMySqlUrl(value)) {
      return DatabaseBackend.MYSQL;
    }
    if (isSqliteUrl(value)) {
      return DatabaseBackend.SQLITE;
    }
    return DatabaseBackend.JDBC;
  }

  private enum DatabaseBackend {
    POSTGRESQL("postgresql"),
    SQLITE("sqlite"),
    MYSQL("mysql"),
    JDBC("jdbc");

    private final String label;

    DatabaseBackend(String label) {
      this.label = label;
    }

    String label() {
      return label;
    }
  }

  private record MigrationLocation(
      String classpathLocation,
      String latestMigrationResource,
      String filesystemDirectory) {}

  private String sanitizeJdbcUrl(String value) {
    String sanitized = value.replaceAll("(?i)(password=)[^&;]+", "$1****");

    int schemeStart = sanitized.indexOf("://");
    if (schemeStart < 0) {
      return sanitized;
    }
    int credentialsStart = schemeStart + 3;
    int credentialsEnd = sanitized.indexOf('@', credentialsStart);
    if (credentialsEnd < 0) {
      return sanitized;
    }
    return sanitized.substring(0, credentialsStart) + "***:***" + sanitized.substring(credentialsEnd);
  }

  private String computeInputHash(CheckRunCreateRequest request) {
    return computeInputHash(
        request.contractId(),
        request.baseVersion(),
        request.candidateVersion(),
        request.mode(),
        request.commitSha(),
        request.triggeredBy());
  }

  private String computeInputHash(
      String contractId,
      String baseVersion,
      String candidateVersion,
      String mode,
      String commitSha,
      String triggeredBy) {
    String safeContractId = contractId == null || contractId.isBlank() ? "-" : contractId;
    String safeBaseVersion = baseVersion == null || baseVersion.isBlank() ? "-" : baseVersion;
    String safeCandidateVersion = candidateVersion == null || candidateVersion.isBlank() ? "-" : candidateVersion;
    String safeMode = mode == null || mode.isBlank() ? "-" : mode;
    String safeCommitSha = commitSha == null || commitSha.isBlank() ? "-" : commitSha;
    String safeTriggeredBy = triggeredBy == null || triggeredBy.isBlank() ? "-" : triggeredBy;
    String payload = String.join(
        "|",
        safeContractId,
        safeBaseVersion,
        safeCandidateVersion,
        safeMode,
        safeCommitSha,
        safeTriggeredBy);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to compute input hash for check run.", ex);
    }
  }

  private List<String> parseDetails(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }

    String trimmed = raw.trim();
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
      try {
        List<String> values = objectMapper.readValue(trimmed, STRING_LIST_TYPE);
        return values == null ? List.of() : values;
      } catch (Exception ignored) {
        // Fallback to legacy parsing below.
      }
    }

    return java.util.Arrays.stream(trimmed.split("\\s*\\|\\s*"))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .collect(Collectors.toList());
  }

  private String toJsonArray(List<String> values) {
    if (values == null || values.isEmpty()) {
      return "[]";
    }
    try {
      return objectMapper.writeValueAsString(values);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize check run details.", e);
    }
  }

  private String toJsonMap(Map<String, String> values) {
    if (values == null || values.isEmpty()) {
      return "{}";
    }
    try {
      return objectMapper.writeValueAsString(values);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Failed to serialize notification links.", error);
    }
  }

  private Map<String, String> parseLinks(String raw) {
    if (raw == null || raw.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, String> values = objectMapper.readValue(raw, STRING_MAP_TYPE);
      return values == null ? Map.of() : Map.copyOf(values);
    } catch (Exception ignored) {
      return Map.of();
    }
  }

  private void bindNotificationDelivery(PreparedStatement statement, NotificationDelivery delivery)
      throws SQLException {
    NotificationEvent event = delivery.event();
    int index = 1;
    statement.setString(index++, delivery.deliveryId());
    statement.setString(index++, event.eventId());
    statement.setString(index++, event.eventType().name());
    statement.setString(index++, event.severity().name());
    statement.setString(index++, event.occurredAt().toString());
    statement.setString(index++, event.contractId());
    statement.setString(index++, event.runId());
    statement.setString(index++, event.baseVersion());
    statement.setString(index++, event.candidateVersion());
    statement.setString(index++, event.commitSha());
    statement.setString(index++, event.triggeredBy());
    statement.setString(index++, event.policyPack());
    statement.setString(index++, event.summary());
    statement.setString(index++, toJsonArray(event.breakingChanges()));
    statement.setString(index++, toJsonArray(event.warnings()));
    statement.setString(index++, toJsonMap(event.links()));
    statement.setString(index++, event.dedupeKey());
    statement.setString(index++, delivery.sinkName());
    statement.setString(index++, delivery.status().name());
    statement.setInt(index++, delivery.attemptCount());
    statement.setString(index++, delivery.createdAt().toString());
    statement.setString(index++, delivery.lastAttemptAt() == null ? null : delivery.lastAttemptAt().toString());
    statement.setString(index++, delivery.deliveredAt() == null ? null : delivery.deliveredAt().toString());
    statement.setString(index++, delivery.nextAttemptAt() == null ? null : delivery.nextAttemptAt().toString());
    statement.setString(index, delivery.failureMessage());
  }

  private Optional<NotificationDelivery> findNotificationDeliveryByDedupe(
      String dedupeKey, String sinkName) {
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(
             CheckRunSqlQueries.FIND_NOTIFICATION_DELIVERY_BY_DEDUPE)) {
      applyQueryTimeout(statement);
      statement.setString(1, dedupeKey);
      statement.setString(2, sinkName);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next()
            ? Optional.of(mapNotificationDelivery(resultSet))
            : Optional.empty();
      }
    } catch (SQLException ignored) {
      return Optional.empty();
    }
  }

  private NotificationDelivery mapNotificationDelivery(ResultSet resultSet) throws SQLException {
    NotificationEvent event = new NotificationEvent(
        resultSet.getString("event_id"),
        NotificationEventType.valueOf(resultSet.getString("event_type")),
        NotificationSeverity.valueOf(resultSet.getString("severity")),
        parseInstant(resultSet.getString("occurred_at")),
        resultSet.getString("contract_id"),
        resultSet.getString("run_id"),
        resultSet.getString("base_version"),
        resultSet.getString("candidate_version"),
        resultSet.getString("commit_sha"),
        resultSet.getString("triggered_by"),
        resultSet.getString("policy_pack"),
        resultSet.getString("summary"),
        parseDetails(resultSet.getString("breaking_changes")),
        parseDetails(resultSet.getString("warnings")),
        parseLinks(resultSet.getString("links")),
        resultSet.getString("dedupe_key"));
    return new NotificationDelivery(
        resultSet.getString("delivery_id"),
        event,
        resultSet.getString("sink_name"),
        NotificationDeliveryStatus.valueOf(resultSet.getString("status")),
        resultSet.getInt("attempt_count"),
        parseInstant(resultSet.getString("created_at")),
        parseInstant(resultSet.getString("last_attempt_at")),
        parseInstant(resultSet.getString("delivered_at")),
        parseInstant(resultSet.getString("next_attempt_at")),
        resultSet.getString("failure_message"));
  }

  private Instant parseInstant(String value) {
    String normalized = trimToEmpty(value);
    if (normalized.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(normalized);
    } catch (Exception ignored) {
      return null;
    }
  }

  private CheckRunResponse mapRow(ResultSet rs) throws SQLException {
    return new CheckRunResponse(
        rs.getString("run_id"),
        rs.getString("contract_id"),
        rs.getString("base_version"),
        rs.getString("candidate_version"),
        rs.getString("status"),
        parseDetails(rs.getString("breaking_changes")),
        parseDetails(rs.getString("warnings")),
        rs.getString("commit_sha"),
        rs.getString("created_at"),
        rs.getString("triggered_by"),
        rs.getString("started_at"),
        rs.getString("finished_at"));
  }
}
