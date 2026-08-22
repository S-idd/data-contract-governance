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
import com.ideas.contracts.service.model.EvidenceImportRequest;
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
      "db/migration/V11__add_evidence_raw_payload_purge_marker.sql";
  private static final String LATEST_MYSQL_MIGRATION_RESOURCE =
      "db/migration-mysql/V11__add_evidence_raw_payload_purge_marker.sql";
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

  @Override
  public EvidenceImportResult importEvidence(CheckEvidence evidence) {
    ensureInitialized();
    if (evidence == null) {
      throw new IllegalArgumentException("evidence must not be null.");
    }
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(CheckRunSqlQueries.INSERT_CHECK_EVIDENCE)) {
      applyQueryTimeout(statement);
      bindEvidence(statement, evidence);
      statement.executeUpdate();
      return new EvidenceImportResult(evidence, true);
    } catch (SQLException error) {
      Optional<CheckEvidence> existing = findEvidenceByIdempotencyKey(evidence.request().idempotencyKey());
      if (existing.isPresent()) {
        if (existing.get().payloadSha256().equals(evidence.payloadSha256())) {
          return new EvidenceImportResult(existing.get(), false);
        }
        throw new EvidenceIdempotencyConflictException(evidence.request().idempotencyKey());
      }
      logDbFailure("import_check_evidence", error, evidence.request().contractId(), evidence.request().commitSha());
      throw new CheckRunStoreException("Failed to import compatibility evidence.", error);
    }
  }

  @Override
  public Optional<CheckEvidence> findEvidenceByIdempotencyKey(String idempotencyKey) {
    ensureInitialized();
    String normalizedKey = trimToEmpty(idempotencyKey);
    if (normalizedKey.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey must not be blank.");
    }
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(
             CheckRunSqlQueries.FIND_CHECK_EVIDENCE_BY_IDEMPOTENCY)) {
      applyQueryTimeout(statement);
      statement.setString(1, normalizedKey);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? Optional.of(mapEvidence(resultSet)) : Optional.empty();
      }
    } catch (SQLException error) {
      logDbFailure("find_check_evidence", error, null, null);
      throw new CheckRunStoreException("Failed to find compatibility evidence.", error);
    }
  }

  @Override
  public List<CheckEvidence> listEvidence(String contractId, String importStatus, int limit) {
    ensureInitialized();
    int normalizedLimit = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 500));
    String normalizedContractId = trimToEmpty(contractId);
    String normalizedStatus = trimToEmpty(importStatus).toUpperCase(Locale.ROOT);
    if (!normalizedStatus.isBlank()) {
      try {
        EvidenceImportStatus.valueOf(normalizedStatus);
      } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException("importStatus must be UNVERIFIED, VERIFIED, VERSION_SKEW, or REJECTED.");
      }
    }
    StringBuilder sql = new StringBuilder(CheckRunSqlQueries.LIST_CHECK_EVIDENCE_BASE);
    List<Object> params = new ArrayList<>();
    if (!normalizedContractId.isBlank()) {
      sql.append(" AND contract_id = ?");
      params.add(normalizedContractId);
    }
    if (!normalizedStatus.isBlank()) {
      sql.append(" AND import_status = ?");
      params.add(normalizedStatus);
    }
    sql.append(" ORDER BY imported_at DESC, evidence_id DESC LIMIT ?");
    params.add(normalizedLimit);
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(sql.toString())) {
      applyQueryTimeout(statement);
      bindParams(statement, params);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<CheckEvidence> evidence = new ArrayList<>();
        while (resultSet.next()) {
          evidence.add(mapEvidence(resultSet));
        }
        return evidence;
      }
    } catch (SQLException error) {
      logDbFailure("list_check_evidence", error, normalizedContractId, null);
      throw new CheckRunStoreException("Failed to list compatibility evidence.", error);
    }
  }

  @Override
  public List<CheckEvidence> listRetentionCandidates(
      List<String> importStatuses, Instant importedBefore, int limit) {
    ensureInitialized();
    if (importStatuses == null || importStatuses.isEmpty() || importedBefore == null) {
      return List.of();
    }
    List<String> statuses = importStatuses.stream()
        .map(value -> trimToEmpty(value).toUpperCase(Locale.ROOT))
        .filter(value -> !value.isBlank())
        .distinct()
        .toList();
    if (statuses.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(statuses.size(), "?"));
    String sql = CheckRunSqlQueries.LIST_CHECK_EVIDENCE_BASE
        + " AND import_status IN (" + placeholders + ") AND imported_at < ?"
        + " AND LENGTH(raw_evidence) > 0"
        + " AND NOT EXISTS (SELECT 1 FROM evidence_legal_holds h WHERE h.active = 1"
        + " AND (h.evidence_id = check_evidence.evidence_id"
        + " OR (h.evidence_id IS NULL AND h.contract_id = check_evidence.contract_id"
        + " AND (h.repository IS NULL OR h.repository = check_evidence.oidc_repository))))"
        + " ORDER BY imported_at ASC, evidence_id ASC LIMIT ?";
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      applyQueryTimeout(statement);
      int index = 1;
      for (String status : statuses) {
        statement.setString(index++, status);
      }
      statement.setString(index++, importedBefore.toString());
      statement.setInt(index, Math.max(1, Math.min(limit, 1000)));
      try (ResultSet resultSet = statement.executeQuery()) {
        List<CheckEvidence> candidates = new ArrayList<>();
        while (resultSet.next()) {
          candidates.add(mapEvidence(resultSet));
        }
        return candidates;
      }
    } catch (SQLException error) {
      logDbFailure("list_evidence_retention_candidates", error, null, null);
      throw new CheckRunStoreException("Failed to list evidence retention candidates.", error);
    }
  }

  @Override
  public EvidenceLegalHold placeEvidenceLegalHold(EvidenceLegalHold hold) {
    ensureInitialized();
    if (hold == null) {
      throw new IllegalArgumentException("hold must not be null.");
    }
    String holdId = trimToEmpty(hold.holdId());
    if (holdId.isBlank()) {
      throw new IllegalArgumentException("holdId must not be blank.");
    }
    String occurredAt = hold.createdAt().toString();
    try (Connection connection = openConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement insertHold = connection.prepareStatement("""
          INSERT INTO evidence_legal_holds (
            hold_id, evidence_id, contract_id, repository, active, reason, created_by, created_at,
            released_by, released_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """);
          PreparedStatement insertEvent = connection.prepareStatement("""
              INSERT INTO evidence_hold_events (event_id, hold_id, action, actor, reason, occurred_at)
              VALUES (?, ?, ?, ?, ?, ?)
              """)) {
        applyQueryTimeout(insertHold);
        insertHold.setString(1, holdId);
        insertHold.setString(2, hold.evidenceId());
        insertHold.setString(3, hold.contractId());
        insertHold.setString(4, hold.repository());
        insertHold.setInt(5, hold.active() ? 1 : 0);
        insertHold.setString(6, hold.reason());
        insertHold.setString(7, hold.createdBy());
        insertHold.setString(8, occurredAt);
        insertHold.setString(9, hold.releasedBy());
        insertHold.setString(10, hold.releasedAt() == null ? null : hold.releasedAt().toString());
        insertHold.executeUpdate();

        applyQueryTimeout(insertEvent);
        insertEvent.setString(1, UUID.randomUUID().toString());
        insertEvent.setString(2, holdId);
        insertEvent.setString(3, "PLACED");
        insertEvent.setString(4, hold.createdBy());
        insertEvent.setString(5, hold.reason());
        insertEvent.setString(6, occurredAt);
        insertEvent.executeUpdate();
        connection.commit();
        return hold;
      } catch (SQLException error) {
        connection.rollback();
        throw error;
      }
    } catch (SQLException error) {
      logDbFailure("place_evidence_legal_hold", error, hold.contractId(), null);
      throw new CheckRunStoreException("Failed to place evidence legal hold.", error);
    }
  }

  @Override
  public boolean releaseEvidenceLegalHold(String holdId, String releasedBy, String reason) {
    ensureInitialized();
    String normalizedHoldId = trimToEmpty(holdId);
    String actor = trimToEmpty(releasedBy);
    String releaseReason = trimToEmpty(reason);
    if (normalizedHoldId.isBlank() || actor.isBlank() || releaseReason.isBlank()) {
      throw new IllegalArgumentException("holdId, releasedBy, and reason must not be blank.");
    }
    String releasedAt = Instant.now().toString();
    try (Connection connection = openConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement release = connection.prepareStatement("""
          UPDATE evidence_legal_holds
          SET active = 0, released_by = ?, released_at = ?
          WHERE hold_id = ? AND active = 1
          """)) {
        applyQueryTimeout(release);
        release.setString(1, actor);
        release.setString(2, releasedAt);
        release.setString(3, normalizedHoldId);
        if (release.executeUpdate() == 0) {
          connection.commit();
          return false;
        }
      }
      try (PreparedStatement event = connection.prepareStatement("""
          INSERT INTO evidence_hold_events (event_id, hold_id, action, actor, reason, occurred_at)
          VALUES (?, ?, ?, ?, ?, ?)
          """)) {
        applyQueryTimeout(event);
        event.setString(1, UUID.randomUUID().toString());
        event.setString(2, normalizedHoldId);
        event.setString(3, "RELEASED");
        event.setString(4, actor);
        event.setString(5, releaseReason);
        event.setString(6, releasedAt);
        event.executeUpdate();
      }
      connection.commit();
      return true;
    } catch (SQLException error) {
      logDbFailure("release_evidence_legal_hold", error, null, null);
      throw new CheckRunStoreException("Failed to release evidence legal hold.", error);
    }
  }

  @Override
  public boolean recordArchiveAndPurgeRawEvidence(
      String evidenceId, EvidenceArchiveReceipt archive, String policyVersion, String actor) {
    ensureInitialized();
    String normalizedEvidenceId = trimToEmpty(evidenceId);
    String normalizedPolicyVersion = trimToEmpty(policyVersion);
    String normalizedActor = trimToEmpty(actor);
    if (normalizedEvidenceId.isBlank() || archive == null || normalizedPolicyVersion.isBlank()
        || normalizedActor.isBlank()) {
      throw new IllegalArgumentException("evidenceId, archive, policyVersion, and actor must not be blank.");
    }
    if (!normalizedEvidenceId.equals(archive.evidenceId())) {
      throw new IllegalArgumentException("Archive receipt does not belong to the evidence being purged.");
    }
    try (Connection connection = openConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      connection.setAutoCommit(false);
      try {
        RetentionTarget target = findRetentionTarget(connection, normalizedEvidenceId);
        if (target == null || target.rawEvidence().isBlank() || hasActiveLegalHold(connection, target)) {
          connection.commit();
          return false;
        }

        String occurredAt = Instant.now().toString();
        try (PreparedStatement event = connection.prepareStatement("""
            INSERT INTO evidence_retention_events (
              event_id, evidence_id, action, policy_version, archive_location, archive_sha256, actor, occurred_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
          applyQueryTimeout(event);
          event.setString(1, UUID.randomUUID().toString());
          event.setString(2, normalizedEvidenceId);
          event.setString(3, "RAW_PAYLOAD_PURGED");
          event.setString(4, normalizedPolicyVersion);
          event.setString(5, archive.location());
          event.setString(6, archive.sha256());
          event.setString(7, normalizedActor);
          event.setString(8, occurredAt);
          event.executeUpdate();
        }
        try (PreparedStatement purge = connection.prepareStatement("""
            UPDATE check_evidence
            SET raw_evidence = '', raw_evidence_archived_at = ?, raw_evidence_archive_location = ?,
                raw_evidence_archive_sha256 = ?, raw_evidence_purged_at = ?
            WHERE evidence_id = ? AND LENGTH(raw_evidence) > 0
            """)) {
          applyQueryTimeout(purge);
          purge.setString(1, archive.archivedAt().toString());
          purge.setString(2, archive.location());
          purge.setString(3, archive.sha256());
          purge.setString(4, occurredAt);
          purge.setString(5, normalizedEvidenceId);
          if (purge.executeUpdate() != 1) {
            connection.rollback();
            return false;
          }
        }
        connection.commit();
        return true;
      } catch (SQLException error) {
        connection.rollback();
        throw error;
      }
    } catch (SQLException error) {
      logDbFailure("archive_and_purge_evidence", error, null, null);
      throw new CheckRunStoreException("Failed to record archived evidence payload purge.", error);
    }
  }

  @Override
  public List<EvidenceRetentionEvent> listEvidenceRetentionEvents(String evidenceId, int limit) {
    ensureInitialized();
    String normalizedEvidenceId = trimToEmpty(evidenceId);
    if (normalizedEvidenceId.isBlank()) {
      throw new IllegalArgumentException("evidenceId must not be blank.");
    }
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement("""
             SELECT event_id, evidence_id, action, policy_version, archive_location, archive_sha256, actor, occurred_at
             FROM evidence_retention_events
             WHERE evidence_id = ?
             ORDER BY occurred_at ASC, event_id ASC
             LIMIT ?
             """)) {
      applyQueryTimeout(statement);
      statement.setString(1, normalizedEvidenceId);
      statement.setInt(2, Math.max(1, Math.min(limit, 500)));
      try (ResultSet resultSet = statement.executeQuery()) {
        List<EvidenceRetentionEvent> events = new ArrayList<>();
        while (resultSet.next()) {
          events.add(new EvidenceRetentionEvent(
              resultSet.getString("event_id"), resultSet.getString("evidence_id"),
              resultSet.getString("action"), resultSet.getString("policy_version"),
              resultSet.getString("archive_location"), resultSet.getString("archive_sha256"),
              resultSet.getString("actor"), parseInstant(resultSet.getString("occurred_at"))));
        }
        return events;
      }
    } catch (SQLException error) {
      logDbFailure("list_evidence_retention_events", error, null, null);
      throw new CheckRunStoreException("Failed to list evidence retention events.", error);
    }
  }

  @Override
  public EvidenceRateLimitDecision tryAcquireEvidenceRateLimit(
      String bucketKey, String windowType, Instant windowStart, int maxRequests, Instant now) {
    ensureInitialized();
    String key = trimToEmpty(bucketKey);
    String type = trimToEmpty(windowType);
    if (key.isBlank() || type.isBlank() || windowStart == null || now == null || maxRequests < 1) {
      throw new IllegalArgumentException("Rate-limit bucket, window, current time, and positive limit are required.");
    }
    Instant windowEnd = windowStart.plus(parseWindowSeconds(type), java.time.temporal.ChronoUnit.SECONDS);
    long retryAfterSeconds = Math.max(1, Duration.between(now, windowEnd).toSeconds() + 1);
    for (int attempt = 0; attempt < 3; attempt++) {
      try (Connection connection = openConnection()) {
        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        connection.setAutoCommit(false);
        try {
          RateLimitBucket existing = findRateLimitBucket(connection, key);
          if (existing == null) {
            try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO evidence_rate_limit_buckets (
                  bucket_key, window_type, window_started_at, request_count, updated_at
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
              applyQueryTimeout(insert);
              insert.setString(1, key);
              insert.setString(2, type);
              insert.setString(3, windowStart.toString());
              insert.setInt(4, 1);
              insert.setString(5, now.toString());
              insert.executeUpdate();
            }
            connection.commit();
            return new EvidenceRateLimitDecision(true, retryAfterSeconds);
          }
          if (type.equals(existing.windowType()) && windowStart.equals(existing.windowStartedAt())) {
            if (existing.requestCount() >= maxRequests) {
              connection.commit();
              return new EvidenceRateLimitDecision(false, retryAfterSeconds);
            }
            try (PreparedStatement increment = connection.prepareStatement("""
                UPDATE evidence_rate_limit_buckets
                SET request_count = request_count + 1, updated_at = ?
                WHERE bucket_key = ? AND window_type = ? AND window_started_at = ? AND request_count < ?
                """)) {
              applyQueryTimeout(increment);
              increment.setString(1, now.toString());
              increment.setString(2, key);
              increment.setString(3, type);
              increment.setString(4, windowStart.toString());
              increment.setInt(5, maxRequests);
              if (increment.executeUpdate() == 1) {
                connection.commit();
                return new EvidenceRateLimitDecision(true, retryAfterSeconds);
              }
            }
          } else {
            try (PreparedStatement reset = connection.prepareStatement("""
                UPDATE evidence_rate_limit_buckets
                SET window_type = ?, window_started_at = ?, request_count = 1, updated_at = ?
                WHERE bucket_key = ? AND window_type = ? AND window_started_at = ?
                """)) {
              applyQueryTimeout(reset);
              reset.setString(1, type);
              reset.setString(2, windowStart.toString());
              reset.setString(3, now.toString());
              reset.setString(4, key);
              reset.setString(5, existing.windowType());
              reset.setString(6, existing.windowStartedAt().toString());
              if (reset.executeUpdate() == 1) {
                connection.commit();
                return new EvidenceRateLimitDecision(true, retryAfterSeconds);
              }
            }
          }
          connection.rollback();
        } catch (SQLException error) {
          connection.rollback();
          if (attempt == 2) {
            throw error;
          }
        }
      } catch (SQLException error) {
        if (attempt == 2) {
          logDbFailure("acquire_evidence_rate_limit", error, null, null);
          throw new CheckRunStoreException("Failed to enforce evidence rate limit.", error);
        }
      }
    }
    return new EvidenceRateLimitDecision(false, retryAfterSeconds);
  }

  private RateLimitBucket findRateLimitBucket(Connection connection, String bucketKey) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT window_type, window_started_at, request_count
        FROM evidence_rate_limit_buckets
        WHERE bucket_key = ?
        """)) {
      applyQueryTimeout(statement);
      statement.setString(1, bucketKey);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next()
            ? new RateLimitBucket(
                resultSet.getString("window_type"), parseInstant(resultSet.getString("window_started_at")),
                resultSet.getInt("request_count"))
            : null;
      }
    }
  }

  private long parseWindowSeconds(String windowType) {
    if (!windowType.startsWith("fixed-") || !windowType.endsWith("s")) {
      throw new IllegalArgumentException("Unsupported evidence rate-limit window type.");
    }
    try {
      long seconds = Long.parseLong(windowType.substring("fixed-".length(), windowType.length() - 1));
      if (seconds < 1 || seconds > Duration.ofHours(1).toSeconds()) {
        throw new IllegalArgumentException("Evidence rate-limit window is out of range.");
      }
      return seconds;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("Unsupported evidence rate-limit window type.", error);
    }
  }

  private RetentionTarget findRetentionTarget(Connection connection, String evidenceId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT evidence_id, contract_id, oidc_repository, raw_evidence
        FROM check_evidence
        WHERE evidence_id = ?
        """)) {
      applyQueryTimeout(statement);
      statement.setString(1, evidenceId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next()
            ? new RetentionTarget(
                resultSet.getString("evidence_id"), resultSet.getString("contract_id"),
                resultSet.getString("oidc_repository"), resultSet.getString("raw_evidence"))
            : null;
      }
    }
  }

  private boolean hasActiveLegalHold(Connection connection, RetentionTarget target) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT 1 FROM evidence_legal_holds
        WHERE active = 1
          AND (evidence_id = ?
            OR (evidence_id IS NULL AND contract_id = ? AND (repository IS NULL OR repository = ?)))
        LIMIT 1
        """)) {
      applyQueryTimeout(statement);
      statement.setString(1, target.evidenceId());
      statement.setString(2, target.contractId());
      statement.setString(3, target.repository());
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    }
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

  private void bindEvidence(PreparedStatement statement, CheckEvidence evidence) throws SQLException {
    EvidenceImportRequest request = evidence.request();
    int index = 1;
    statement.setString(index++, evidence.evidenceId());
    statement.setString(index++, request.idempotencyKey());
    statement.setString(index++, evidence.payloadSha256());
    statement.setString(index++, request.contractId());
    statement.setString(index++, request.baseVersion());
    statement.setString(index++, request.candidateVersion());
    statement.setString(index++, request.compatibilityMode());
    statement.setString(index++, request.commitSha());
    statement.setString(index++, request.baseSchemaSha256());
    statement.setString(index++, request.candidateSchemaSha256());
    statement.setString(index++, request.engineVersion());
    statement.setString(index++, request.engineCompatibilityProtocol());
    statement.setString(index++, request.policyPackName());
    statement.setString(index++, request.policyPackSha256());
    statement.setString(index++, request.localStatus());
    statement.setString(index++, toJsonArray(request.breakingChanges()));
    statement.setString(index++, toJsonArray(request.warnings()));
    statement.setString(index++, request.executedAt().toString());
    statement.setString(index++, request.ciIdentity());
    statement.setString(index++, request.buildUrl());
    statement.setString(index++, evidence.rawEvidence());
    statement.setString(index++, evidence.provenance().authenticatedIdentity());
    statement.setString(index++, evidence.provenance().authenticationScheme());
    statement.setString(index++, evidence.provenance().issuer());
    statement.setString(index++, evidence.provenance().subject());
    statement.setString(index++, evidence.provenance().audience());
    statement.setString(index++, evidence.provenance().repository());
    statement.setString(index++, evidence.provenance().ref());
    statement.setString(index++, evidence.importStatus().name());
    statement.setString(index++, evidence.verificationReason());
    statement.setString(index++, evidence.authoritativeRunId());
    statement.setString(index, evidence.importedAt().toString());
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

  private CheckEvidence mapEvidence(ResultSet resultSet) throws SQLException {
    EvidenceImportRequest request = new EvidenceImportRequest(
        "1.0",
        resultSet.getString("idempotency_key"),
        resultSet.getString("contract_id"),
        resultSet.getString("base_version"),
        resultSet.getString("candidate_version"),
        resultSet.getString("compatibility_mode"),
        resultSet.getString("commit_sha"),
        resultSet.getString("base_schema_sha256"),
        resultSet.getString("candidate_schema_sha256"),
        resultSet.getString("engine_version"),
        resultSet.getString("engine_compatibility_protocol"),
        resultSet.getString("policy_pack_name"),
        resultSet.getString("policy_pack_sha256"),
        resultSet.getString("local_status"),
        parseDetails(resultSet.getString("breaking_changes")),
        parseDetails(resultSet.getString("warnings")),
        parseInstant(resultSet.getString("executed_at")),
        resultSet.getString("ci_identity"),
        resultSet.getString("build_url"));
    return new CheckEvidence(
        resultSet.getString("evidence_id"),
        request,
        resultSet.getString("payload_sha256"),
        resultSet.getString("raw_evidence"),
        new EvidenceProvenance(
            resultSet.getString("auth_scheme"),
            resultSet.getString("authenticated_identity"),
            resultSet.getString("oidc_issuer"),
            resultSet.getString("oidc_subject"),
            resultSet.getString("oidc_audience"),
            resultSet.getString("oidc_repository"),
            resultSet.getString("oidc_ref")),
        EvidenceImportStatus.valueOf(resultSet.getString("import_status")),
        resultSet.getString("verification_reason"),
        resultSet.getString("authoritative_run_id"),
        parseInstant(resultSet.getString("imported_at")));
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

  private record RetentionTarget(
      String evidenceId, String contractId, String repository, String rawEvidence) {}

  private record RateLimitBucket(String windowType, Instant windowStartedAt, int requestCount) {}
}
