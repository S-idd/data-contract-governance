package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ideas.contracts.service.model.CheckRunResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CheckRunStorePostgresPathTest {
  private static final String BASE_JDBC_URL = PostgresTestSupport.localJdbcUrl();
  private static final String USERNAME = PostgresTestSupport.localUsername();
  private static final String PASSWORD = PostgresTestSupport.localPassword();

  @Test
  void postgresPathSuccessReturnsStoredRows() throws Exception {
    PostgresTestSupport.assumeLocalPostgresAvailable();
    String schema = PostgresTestSupport.randomSchema("unit_success");
    String jdbcUrl = PostgresTestSupport.withCurrentSchema(BASE_JDBC_URL, schema);
    PostgresTestSupport.createSchema(BASE_JDBC_URL, USERNAME, PASSWORD, schema);

    CheckRunStore store = new CheckRunStore(baseProperties(jdbcUrl, USERNAME, PASSWORD));
    try {
      store.initialize();
      PostgresTestSupport.insertCheckRun(
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
    }
  }

  @Test
  void postgresPathAuthFailureReturnsUnavailableStoreException() {
    PostgresTestSupport.assumeLocalPostgresAvailable();
    String schema = PostgresTestSupport.randomSchema("unit_auth_failure");
    String jdbcUrl = PostgresTestSupport.withCurrentSchema(BASE_JDBC_URL, schema);
    try {
      PostgresTestSupport.createSchema(BASE_JDBC_URL, USERNAME, PASSWORD, schema);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to prepare schema for auth-failure test.", e);
    }
    String missingUser = PostgresTestSupport.missingUsername();

    CheckRunStore store = new CheckRunStore(baseProperties(jdbcUrl, missingUser, PostgresTestSupport.invalidPassword()));
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
  void postgresPathNetworkFailureReturnsUnavailableStoreException() {
    String jdbcUrl = "jdbc:postgresql://127.0.0.1:1/contracts?connectTimeout=1&socketTimeout=1";

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
  void postgresPathSchemaMismatchThrowsQueryFailure() throws Exception {
    PostgresTestSupport.assumeLocalPostgresAvailable();
    String schema = PostgresTestSupport.randomSchema("unit_schema_mismatch");
    String jdbcUrl = PostgresTestSupport.withCurrentSchema(BASE_JDBC_URL, schema);
    PostgresTestSupport.createSchema(BASE_JDBC_URL, USERNAME, PASSWORD, schema);
    PostgresTestSupport.migrateSchema(jdbcUrl, USERNAME, PASSWORD);
    PostgresTestSupport.dropWarningsColumn(jdbcUrl, USERNAME, PASSWORD);

    CheckRunStore store = new CheckRunStore(baseProperties(jdbcUrl, USERNAME, PASSWORD));
    try {
      store.initialize();
      CheckRunStoreException exception =
          assertThrows(CheckRunStoreException.class, () -> store.list("orders.created", null));
      assertTrue(exception.getMessage().contains("Failed to query check runs"));
    } finally {
      store.shutdown();
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
}
