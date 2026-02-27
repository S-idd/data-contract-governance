package com.ideas.contracts.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideas.contracts.core.CompatibilityResult;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

public class CheckRunRecorder {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public void record(
      Path dbPath,
      String contractId,
      String baseVersion,
      String candidateVersion,
      CompatibilityResult result,
      String commitSha) {
    try {
      Path parent = dbPath.toAbsolutePath().getParent();
      if (parent != null) {
        java.nio.file.Files.createDirectories(parent);
      }
      try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
        createTableIfMissing(connection);
        insertRow(connection, contractId, baseVersion, candidateVersion, result, commitSha);
      }
    } catch (Exception e) {
      throw new IllegalStateException("Failed to record check run in SQLite: " + dbPath, e);
    }
  }

  private void createTableIfMissing(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
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
}
