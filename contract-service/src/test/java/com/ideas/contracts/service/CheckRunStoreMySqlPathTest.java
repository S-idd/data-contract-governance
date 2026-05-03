package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ideas.contracts.service.model.CheckRunResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MySqlDatabaseCleanupExtension.class)
class CheckRunStoreMySqlPathTest {
  private static final String BASE_JDBC_URL = MySqlTestSupport.localJdbcUrl();
  private static final String USERNAME = MySqlTestSupport.localUsername();
  private static final String PASSWORD = MySqlTestSupport.localPassword();

  @Test
  void mySqlPathSuccessReturnsStoredRows() throws Exception {
    MySqlTestSupport.assumeLocalMySqlAvailable();
    String database = MySqlTestSupport.randomDatabase("unit_success");
    String jdbcUrl = MySqlTestSupport.withDatabase(BASE_JDBC_URL, database);
    createDatabaseOrSkip(database);

    CheckRunStore store = new CheckRunStore(baseProperties(jdbcUrl, USERNAME, PASSWORD));
    try {
      store.initialize();
      MySqlTestSupport.insertCheckRun(
          jdbcUrl,
          USERNAME,
          PASSWORD,
          "run-1",
          "orders.created",
          "PASS",
          "[\"Enum value added: status.SHIPPED\"]");

      List<CheckRunResponse> rows = store.list("orders.created", null);

      assertEquals(1, rows.size());
      assertEquals("run-1", rows.get(0).runId());
      assertEquals(List.of("Enum value added: status.SHIPPED"), rows.get(0).warnings());
    } finally {
      store.shutdown();
      MySqlTestSupport.dropDatabaseQuietly(BASE_JDBC_URL, USERNAME, PASSWORD, database);
    }
  }

  @Test
  void mySqlPathAuthFailureReturnsUnavailableStoreException() {
    MySqlTestSupport.assumeLocalMySqlAvailable();
    String database = MySqlTestSupport.randomDatabase("unit_auth_failure");
    String jdbcUrl = MySqlTestSupport.withDatabase(BASE_JDBC_URL, database);
    createDatabaseOrSkip(database);
    String missingUser = MySqlTestSupport.missingUsername();

    CheckRunStore store = new CheckRunStore(baseProperties(jdbcUrl, missingUser, MySqlTestSupport.invalidPassword()));
    try {
      store.initialize();
      CheckRunStoreException exception =
          assertThrows(CheckRunStoreException.class, () -> store.list(null, null));
      assertTrue(exception.getMessage().contains("currently unavailable"));
    } finally {
      store.shutdown();
      MySqlTestSupport.dropDatabaseQuietly(BASE_JDBC_URL, USERNAME, PASSWORD, database);
    }
  }

  @Test
  void mySqlPathNetworkFailureReturnsUnavailableStoreException() {
    String jdbcUrl =
        "jdbc:mysql://127.0.0.1:1/contracts?connectTimeout=1000&socketTimeout=1000&useSSL=false";

    CheckRunStore store = new CheckRunStore(baseProperties(jdbcUrl, "contracts_user", "contracts_password"));
    try {
      store.initialize();
      CheckRunStoreException exception =
          assertThrows(CheckRunStoreException.class, () -> store.list(null, null));
      assertTrue(exception.getMessage().contains("currently unavailable"));
    } finally {
      store.shutdown();
    }
  }

  @Test
  void mySqlPathSchemaMismatchThrowsQueryFailure() throws Exception {
    MySqlTestSupport.assumeLocalMySqlAvailable();
    String database = MySqlTestSupport.randomDatabase("unit_schema_mismatch");
    String jdbcUrl = MySqlTestSupport.withDatabase(BASE_JDBC_URL, database);
    createDatabaseOrSkip(database);
    MySqlTestSupport.migrateDatabase(jdbcUrl, USERNAME, PASSWORD);
    MySqlTestSupport.dropWarningsColumn(jdbcUrl, USERNAME, PASSWORD);

    CheckRunStore store = new CheckRunStore(baseProperties(jdbcUrl, USERNAME, PASSWORD));
    try {
      store.initialize();
      CheckRunStoreException exception =
          assertThrows(CheckRunStoreException.class, () -> store.list("orders.created", null));
      assertTrue(exception.getMessage().contains("Failed to query check runs"));
    } finally {
      store.shutdown();
      MySqlTestSupport.dropDatabaseQuietly(BASE_JDBC_URL, USERNAME, PASSWORD, database);
    }
  }

  private CheckStoreProperties baseProperties(String jdbcUrl, String username, String password) {
    CheckStoreProperties properties = new CheckStoreProperties();
    properties.setUrl(jdbcUrl);
    properties.setUsername(username);
    properties.setPassword(password);
    properties.setPath("checks.db");
    properties.setQueryTimeout(Duration.ofSeconds(1));
    properties.getPool().setConnectionTimeout(Duration.ofMillis(250));
    properties.getPool().setInitializationFailTimeout(Duration.ofMillis(-1));
    return properties;
  }

  private void createDatabaseOrSkip(String database) {
    try {
      MySqlTestSupport.createDatabase(BASE_JDBC_URL, USERNAME, PASSWORD, database);
    } catch (Exception provisionError) {
      Assumptions.assumeTrue(
          false,
          "Skipping MySQL path test: unable to create test database. " + provisionError.getMessage());
    }
  }
}
