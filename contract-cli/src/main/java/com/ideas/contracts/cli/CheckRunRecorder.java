package com.ideas.contracts.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideas.contracts.core.CompatibilityResult;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

public class CheckRunRecorder {
  private static final String SQLITE_JDBC_PREFIX = "jdbc:sqlite:";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public void record(
      Path dbPath,
      String contractId,
      String baseVersion,
      String candidateVersion,
      CompatibilityResult result,
      String commitSha) {
    String sqliteJdbcUrl = SQLITE_JDBC_PREFIX + dbPath;
    record(
        sqliteJdbcUrl,
        null,
        null,
        dbPath.toAbsolutePath().toString(),
        contractId,
        baseVersion,
        candidateVersion,
        result,
        commitSha);
  }

  public void record(
      String jdbcUrl,
      String username,
      String password,
      String contractId,
      String baseVersion,
      String candidateVersion,
      CompatibilityResult result,
      String commitSha) {
    String dbTarget = sanitizeJdbcUrl(jdbcUrl);
    record(jdbcUrl, username, password, dbTarget, contractId, baseVersion, candidateVersion, result, commitSha);
  }

  private void record(
      String jdbcUrl,
      String username,
      String password,
      String dbTarget,
      String contractId,
      String baseVersion,
      String candidateVersion,
      CompatibilityResult result,
      String commitSha) {
    try {
      Path sqlitePath = resolveSqlitePath(jdbcUrl);
      if (sqlitePath != null) {
        Path parent = sqlitePath.toAbsolutePath().getParent();
        if (parent != null) {
          java.nio.file.Files.createDirectories(parent);
        }
      }
      migrateSchema(jdbcUrl, username, password);
      try (Connection connection = openConnection(jdbcUrl, username, password)) {
        insertRow(connection, contractId, baseVersion, candidateVersion, result, commitSha);
      }
    } catch (SQLException e) {
      logDbFailure("record_check_run", dbTarget, contractId, baseVersion, candidateVersion, commitSha, e);
      throw new IllegalStateException("Failed to record check run in database target: " + dbTarget, e);
    } catch (Exception e) {
      logDbFailure("record_check_run", dbTarget, contractId, baseVersion, candidateVersion, commitSha, e);
      throw new IllegalStateException("Failed to record check run in database target: " + dbTarget, e);
    }
  }

  private void migrateSchema(String jdbcUrl, String username, String password) {
    Flyway.configure()
        .dataSource(jdbcUrl, normalizeCredential(username), normalizeCredential(password))
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion(MigrationVersion.fromVersion("0"))
        .load()
        .migrate();
  }

  private void insertRow(
      Connection connection,
      String contractId,
      String baseVersion,
      String candidateVersion,
      CompatibilityResult result,
      String commitSha) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO check_runs (
          run_id, contract_id, base_version, candidate_version, status,
          breaking_changes, warnings, commit_sha, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)) {
      statement.setString(1, UUID.randomUUID().toString());
      statement.setString(2, contractId);
      statement.setString(3, baseVersion);
      statement.setString(4, candidateVersion);
      statement.setString(5, result.status().name());
      statement.setString(6, toJsonArray(result.breakingChanges()));
      statement.setString(7, toJsonArray(result.warnings()));
      statement.setString(8, commitSha);
      statement.setString(9, Instant.now().toString());
      statement.executeUpdate();
    }
  }

  private String toJsonArray(java.util.List<String> values) {
    try {
      return OBJECT_MAPPER.writeValueAsString(values);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize check details.", e);
    }
  }

  private void logDbFailure(
      String operation,
      String dbTarget,
      String contractId,
      String baseVersion,
      String candidateVersion,
      String commitSha,
      Exception error) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("event", "db_operation_failed");
    payload.put("component", "check_run_recorder");
    payload.put("operation", operation);
    payload.put("dbTarget", dbTarget);
    payload.put("dbPath", dbTarget);
    payload.put("contractId", safeValue(contractId));
    payload.put("baseVersion", safeValue(baseVersion));
    payload.put("candidateVersion", safeValue(candidateVersion));
    payload.put("commitSha", safeValue(commitSha));
    payload.put("errorType", error.getClass().getSimpleName());
    payload.put("errorMessage", safeValue(error.getMessage()));
    if (error instanceof SQLException sqlError) {
      payload.put("sqlState", safeValue(sqlError.getSQLState()));
      payload.put("sqlVendorCode", Integer.toString(sqlError.getErrorCode()));
    } else {
      payload.put("sqlState", "-");
      payload.put("sqlVendorCode", "-");
    }
    payload.put("timestamp", Instant.now().toString());

    try {
      System.err.println(OBJECT_MAPPER.writeValueAsString(payload));
    } catch (JsonProcessingException serializationError) {
      System.err.println(
          "event=db_operation_failed component=check_run_recorder operation="
              + operation
              + " dbTarget="
              + dbTarget);
    }
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

  private Connection openConnection(String jdbcUrl, String username, String password) throws SQLException {
    if (username == null || username.isBlank()) {
      return DriverManager.getConnection(jdbcUrl);
    }
    Properties properties = new Properties();
    properties.setProperty("user", username.trim());
    properties.setProperty("password", password == null ? "" : password);
    return DriverManager.getConnection(jdbcUrl, properties);
  }

  private Path resolveSqlitePath(String jdbcUrl) {
    if (jdbcUrl == null || !jdbcUrl.startsWith(SQLITE_JDBC_PREFIX)) {
      return null;
    }

    String raw = jdbcUrl.substring(SQLITE_JDBC_PREFIX.length());
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

  private String sanitizeJdbcUrl(String value) {
    if (value == null || value.isBlank()) {
      return "-";
    }
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
}
