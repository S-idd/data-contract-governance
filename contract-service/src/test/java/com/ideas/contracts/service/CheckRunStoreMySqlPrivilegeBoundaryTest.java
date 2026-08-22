package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ideas.contracts.service.model.CheckRunCreateRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MySqlDatabaseCleanupExtension.class)
class CheckRunStoreMySqlPrivilegeBoundaryTest {
  private static final String BASE_JDBC_URL = MySqlTestSupport.localJdbcUrl();
  private static final String ADMIN_USERNAME = MySqlTestSupport.localUsername();
  private static final String ADMIN_PASSWORD = MySqlTestSupport.localPassword();

  @Test
  void migrationIdentityCanMigrateWhileRuntimeIdentityOnlyWritesApplicationData() throws Exception {
    MySqlTestSupport.assumeLocalMySqlAvailable();
    String database = MySqlTestSupport.randomDatabase("unit_privilege_boundary");
    String jdbcUrl = MySqlTestSupport.withDatabase(BASE_JDBC_URL, database);
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    String runtimeUser = "dcg_runtime_" + suffix;
    String migrationUser = "dcg_migrator_" + suffix;
    String runtimePassword = "runtime-test-password";
    String migrationPassword = "migration-test-password";
    MySqlTestSupport.createDatabase(BASE_JDBC_URL, ADMIN_USERNAME, ADMIN_PASSWORD, database);
    createAccounts(database, runtimeUser, runtimePassword, migrationUser, migrationPassword);

    CheckRunStore store = newStore(jdbcUrl, runtimeUser, runtimePassword, migrationUser, migrationPassword);
    try {
      store.initialize();
      String runId = store.createQueuedRun(new CheckRunCreateRequest(
          "orders.created", "v1", "v2", "BACKWARD", "privilege-test", "test")).runId();
      assertEquals("orders.created", store.findByRunId(runId).orElseThrow().contractId());
    } finally {
      store.shutdown();
      dropAccounts(runtimeUser, migrationUser);
      MySqlTestSupport.dropDatabaseQuietly(BASE_JDBC_URL, ADMIN_USERNAME, ADMIN_PASSWORD, database);
    }
  }

  private CheckRunStore newStore(
      String jdbcUrl,
      String runtimeUser,
      String runtimePassword,
      String migrationUser,
      String migrationPassword) {
    CheckStoreProperties properties = new CheckStoreProperties();
    properties.setUrl(jdbcUrl);
    properties.setUsername(runtimeUser);
    properties.setPassword(runtimePassword);
    properties.setMigrationUsername(migrationUser);
    properties.setMigrationPassword(migrationPassword);
    properties.setEnforceSeparateMigrationCredentials(true);
    properties.setQueryTimeout(Duration.ofSeconds(3));
    properties.getPool().setConnectionTimeout(Duration.ofSeconds(2));
    properties.getPool().setInitializationFailTimeout(Duration.ofMillis(-1));
    return new CheckRunStore(properties);
  }

  private void createAccounts(
      String database,
      String runtimeUser,
      String runtimePassword,
      String migrationUser,
      String migrationPassword) throws Exception {
    try (Connection connection = DriverManager.getConnection(BASE_JDBC_URL, ADMIN_USERNAME, ADMIN_PASSWORD);
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE USER '" + runtimeUser + "'@'%' IDENTIFIED BY '" + runtimePassword + "'");
      statement.execute("CREATE USER '" + migrationUser + "'@'%' IDENTIFIED BY '" + migrationPassword + "'");
      statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON `" + database + "`.* TO '" + runtimeUser + "'@'%'");
      statement.execute(
          "GRANT ALTER, CREATE, DELETE, DROP, INDEX, INSERT, REFERENCES, SELECT, TRIGGER, UPDATE "
              + "ON `" + database + "`.* TO '" + migrationUser + "'@'%'");
    }
  }

  private void dropAccounts(String runtimeUser, String migrationUser) throws Exception {
    try (Connection connection = DriverManager.getConnection(BASE_JDBC_URL, ADMIN_USERNAME, ADMIN_PASSWORD);
         Statement statement = connection.createStatement()) {
      statement.execute("DROP USER IF EXISTS '" + runtimeUser + "'@'%'");
      statement.execute("DROP USER IF EXISTS '" + migrationUser + "'@'%'");
    }
  }
}
