package com.ideas.contracts.service;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Assumptions;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class PostgresTestSupport {
  private static final String JDBC_URL_PROPERTY = "test.postgres.jdbc-url";
  private static final String USERNAME_PROPERTY = "test.postgres.username";
  private static final String PASSWORD_PROPERTY = "test.postgres.password";
  private static final String JDBC_URL_ENV = "TEST_POSTGRES_JDBC_URL";
  private static final String USERNAME_ENV = "TEST_POSTGRES_USERNAME";
  private static final String PASSWORD_ENV = "TEST_POSTGRES_PASSWORD";
  private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:5432/postgres";
  private static final String DEFAULT_USERNAME = "postgres";
  private static final String DEFAULT_PASSWORD = "postgres";
  private static final Set<String> CREATED_SCHEMAS = ConcurrentHashMap.newKeySet();
  private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean(false);

  private PostgresTestSupport() {}

  static String randomSchema(String prefix) {
    String normalizedPrefix = prefix == null || prefix.isBlank() ? "checks_it" : prefix;
    String schema = normalizedPrefix + "_" + UUID.randomUUID().toString().replace("-", "");
    registerSchema(schema);
    return schema;
  }

  private static void registerSchema(String schema) {
    if (schema == null || schema.isBlank()) {
      return;
    }
    CREATED_SCHEMAS.add(schema);
    registerShutdownHook();
  }

  private static void registerShutdownHook() {
    if (!SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
      return;
    }
    Runtime.getRuntime().addShutdownHook(new Thread(PostgresTestSupport::cleanupSchemas, "dcg-test-schema-cleanup"));
  }

  private static void cleanupSchemas() {
    if (CREATED_SCHEMAS.isEmpty()) {
      return;
    }
    String jdbcUrl = localJdbcUrl();
    String username = localUsername();
    String password = localPassword();
    if (!canConnect(jdbcUrl, username, password)) {
      return;
    }
    for (String schema : CREATED_SCHEMAS) {
      dropSchemaQuietly(jdbcUrl, username, password, schema);
    }
  }

  static void cleanupSchemasNow() {
    cleanupSchemas();
    CREATED_SCHEMAS.clear();
  }

  static String localJdbcUrl() {
    return firstNonBlank(System.getProperty(JDBC_URL_PROPERTY), System.getenv(JDBC_URL_ENV), DEFAULT_JDBC_URL);
  }

  static String localUsername() {
    return firstNonBlank(System.getProperty(USERNAME_PROPERTY), System.getenv(USERNAME_ENV), DEFAULT_USERNAME);
  }

  static String localPassword() {
    return firstNonBlank(System.getProperty(PASSWORD_PROPERTY), System.getenv(PASSWORD_ENV), DEFAULT_PASSWORD);
  }

  static void assumeLocalPostgresAvailable() {
    String jdbcUrl = localJdbcUrl();
    String username = localUsername();
    String password = localPassword();
    Assumptions.assumeTrue(
        canConnect(jdbcUrl, username, password),
        "Skipping Postgres test. Unable to connect to "
            + jdbcUrl
            + " with configured test credentials. "
            + "Set "
            + JDBC_URL_PROPERTY
            + ", "
            + USERNAME_PROPERTY
            + ", and "
            + PASSWORD_PROPERTY
            + " (or TEST_POSTGRES_* env vars).");
  }

  static String invalidPassword() {
    String configured = localPassword();
    return configured.equals("definitely-wrong-password")
        ? "definitely-wrong-password-x"
        : "definitely-wrong-password";
  }

  static String missingUsername() {
    return "dcg_missing_user_" + UUID.randomUUID().toString().replace("-", "");
  }

  static String withCurrentSchema(String jdbcUrl, String schema) {
    String withoutCurrentSchema = jdbcUrl
        .replaceAll("([?&])currentSchema=[^&]*&", "$1")
        .replaceAll("([?&])currentSchema=[^&]*$", "")
        .replaceAll("[?&]$", "");
    char delimiter = withoutCurrentSchema.contains("?") ? '&' : '?';
    return withoutCurrentSchema
        + delimiter
        + "currentSchema="
        + URLEncoder.encode(schema, StandardCharsets.UTF_8);
  }

  static void createSchema(String adminJdbcUrl, String username, String password, String schema) throws Exception {
    try (Connection connection = DriverManager.getConnection(adminJdbcUrl, username, password);
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA IF NOT EXISTS " + sanitizeIdentifier(schema));
    }
  }

  static void migrateSchema(String jdbcUrl, String username, String password) {
    Flyway.configure()
        .dataSource(jdbcUrl, username, password)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion(MigrationVersion.fromVersion("0"))
        .load()
        .migrate();
  }

  static void insertCheckRun(
      String jdbcUrl,
      String username,
      String password,
      String runId,
      String contractId,
      String status,
      String warnings) throws Exception {
    try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
         PreparedStatement statement = connection.prepareStatement("""
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
      statement.setString(6, "[]");
      statement.setString(7, warnings);
      statement.setString(8, "postgres-test");
      statement.setString(9, "2026-03-01T12:00:00Z");
      statement.executeUpdate();
    }
  }

  static void dropWarningsColumn(String jdbcUrl, String username, String password) throws Exception {
    try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
         Statement statement = connection.createStatement()) {
      statement.execute("ALTER TABLE check_runs DROP COLUMN warnings");
    }
  }

  static void dropSchema(String adminJdbcUrl, String username, String password, String schema) throws Exception {
    if (schema == null || schema.isBlank()) {
      return;
    }
    try (Connection connection = DriverManager.getConnection(adminJdbcUrl, username, password);
         Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + sanitizeIdentifier(schema) + " CASCADE");
    }
  }

  static void dropSchemaQuietly(String adminJdbcUrl, String username, String password, String schema) {
    if (!canConnect(adminJdbcUrl, username, password)) {
      return;
    }
    try {
      dropSchema(adminJdbcUrl, username, password, schema);
    } catch (Exception ex) {
      System.err.println(
          "Warning: failed to drop test schema '" + schema + "': " + ex.getMessage());
    }
  }

  private static String sanitizeIdentifier(String value) {
    if (value == null || !value.matches("[a-zA-Z0-9_]+")) {
      throw new IllegalArgumentException("Invalid schema identifier.");
    }
    return "\"" + value + "\"";
  }

  private static boolean canConnect(String jdbcUrl, String username, String password) {
    try (Connection ignored = DriverManager.getConnection(jdbcUrl, username, password)) {
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static String firstNonBlank(String first, String second, String fallback) {
    if (first != null && !first.isBlank()) {
      return first.trim();
    }
    if (second != null && !second.isBlank()) {
      return second.trim();
    }
    return fallback;
  }
}
