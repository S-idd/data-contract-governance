package com.ideas.contracts.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideas.contracts.service.model.CheckRunResponse;
import jakarta.annotation.PostConstruct;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CheckRunStore {
  private static final String SQLITE_JDBC_PREFIX = "jdbc:sqlite:";
  private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
  private static final Logger LOGGER = LoggerFactory.getLogger(CheckRunStore.class);

  public record HealthSnapshot(boolean available, String reason) {}

  private final String jdbcUrl;
  private final String dbUsername;
  private final String dbPassword;
  private final Path sqlitePath;
  private final String dbTarget;
  private final ObjectMapper objectMapper;
  private final Object initLock = new Object();
  private volatile boolean initialized;

  public CheckRunStore(
      @Value("${checks.db.url:}") String dbUrl,
      @Value("${checks.db.path:checks.db}") String dbPath,
      @Value("${checks.db.username:}") String dbUsername,
      @Value("${checks.db.password:}") String dbPassword) {
    String trimmedUrl = dbUrl == null ? "" : dbUrl.trim();
    if (trimmedUrl.isBlank()) {
      Path resolvedPath = Paths.get(dbPath);
      this.jdbcUrl = SQLITE_JDBC_PREFIX + resolvedPath;
      this.sqlitePath = resolvedPath;
      this.dbTarget = resolvedPath.toAbsolutePath().toString();
    } else {
      this.jdbcUrl = trimmedUrl;
      this.sqlitePath = resolveSqlitePath(trimmedUrl);
      this.dbTarget = sanitizeJdbcUrl(trimmedUrl);
    }
    this.dbUsername = normalizeCredential(dbUsername);
    this.dbPassword = dbPassword == null ? "" : dbPassword;
    this.objectMapper = new ObjectMapper();
  }

  @PostConstruct
  public void initialize() {
    if (!tryInitialize(true)) {
      LOGGER.warn(
          "event=check_store_init_deferred component=check_run_store db_target={} message=Will retry on first checks request",
          dbTarget);
    }
  }

  public List<CheckRunResponse> list(String contractId, String commitSha) {
    ensureInitialized();

    StringBuilder sql = new StringBuilder("""
        SELECT run_id, contract_id, base_version, candidate_version, status,
               breaking_changes, warnings, commit_sha, created_at
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
      for (int i = 0; i < params.size(); i++) {
        statement.setObject(i + 1, params.get(i));
      }
      try (ResultSet rs = statement.executeQuery()) {
        List<CheckRunResponse> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(new CheckRunResponse(
              rs.getString("run_id"),
              rs.getString("contract_id"),
              rs.getString("base_version"),
              rs.getString("candidate_version"),
              rs.getString("status"),
              parseDetails(rs.getString("breaking_changes")),
              parseDetails(rs.getString("warnings")),
              rs.getString("commit_sha"),
              rs.getString("created_at")));
        }
        return rows;
      }
    } catch (SQLException e) {
      logDbFailure("list_check_runs", e, contractId, commitSha);
      throw new CheckRunStoreException("Failed to query check runs from configured database.", e);
    }
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

  public HealthSnapshot healthSnapshot() {
    if (!(initialized || tryInitialize(false))) {
      return new HealthSnapshot(false, "initialization_failed");
    }
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement("SELECT 1");
         ResultSet resultSet = statement.executeQuery()) {
      return resultSet.next()
          ? new HealthSnapshot(true, "ok")
          : new HealthSnapshot(false, "health_query_failed");
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
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 CREATE TABLE IF NOT EXISTS check_runs (
                   run_id TEXT PRIMARY KEY,
                   contract_id TEXT NOT NULL,
                   base_version TEXT NOT NULL,
                   candidate_version TEXT NOT NULL,
                   status TEXT NOT NULL,
                   breaking_changes TEXT,
                   warnings TEXT,
                   commit_sha TEXT,
                   created_at TEXT NOT NULL
                 )
                 """)) {
          statement.execute();
        }
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
    if (dbUsername == null) {
      return DriverManager.getConnection(jdbcUrl);
    }
    Properties properties = new Properties();
    properties.setProperty("user", dbUsername);
    properties.setProperty("password", dbPassword);
    return DriverManager.getConnection(jdbcUrl, properties);
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

  private String normalizeCredential(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
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
}
