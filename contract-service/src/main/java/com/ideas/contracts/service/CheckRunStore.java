package com.ideas.contracts.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideas.contracts.service.model.CheckRunResponse;
import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CheckRunStore {
  private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

  private final Path dbPath;
  private final ObjectMapper objectMapper;

  public CheckRunStore(@Value("${checks.db.path:checks.db}") String dbPath) {
    this.dbPath = Paths.get(dbPath);
    this.objectMapper = new ObjectMapper();
  }

  @PostConstruct
  public void initialize() {
    try {
      Path parent = dbPath.toAbsolutePath().getParent();
      if (parent != null) {
        java.nio.file.Files.createDirectories(parent);
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
    } catch (Exception e) {
      throw new IllegalStateException("Failed to initialize SQLite check store at: " + dbPath, e);
    }
  }

  public List<CheckRunResponse> list(String contractId, String commitSha) {
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
      throw new IllegalStateException("Failed to query check runs from SQLite.", e);
    }
  }

  private Connection openConnection() throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
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
