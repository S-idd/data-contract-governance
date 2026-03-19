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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
public class CheckRunStore {
  private static final String SQLITE_JDBC_PREFIX = "jdbc:sqlite:";
  private static final Set<String> ALLOWED_STRICT_SSL_MODES = Set.of("verify-ca", "verify-full");
  private static final Set<String> ALLOWED_COMPATIBILITY_MODES =
      Set.of("BACKWARD", "FORWARD", "FULL");
  private static final String STATUS_QUEUED = "QUEUED";
  private static final String STATUS_RUNNING = "RUNNING";
  private static final String LATEST_MIGRATION_RESOURCE = "db/migration/V5__create_audit_logs.sql";
  private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
  private static final Logger LOGGER = LoggerFactory.getLogger(CheckRunStore.class);

  public record HealthSnapshot(boolean available, String reason) {}
  public record PoolSnapshot(
      int totalConnections,
      int activeConnections,
      int idleConnections,
      int threadsAwaitingConnection,
      int maximumPoolSize,
      int minimumIdle,
      long connectionTimeoutMs) {}
  public record QueuedCheckRun(
      String runId,
      String contractId,
      String baseVersion,
      String candidateVersion,
      String mode,
      String commitSha,
      String triggeredBy) {}

  private final String jdbcUrl;
  private final Path sqlitePath;
  private final String dbTarget;
  private final HikariDataSource dataSource;
  private final Function<String, String> envLookup;
  private final int queryTimeoutSeconds;
  private final boolean failFastStartup;
  private final ObjectMapper objectMapper;
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
    validateExpectedSchema(this.jdbcUrl, properties.getExpectedSchema());
    validatePoolAndTimeoutSettings(properties);
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

    StringBuilder sql = new StringBuilder("""
        SELECT run_id, contract_id, base_version, candidate_version, status,
               breaking_changes, warnings, commit_sha, created_at,
               triggered_by, started_at, finished_at
        FROM check_runs
        WHERE 1=1
        """);

    List<Object> params = new ArrayList<>();
    if (contractId != null && !contractId.isBlank()) {
      sql.append(" AND contract_id = ?");
      params.add(contractId);
    }
    if (commitSha != null && !commitSha.isBlank()) {
      sql.append(" AND commit_sha = ?");
      params.add(commitSha);
    }
    sql.append(" ORDER BY created_at DESC");

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

    StringBuilder sql = new StringBuilder("""
        SELECT run_id, contract_id, base_version, candidate_version, status,
               breaking_changes, warnings, commit_sha, created_at,
               triggered_by, started_at, finished_at
        FROM check_runs
        WHERE 1=1
        """);
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
    sql.append(" ORDER BY created_at DESC");
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

    String sql = """
        SELECT run_id, contract_id, base_version, candidate_version, status,
               breaking_changes, warnings, commit_sha, created_at,
               triggered_by, started_at, finished_at
        FROM check_runs
        WHERE run_id = ?
        LIMIT 1
        """;
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

    String sql = """
        SELECT log_id, run_id, level, message, created_at
        FROM check_run_logs
        WHERE run_id = ?
        ORDER BY created_at ASC
        """;
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

    String selectSql = """
        SELECT run_id, contract_id, base_version, candidate_version, compatibility_mode,
               commit_sha, triggered_by
        FROM check_runs
        WHERE status = ?
        ORDER BY created_at ASC
        LIMIT 1
        """;
    String updateSql = """
        UPDATE check_runs
        SET status = ?, started_at = ?
        WHERE run_id = ? AND status = ?
        """;

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
    String sql = """
        UPDATE check_runs
        SET status = ?, started_at = NULL, finished_at = NULL
        WHERE run_id = ? AND status = ?
        """;
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

    String sql = """
        INSERT INTO check_run_logs (
          log_id, run_id, level, message, created_at
        ) VALUES (?, ?, ?, ?, ?)
        """;
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

    String sql = """
        UPDATE check_runs
        SET status = ?, breaking_changes = ?, warnings = ?, finished_at = ?
        WHERE run_id = ? AND status = ?
        """;
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

    String sql = """
        INSERT INTO check_runs (
          run_id, contract_id, base_version, candidate_version, status,
          breaking_changes, warnings, commit_sha, created_at,
          triggered_by, compatibility_mode, input_hash, started_at, finished_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

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
    String sql = """
        INSERT INTO audit_logs (
          audit_id, action, actor, actor_roles, source, request_id, http_method, path,
          resource_type, resource_id, status, detail, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

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

  public int backfillLegacyRuns(
      Function<String, String> modeResolver,
      String defaultTriggeredBy,
      String defaultMode) {
    ensureInitialized();
    Function<String, String> resolver = modeResolver == null ? id -> null : modeResolver;
    String fallbackTriggeredBy = defaultIfBlank(defaultTriggeredBy, "legacy");
    String fallbackMode = normalizeCompatibilityMode(defaultMode, "BACKWARD");

    String selectSql = """
        SELECT run_id, contract_id, base_version, candidate_version, commit_sha, created_at, status,
               triggered_by, compatibility_mode, input_hash, started_at, finished_at
        FROM check_runs
        WHERE triggered_by IS NULL
           OR compatibility_mode IS NULL
           OR input_hash IS NULL
           OR started_at IS NULL
           OR finished_at IS NULL
        """;
    String updateSql = """
        UPDATE check_runs
        SET triggered_by = ?, compatibility_mode = ?, input_hash = ?, started_at = ?, finished_at = ?
        WHERE run_id = ?
        """;
    String logExistsSql = """
        SELECT 1
        FROM check_run_logs
        WHERE run_id = ?
        LIMIT 1
        """;
    String insertLogSql = """
        INSERT INTO check_run_logs (
          log_id, run_id, level, message, created_at
        ) VALUES (?, ?, ?, ?, ?)
        """;

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
         PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
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
        migrateSchema();
        initialized = true;
        LOGGER.info(
            "event=check_store_initialized component=check_run_store db_target={} backend={}",
            dbTarget,
            backendFromJdbcUrl(jdbcUrl));
        return true;
      } catch (Exception e) {
        if (logFailure) {
          logDbFailure("initialize_check_store", e, null, null);
        }
        return false;
      }
    }
  }

  private Connection openConnection() throws SQLException {
    return dataSource.getConnection();
  }

  private void migrateSchema() {
    String[] locations = resolveMigrationLocations();
    Flyway.configure()
        .dataSource(dataSource)
        .locations(locations)
        .baselineOnMigrate(true)
        .baselineVersion(MigrationVersion.fromVersion("0"))
        .load()
        .migrate();
  }

  private String[] resolveMigrationLocations() {
    if (classpathMigrationAvailable()) {
      return new String[] {"classpath:db/migration"};
    }

    Path fallback = resolveFilesystemMigrationPath();
    if (fallback != null) {
      LOGGER.warn(
          "event=check_store_migrations_fallback component=check_run_store path={} message=Using filesystem migrations",
          fallback.toAbsolutePath());
      return new String[] {"filesystem:" + fallback.toAbsolutePath()};
    }

    throw new IllegalStateException(
        "No Flyway migrations found for check history store. Ensure db/migration resources are packaged.");
  }

  private boolean classpathMigrationAvailable() {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    if (loader == null) {
      loader = CheckRunStore.class.getClassLoader();
    }
    return loader != null && loader.getResource(LATEST_MIGRATION_RESOURCE) != null;
  }

  private Path resolveFilesystemMigrationPath() {
    Path rootRelative = Paths.get("contract-core", "src", "main", "resources", "db", "migration");
    if (Files.isDirectory(rootRelative)) {
      return rootRelative;
    }
    Path moduleRelative = Paths.get("..", "contract-core", "src", "main", "resources", "db", "migration");
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

  private String normalizeSslMode(String value) {
    String normalized = normalizeCredential(value);
    return normalized == null ? "" : normalized.toLowerCase(Locale.ROOT);
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

  private String backendFromJdbcUrl(String value) {
    if (value.startsWith("jdbc:postgresql:")) {
      return "postgresql";
    }
    if (value.startsWith(SQLITE_JDBC_PREFIX)) {
      return "sqlite";
    }
    return "jdbc";
  }

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
