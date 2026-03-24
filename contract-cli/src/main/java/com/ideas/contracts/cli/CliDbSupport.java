package com.ideas.contracts.cli;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

final class CliDbSupport {
  private static final String SQLITE_JDBC_PREFIX = "jdbc:sqlite:";

  private CliDbSupport() {}

  static String resolveJdbcUrl(Path dbPath, String jdbcUrl) {
    boolean hasPath = dbPath != null;
    boolean hasJdbc = jdbcUrl != null && !jdbcUrl.isBlank();
    if (hasPath && hasJdbc) {
      throw new IllegalArgumentException("Use either --db or --jdbc-url, not both.");
    }
    if (hasJdbc) {
      return jdbcUrl.trim();
    }
    Path effectivePath = hasPath ? dbPath : Path.of("checks.db");
    return SQLITE_JDBC_PREFIX + effectivePath.toAbsolutePath();
  }

  static Connection openConnection(
      Path dbPath,
      String jdbcUrl,
      String username,
      String password) throws SQLException {
    String resolvedJdbcUrl = resolveJdbcUrl(dbPath, jdbcUrl);
    if (username == null || username.isBlank()) {
      return DriverManager.getConnection(resolvedJdbcUrl);
    }
    Properties properties = new Properties();
    properties.setProperty("user", username.trim());
    properties.setProperty("password", password == null ? "" : password);
    return DriverManager.getConnection(resolvedJdbcUrl, properties);
  }
}
