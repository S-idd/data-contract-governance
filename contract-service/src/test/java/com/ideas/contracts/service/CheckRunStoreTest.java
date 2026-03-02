package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ideas.contracts.service.model.CheckRunResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckRunStoreTest {
  @TempDir
  Path tempDir;

  @Test
  void configuredDbTargetSanitizesJdbcCredentials() {
    CheckRunStore store = new CheckRunStore(
        "jdbc:postgresql://app_user:secret@localhost:5432/contracts?ssl=true&password=top-secret",
        "unused.db",
        "db-user",
        "db-password");

    String configuredTarget = store.configuredDbTarget();
    assertTrue(configuredTarget.contains("***:***@"));
    assertFalse(configuredTarget.contains("secret"));
    assertFalse(configuredTarget.contains("top-secret"));
  }

  @Test
  void listThrowsStoreExceptionWhenDbPathIsUnavailable() throws Exception {
    Path dbPath = tempDir.resolve("checks-directory");
    Files.createDirectories(dbPath);

    CheckRunStore store = new CheckRunStore("", dbPath.toString(), "", "");
    store.initialize();

    CheckRunStoreException exception =
        assertThrows(CheckRunStoreException.class, () -> store.list(null, null));
    assertTrue(exception.getMessage().contains("unavailable"));
  }

  @Test
  void listParsesJsonArrayAndLegacyStringFormats() throws Exception {
    Path dbPath = tempDir.resolve("checks-test.db");
    CheckRunStore store = new CheckRunStore("", dbPath.toString(), "", "");
    store.initialize();

    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
      insertRow(
          connection,
          "run-json",
          "orders.created",
          "FAIL",
          "[\"Field type changed: orderId (string -> integer)\"]",
          "[\"Enum value added: status.SHIPPED\"]",
          "sha-json",
          "2026-02-27T12:00:00Z");
      insertRow(
          connection,
          "run-legacy",
          "orders.created",
          "PASS",
          "Field removed: amount | Required field added: region",
          "",
          "sha-legacy",
          "2026-02-27T11:00:00Z");
    }

    List<CheckRunResponse> rows = store.list("orders.created", null);
    assertEquals(2, rows.size());

    CheckRunResponse latest = rows.get(0);
    assertEquals("run-json", latest.runId());
    assertIterableEquals(
        List.of("Field type changed: orderId (string -> integer)"),
        latest.breakingChanges());
    assertIterableEquals(
        List.of("Enum value added: status.SHIPPED"),
        latest.warnings());

    CheckRunResponse legacy = rows.get(1);
    assertEquals("run-legacy", legacy.runId());
    assertIterableEquals(
        List.of("Field removed: amount", "Required field added: region"),
        legacy.breakingChanges());
    assertIterableEquals(List.of(), legacy.warnings());
  }

  private void insertRow(
      Connection connection,
      String runId,
      String contractId,
      String status,
      String breakingChanges,
      String warnings,
      String commitSha,
      String createdAt) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO check_runs (
          run_id, contract_id, base_version, candidate_version, status,
          breaking_changes, warnings, commit_sha, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)) {
      statement.setString(1, runId);
      statement.setString(2, contractId);
      statement.setString(3, "v1");
      statement.setString(4, "v2");
      statement.setString(5, status);
      statement.setString(6, breakingChanges);
      statement.setString(7, warnings);
      statement.setString(8, commitSha);
      statement.setString(9, createdAt);
      statement.executeUpdate();
    }
  }
}
