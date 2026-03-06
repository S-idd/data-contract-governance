package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ideas.contracts.service.model.CheckRunResponse;
import com.ideas.contracts.service.model.CheckRunPageResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckRunStoreTest {
  @TempDir
  Path tempDir;

  @Test
  void configuredDbTargetSanitizesJdbcCredentials() {
    CheckStoreProperties properties = baseProperties();
    properties.setUrl(
        "jdbc:postgresql://app_user:secret@localhost:5432/contracts?ssl=true&password=top-secret");
    properties.setUsername("db-user");
    properties.setPassword("db-password");

    CheckRunStore store = new CheckRunStore(properties);

    String configuredTarget = store.configuredDbTarget();
    assertTrue(configuredTarget.contains("***:***@"));
    assertFalse(configuredTarget.contains("secret"));
    assertFalse(configuredTarget.contains("top-secret"));
  }

  @Test
  void postgresUrlGetsSslModeWhenSslIsEnabled() {
    CheckStoreProperties properties = baseProperties();
    properties.setUrl("jdbc:postgresql://localhost:5432/contracts");
    properties.getSsl().setEnabled(true);
    properties.getSsl().setMode("verify-full");

    CheckRunStore store = new CheckRunStore(properties);

    assertTrue(store.configuredDbTarget().contains("sslmode=verify-full"));
  }

  @Test
  void listThrowsStoreExceptionWhenDbPathIsUnavailable() throws Exception {
    Path dbPath = tempDir.resolve("checks-directory");
    Files.createDirectories(dbPath);

    CheckStoreProperties properties = baseProperties();
    properties.setPath(dbPath.toString());
    CheckRunStore store = new CheckRunStore(properties);
    store.initialize();

    CheckRunStoreException exception =
        assertThrows(CheckRunStoreException.class, () -> store.list(null, null));
    assertTrue(exception.getMessage().contains("unavailable"));
  }

  @Test
  void listParsesJsonArrayAndLegacyStringFormats() throws Exception {
    Path dbPath = tempDir.resolve("checks-test.db");
    CheckStoreProperties properties = baseProperties();
    properties.setPath(dbPath.toString());
    CheckRunStore store = new CheckRunStore(properties);
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

  @Test
  void listPageSupportsPaginationAndStatusFilter() throws Exception {
    Path dbPath = tempDir.resolve("checks-page.db");
    CheckStoreProperties properties = baseProperties();
    properties.setPath(dbPath.toString());
    CheckRunStore store = new CheckRunStore(properties);
    store.initialize();

    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
      insertRow(connection, "run-1", "orders.created", "PASS", "[]", "[]", "sha-1", "2026-03-01T12:00:03Z");
      insertRow(connection, "run-2", "orders.created", "FAIL", "[]", "[]", "sha-2", "2026-03-01T12:00:02Z");
      insertRow(connection, "run-3", "orders.created", "PASS", "[]", "[]", "sha-3", "2026-03-01T12:00:01Z");
    }

    CheckRunPageResponse firstPage = store.listPage(CheckRunQuery.from("orders.created", null, null, 2, 0));
    assertEquals(2, firstPage.items().size());
    assertTrue(firstPage.hasMore());
    assertEquals("run-1", firstPage.items().get(0).runId());
    assertEquals("run-2", firstPage.items().get(1).runId());

    CheckRunPageResponse passOnly = store.listPage(CheckRunQuery.from("orders.created", null, "PASS", 10, 0));
    assertEquals(2, passOnly.items().size());
    assertEquals("run-1", passOnly.items().get(0).runId());
    assertEquals("run-3", passOnly.items().get(1).runId());
  }

  @Test
  void findByRunIdReturnsMatchingRowOrEmpty() throws Exception {
    Path dbPath = tempDir.resolve("checks-find.db");
    CheckStoreProperties properties = baseProperties();
    properties.setPath(dbPath.toString());
    CheckRunStore store = new CheckRunStore(properties);
    store.initialize();

    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
      insertRow(connection, "find-run-1", "orders.created", "PASS", "[]", "[]", "sha-find", "2026-03-01T12:00:00Z");
    }

    CheckRunResponse found = store.findByRunId("find-run-1").orElseThrow();
    assertEquals("find-run-1", found.runId());
    assertTrue(store.findByRunId("unknown-run").isEmpty());
  }

  @Test
  void constructorFailsWhenCredentialEnvVariableIsMissing() {
    CheckStoreProperties properties = baseProperties();
    properties.setUrl("jdbc:postgresql://localhost:5432/contracts");
    properties.setUsernameEnv("MISSING_DB_USER");

    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> new CheckRunStore(properties, key -> null));
    assertTrue(exception.getMessage().contains("MISSING_DB_USER"));
  }

  @Test
  void listReturnsUnavailableWhenPostgresNetworkIsUnreachable() {
    CheckStoreProperties properties = baseProperties();
    properties.setUrl("jdbc:postgresql://127.0.0.1:1/contracts?connectTimeout=1&socketTimeout=1");
    properties.setUsername("contracts_user");
    properties.setPassword("contracts_password");
    CheckRunStore store = new CheckRunStore(properties);
    store.initialize();

    CheckRunStoreException exception =
        assertThrows(CheckRunStoreException.class, () -> store.list(null, null));
    assertTrue(exception.getMessage().contains("unavailable"));
  }

  @Test
  void constructorFailsWhenSecurePostgresIsEnforcedWithoutStrictSslMode() {
    CheckStoreProperties properties = baseProperties();
    properties.setUrl("jdbc:postgresql://localhost:5432/contracts");
    properties.setEnforceSecurePostgres(true);
    properties.getSsl().setEnabled(true);
    properties.getSsl().setMode("require");

    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> new CheckRunStore(properties));
    assertTrue(exception.getMessage().contains("checks.db.ssl.mode must be one of"));
  }

  @Test
  void initializeFailsFastWhenEnabledAndStoreCannotInitialize() throws Exception {
    Path dbPath = tempDir.resolve("checks-directory-fast-fail");
    Files.createDirectories(dbPath);

    CheckStoreProperties properties = baseProperties();
    properties.setPath(dbPath.toString());
    properties.setFailFastStartup(true);
    CheckRunStore store = new CheckRunStore(properties);

    IllegalStateException exception = assertThrows(IllegalStateException.class, store::initialize);
    assertTrue(exception.getMessage().contains("Failed to initialize check history store"));
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

  private CheckStoreProperties baseProperties() {
    CheckStoreProperties properties = new CheckStoreProperties();
    properties.setPath(tempDir.resolve("default-checks.db").toString());
    properties.getPool().setConnectionTimeout(Duration.ofMillis(250));
    return properties;
  }
}
