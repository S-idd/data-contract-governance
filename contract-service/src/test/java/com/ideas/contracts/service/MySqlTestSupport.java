package com.ideas.contracts.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Assumptions;

final class MySqlTestSupport {
  private static final String JDBC_URL_PROPERTY = "test.mysql.jdbc-url";
  private static final String USERNAME_PROPERTY = "test.mysql.username";
  private static final String PASSWORD_PROPERTY = "test.mysql.password";
  private static final String JDBC_URL_ENV = "TEST_MYSQL_JDBC_URL";
  private static final String USERNAME_ENV = "TEST_MYSQL_USERNAME";
  private static final String PASSWORD_ENV = "TEST_MYSQL_PASSWORD";
  private static final String DEFAULT_JDBC_URL =
      "jdbc:mysql://localhost:3306/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
  private static final String DEFAULT_USERNAME = "root";
  private static final String DEFAULT_PASSWORD = "";
  private static final Set<String> CREATED_DATABASES = ConcurrentHashMap.newKeySet();
  private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean(false);

  private MySqlTestSupport() {}

  static String randomDatabase(String prefix) {
    String normalizedPrefix = prefix == null || prefix.isBlank() ? "checks_it" : prefix;
    String database = normalizedPrefix + "_" + UUID.randomUUID().toString().replace("-", "");
    registerDatabase(database);
    return database;
  }

  private static void registerDatabase(String database) {
    if (database == null || database.isBlank()) {
      return;
    }
    CREATED_DATABASES.add(database);
    registerShutdownHook();
  }

  private static void registerShutdownHook() {
    if (!SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
      return;
    }
    Runtime.getRuntime().addShutdownHook(new Thread(MySqlTestSupport::cleanupDatabases, "dcg-mysql-test-db-cleanup"));
  }

  private static void cleanupDatabases() {
    if (CREATED_DATABASES.isEmpty()) {
      return;
    }
    String jdbcUrl = localJdbcUrl();
    String username = localUsername();
    String password = localPassword();
    if (!canConnect(jdbcUrl, username, password)) {
      return;
    }
    for (String database : CREATED_DATABASES) {
      dropDatabaseQuietly(jdbcUrl, username, password, database);
    }
  }

  static void cleanupDatabasesNow() {
    cleanupDatabases();
    CREATED_DATABASES.clear();
  }

  static String localJdbcUrl() {
    return firstNonBlank(System.getProperty(JDBC_URL_PROPERTY), System.getenv(JDBC_URL_ENV), DEFAULT_JDBC_URL);
  }

  static String localUsername() {
    return firstNonBlank(System.getProperty(USERNAME_PROPERTY), System.getenv(USERNAME_ENV), DEFAULT_USERNAME);
  }

  static String localPassword() {
    String fromProperty = System.getProperty(PASSWORD_PROPERTY);
    if (fromProperty != null) {
      return fromProperty.trim();
    }
    String fromEnv = System.getenv(PASSWORD_ENV);
    if (fromEnv != null) {
      return fromEnv.trim();
    }
    return DEFAULT_PASSWORD;
  }

  static void assumeLocalMySqlAvailable() {
    String jdbcUrl = localJdbcUrl();
    String username = localUsername();
    String password = localPassword();
    Assumptions.assumeTrue(
        canConnect(jdbcUrl, username, password),
        "Skipping MySQL test. Unable to connect to "
            + jdbcUrl
            + " with configured test credentials. "
            + "Set "
            + JDBC_URL_PROPERTY
            + ", "
            + USERNAME_PROPERTY
            + ", and "
            + PASSWORD_PROPERTY
            + " (or TEST_MYSQL_* env vars).");

    Version version = localServerVersion(jdbcUrl, username, password);
    Assumptions.assumeTrue(
        version != null && isFlywayVerifiedVersion(version),
        "Skipping MySQL test. Local MySQL "
            + (version == null ? "unknown" : version.toString())
            + " is outside Flyway 10.10 verified range for this project. "
            + "Use MySQL 8.0/8.4 (or <=9.4) for Week 9 verification.");
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

  static String withDatabase(String jdbcUrl, String database) {
    if (jdbcUrl == null || jdbcUrl.isBlank()) {
      throw new IllegalArgumentException("jdbcUrl must not be blank.");
    }
    if (database == null || database.isBlank()) {
      throw new IllegalArgumentException("database must not be blank.");
    }

    int schemeIndex = jdbcUrl.indexOf("://");
    if (schemeIndex < 0) {
      throw new IllegalArgumentException("Invalid MySQL JDBC URL.");
    }
    int hostStart = schemeIndex + 3;
    int pathStart = jdbcUrl.indexOf('/', hostStart);
    String encodedDatabase = URLEncoder.encode(database, StandardCharsets.UTF_8);
    if (pathStart < 0) {
      return jdbcUrl + "/" + encodedDatabase;
    }
    int queryStart = jdbcUrl.indexOf('?', pathStart);
    String prefix = jdbcUrl.substring(0, pathStart + 1);
    String suffix = queryStart >= 0 ? jdbcUrl.substring(queryStart) : "";
    return prefix + encodedDatabase + suffix;
  }

  static void createDatabase(String adminJdbcUrl, String username, String password, String database) throws Exception {
    try (Connection connection = DriverManager.getConnection(adminJdbcUrl, username, password);
         Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE DATABASE IF NOT EXISTS "
              + sanitizeIdentifier(database)
              + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    }
  }

  static void migrateDatabase(String jdbcUrl, String username, String password) {
    Flyway.configure()
        .dataSource(jdbcUrl, username, password)
        .locations("classpath:db/migration-mysql")
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
      statement.setString(8, "mysql-test");
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

  static void dropDatabase(String adminJdbcUrl, String username, String password, String database) throws Exception {
    if (database == null || database.isBlank()) {
      return;
    }
    try (Connection connection = DriverManager.getConnection(adminJdbcUrl, username, password);
         Statement statement = connection.createStatement()) {
      statement.execute("DROP DATABASE IF EXISTS " + sanitizeIdentifier(database));
    }
  }

  static void dropDatabaseQuietly(String adminJdbcUrl, String username, String password, String database) {
    if (!canConnect(adminJdbcUrl, username, password)) {
      return;
    }
    try {
      dropDatabase(adminJdbcUrl, username, password, database);
    } catch (Exception ex) {
      System.err.println("Warning: failed to drop test database '" + database + "': " + ex.getMessage());
    }
  }

  private static String sanitizeIdentifier(String value) {
    if (value == null || !value.matches("[a-zA-Z0-9_]+")) {
      throw new IllegalArgumentException("Invalid database identifier.");
    }
    return "`" + value + "`";
  }

  private static boolean canConnect(String jdbcUrl, String username, String password) {
    try (Connection ignored = DriverManager.getConnection(jdbcUrl, username, password)) {
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static Version localServerVersion(String jdbcUrl, String username, String password) {
    try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
      DatabaseMetaData metaData = connection.getMetaData();
      return new Version(metaData.getDatabaseMajorVersion(), metaData.getDatabaseMinorVersion());
    } catch (Exception ignored) {
      return null;
    }
  }

  private static boolean isFlywayVerifiedVersion(Version version) {
    if (version.major() < 9) {
      return true;
    }
    if (version.major() > 9) {
      return false;
    }
    return version.minor() <= 4;
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

  private record Version(int major, int minor) {
    @Override
    public String toString() {
      return major + "." + minor;
    }
  }
}
