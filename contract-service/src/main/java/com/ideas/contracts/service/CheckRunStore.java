package com.ideas.contracts.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import com.ideas.contracts.service.model.CheckRunResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CheckRunStore {
  private static final String SQLITE_JDBC_PREFIX = "jdbc:sqlite:";
  private static final Set<String> ALLOWED_STRICT_SSL_MODES = Set.of("verify-ca", "verify-full");
  private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
  private static final Logger LOGGER = LoggerFactory.getLogger(CheckRunStore.class);

  public record HealthSnapshot(boolean available, String reason) {}
  public record PoolSnapshot(
      int totalConnections,
      int activeConnections,
      int idleConnections,
      int threadsAwaitingConnection,
      int maximumPoolSize,
      int minimumIdle,
      long connectionTimeoutMs) {}

  private final String jdbcUrl;
  private final Path sqlitePath;
  private final String dbTarget;
  private final HikariDataSource dataSource;
  private final Function<String, String> envLookup;
  private final int queryTimeoutSeconds;
  private final boolean failFastStartup;
  private final ObjectMapper objectMapper;
  private final Object initLock = new Object();
  private volatile boolean initialized;

  @Autowired
  public CheckRunStore(CheckStoreProperties properties) {
    this(properties, System::getenv);
  }

  CheckRunStore(CheckStoreProperties properties, Function<String, String> envLookup) {
    this.envLookup = envLookup == null ? System::getenv : envLookup;
    String trimmedUrl = trimToEmpty(properties.getUrl());
    CheckStoreProperties.Ssl ssl = properties.getSsl();
    validatePostgresSecurityConstraints(trimmedUrl, ssl, properties.isEnforceSecurePostgres());
    if (trimmedUrl.isBlank()) {
      Path resolvedPath = Paths.get(defaultIfBlank(properties.getPath(), "checks.db"));
      this.jdbcUrl = SQLITE_JDBC_PREFIX + resolvedPath;
      this.sqlitePath = resolvedPath;
      this.dbTarget = resolvedPath.toAbsolutePath().toString();
    } else {
      this.jdbcUrl = withPostgresSslOptions(trimmedUrl, ssl);
      this.sqlitePath = resolveSqlitePath(jdbcUrl);
      this.dbTarget = sanitizeJdbcUrl(jdbcUrl);
    }
    validatePoolAndTimeoutSettings(properties);
    String dbUsername = resolveUsername(properties);
    String dbPassword = resolvePassword(properties);
    this.dataSource = createDataSource(jdbcUrl, dbUsername, dbPassword, properties.getPool());
    this.queryTimeoutSeconds = toQueryTimeoutSeconds(properties.getQueryTimeout());
    this.failFastStartup = properties.isFailFastStartup();
    this.objectMapper = new ObjectMapper();
  }

  @PostConstruct
  public void initialize() {
    if (!tryInitialize(true)) {
      if (failFastStartup) {
        throw new IllegalStateException(
            "Failed to initialize check history store for configured database target: " + dbTarget);
      }
      LOGGER.warn(
          "event=check_store_init_deferred component=check_run_store db_target={} message=Will retry on first checks request",
          dbTarget);
    }
  }

  public List<CheckRunResponse> list(String contractId, String commitSha) {
    ensureInitialized();

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
      applyQueryTimeout(statement);
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
      logDbFailure("list_check_runs", e, contractId, commitSha);
      throw new CheckRunStoreException("Failed to query check runs from configured database.", e);
    }
  }

  private void ensureInitialized() {
    if (initialized || tryInitialize(true)) {
      return;
    }
    throw new CheckRunStoreException("Check history store is currently unavailable.");
  }

  public String configuredDbTarget() {
    return dbTarget;
  }

  public PoolSnapshot poolSnapshot() {
    HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
    HikariConfigMXBean config = dataSource.getHikariConfigMXBean();
    if (pool == null || config == null) {
      return new PoolSnapshot(-1, -1, -1, -1, -1, -1, -1);
    }
    return new PoolSnapshot(
        pool.getTotalConnections(),
        pool.getActiveConnections(),
        pool.getIdleConnections(),
        pool.getThreadsAwaitingConnection(),
        config.getMaximumPoolSize(),
        config.getMinimumIdle(),
        config.getConnectionTimeout());
  }

  @PreDestroy
  public void shutdown() {
    dataSource.close();
  }

  public HealthSnapshot healthSnapshot() {
    if (!(initialized || tryInitialize(false))) {
      return new HealthSnapshot(false, "initialization_failed");
    }
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
      applyQueryTimeout(statement);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next()
            ? new HealthSnapshot(true, "ok")
            : new HealthSnapshot(false, "health_query_failed");
      }
    } catch (SQLException e) {
      return new HealthSnapshot(false, safeValue(e.getMessage()));
    }
  }

  private boolean tryInitialize(boolean logFailure) {
    synchronized (initLock) {
      if (initialized) {
        return true;
      }
      try {
        if (sqlitePath != null) {
          Path parent = sqlitePath.toAbsolutePath().getParent();
          if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
          }
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
        initialized = true;
        LOGGER.info(
            "event=check_store_initialized component=check_run_store db_target={} backend={}",
            dbTarget,
            backendFromJdbcUrl(jdbcUrl));
        return true;
      } catch (Exception e) {
        if (logFailure) {
          logDbFailure("initialize_check_store", e, null, null);
        }
        return false;
      }
    }
  }

  private Connection openConnection() throws SQLException {
    return dataSource.getConnection();
  }

  private void logDbFailure(String operation, Exception error, String contractId, String commitSha) {
    String sqlState = "-";
    String sqlVendorCode = "-";
    if (error instanceof SQLException sqlError) {
      sqlState = safeValue(sqlError.getSQLState());
      sqlVendorCode = Integer.toString(sqlError.getErrorCode());
    }

    LOGGER.error(
        "event=db_operation_failed component=check_run_store operation={} db_target={} contract_id={} commit_sha={} sql_state={} sql_vendor_code={} error_type={} error_message={}",
        operation,
        dbTarget,
        safeValue(contractId),
        safeValue(commitSha),
        sqlState,
        sqlVendorCode,
        error.getClass().getSimpleName(),
        safeValue(error.getMessage()));
  }

  private String safeValue(String value) {
    return value == null || value.isBlank() ? "-" : value;
  }

  private String normalizeCredential(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private void applyQueryTimeout(PreparedStatement statement) throws SQLException {
    statement.setQueryTimeout(queryTimeoutSeconds);
  }

  private String resolveUsername(CheckStoreProperties properties) {
    String configuredUsername = normalizeCredential(properties.getUsername());
    if (configuredUsername != null) {
      return configuredUsername;
    }
    return normalizeCredential(resolveSecretFromEnv(properties.getUsernameEnv()));
  }

  private String resolvePassword(CheckStoreProperties properties) {
    String configuredPassword = trimToEmpty(properties.getPassword());
    if (!configuredPassword.isBlank()) {
      return configuredPassword;
    }
    String fromEnv = resolveSecretFromEnv(properties.getPasswordEnv());
    return fromEnv == null ? "" : fromEnv;
  }

  private String resolveSecretFromEnv(String envVarName) {
    String normalizedEnvVarName = normalizeCredential(envVarName);
    if (normalizedEnvVarName == null) {
      return null;
    }
    String envValue = envLookup.apply(normalizedEnvVarName);
    if (envValue == null || envValue.isBlank()) {
      throw new IllegalStateException(
          "Environment variable '" + normalizedEnvVarName + "' configured for check store credentials is not set or blank.");
    }
    return envValue.trim();
  }

  private void validatePoolAndTimeoutSettings(CheckStoreProperties properties) {
    CheckStoreProperties.Pool pool = properties.getPool();
    if (pool == null) {
      return;
    }

    if (pool.getMaximumSize() < 1) {
      throw new IllegalStateException("checks.db.pool.maximum-size must be greater than 0.");
    }
    if (pool.getMinimumIdle() < 0 || pool.getMinimumIdle() > pool.getMaximumSize()) {
      throw new IllegalStateException(
          "checks.db.pool.minimum-idle must be between 0 and checks.db.pool.maximum-size.");
    }
    requirePositiveDuration("checks.db.query-timeout", properties.getQueryTimeout());
    requirePositiveDuration("checks.db.pool.connection-timeout", pool.getConnectionTimeout());
    requirePositiveDuration("checks.db.pool.idle-timeout", pool.getIdleTimeout());
    requirePositiveDuration("checks.db.pool.max-lifetime", pool.getMaxLifetime());
    requirePositiveDuration("checks.db.pool.validation-timeout", pool.getValidationTimeout());

    Duration initializationFailTimeout = pool.getInitializationFailTimeout();
    if (initializationFailTimeout != null && initializationFailTimeout.toMillis() == 0) {
      throw new IllegalStateException(
          "checks.db.pool.initialization-fail-timeout must be negative (disable) or greater than 0ms.");
    }
  }

  private void validatePostgresSecurityConstraints(
      String configuredDbUrl,
      CheckStoreProperties.Ssl sslProperties,
      boolean enforceSecurePostgres) {
    if (!enforceSecurePostgres || !isPostgresUrl(configuredDbUrl)) {
      return;
    }
    if (sslProperties == null || !sslProperties.isEnabled()) {
      throw new IllegalStateException(
          "checks.db.ssl.enabled must be true when checks.db.enforce-secure-postgres=true.");
    }
    String sslMode = normalizeSslMode(sslProperties.getMode());
    if (!ALLOWED_STRICT_SSL_MODES.contains(sslMode)) {
      throw new IllegalStateException(
          "checks.db.ssl.mode must be one of "
              + ALLOWED_STRICT_SSL_MODES
              + " when checks.db.enforce-secure-postgres=true.");
    }
  }

  private void requirePositiveDuration(String propertyName, Duration value) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalStateException(propertyName + " must be greater than 0.");
    }
  }

  private boolean isPostgresUrl(String value) {
    return value != null && value.startsWith("jdbc:postgresql:");
  }

  private String normalizeSslMode(String value) {
    String normalized = normalizeCredential(value);
    return normalized == null ? "" : normalized.toLowerCase(Locale.ROOT);
  }

  private HikariDataSource createDataSource(
      String jdbcUrl,
      String username,
      String password,
      CheckStoreProperties.Pool poolProperties) {
    CheckStoreProperties.Pool pool = poolProperties == null ? new CheckStoreProperties.Pool() : poolProperties;
    int maxPoolSize = pool.getMaximumSize();
    int minIdle = pool.getMinimumIdle();

    HikariConfig config = new HikariConfig();
    config.setPoolName("check-run-store-pool");
    config.setJdbcUrl(jdbcUrl);
    if (username != null) {
      config.setUsername(username);
      config.setPassword(password);
    }
    config.setMaximumPoolSize(maxPoolSize);
    config.setMinimumIdle(minIdle);
    config.setConnectionTimeout(toPositiveMillis(pool.getConnectionTimeout(), Duration.ofSeconds(1), 250));
    config.setIdleTimeout(toPositiveMillis(pool.getIdleTimeout(), Duration.ofMinutes(2), 1000));
    config.setMaxLifetime(toPositiveMillis(pool.getMaxLifetime(), Duration.ofMinutes(30), 30000));
    config.setValidationTimeout(toPositiveMillis(pool.getValidationTimeout(), Duration.ofSeconds(3), 250));
    config.setInitializationFailTimeout(toInitializationFailTimeoutMillis(pool.getInitializationFailTimeout()));
    config.setAutoCommit(true);
    return new HikariDataSource(config);
  }

  private int toQueryTimeoutSeconds(Duration timeout) {
    Duration normalized = timeout == null ? Duration.ofSeconds(5) : timeout;
    if (normalized.isNegative() || normalized.isZero()) {
      return 5;
    }
    long seconds = normalized.toSeconds();
    if (seconds <= 0) {
      return 1;
    }
    if (seconds > Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    return (int) seconds;
  }

  private long toPositiveMillis(Duration value, Duration fallback, long minimum) {
    Duration normalized = value == null ? fallback : value;
    long millis = normalized.toMillis();
    if (millis <= 0) {
      millis = fallback.toMillis();
    }
    return Math.max(minimum, millis);
  }

  private long toInitializationFailTimeoutMillis(Duration value) {
    Duration normalized = value == null ? Duration.ofMillis(-1) : value;
    long millis = normalized.toMillis();
    if (millis < 0) {
      return -1;
    }
    return Math.max(1, millis);
  }

  private String withPostgresSslOptions(String url, CheckStoreProperties.Ssl sslProperties) {
    if (sslProperties == null || !sslProperties.isEnabled() || !url.startsWith("jdbc:postgresql:")) {
      return url;
    }

    String withMode = appendQueryParamIfMissing(url, "sslmode", defaultIfBlank(sslProperties.getMode(), "require"));
    String withRootCert = appendQueryParamIfMissing(withMode, "sslrootcert", sslProperties.getRootCertPath());
    String withClientCert = appendQueryParamIfMissing(withRootCert, "sslcert", sslProperties.getCertPath());
    return appendQueryParamIfMissing(withClientCert, "sslkey", sslProperties.getKeyPath());
  }

  private String appendQueryParamIfMissing(String url, String key, String value) {
    if (value == null || value.isBlank() || containsQueryParam(url, key)) {
      return url;
    }
    String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
    char delimiter = url.contains("?") ? '&' : '?';
    return url + delimiter + key + "=" + encoded;
  }

  private boolean containsQueryParam(String url, String key) {
    return Pattern.compile("(?i)([?&])" + Pattern.quote(key) + "=").matcher(url).find();
  }

  private String trimToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  private String defaultIfBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private Path resolveSqlitePath(String value) {
    if (!value.startsWith(SQLITE_JDBC_PREFIX)) {
      return null;
    }

    String raw = value.substring(SQLITE_JDBC_PREFIX.length());
    int queryStart = raw.indexOf('?');
    if (queryStart >= 0) {
      raw = raw.substring(0, queryStart);
    }
    if (raw.isBlank() || raw.equals(":memory:") || raw.startsWith("file::memory:")) {
      return null;
    }
    if (raw.startsWith("file:")) {
      raw = raw.substring("file:".length());
    }

    try {
      return Paths.get(raw);
    } catch (InvalidPathException ignored) {
      return null;
    }
  }

  private String backendFromJdbcUrl(String value) {
    if (value.startsWith("jdbc:postgresql:")) {
      return "postgresql";
    }
    if (value.startsWith(SQLITE_JDBC_PREFIX)) {
      return "sqlite";
    }
    return "jdbc";
  }

  private String sanitizeJdbcUrl(String value) {
    String sanitized = value.replaceAll("(?i)(password=)[^&;]+", "$1****");

    int schemeStart = sanitized.indexOf("://");
    if (schemeStart < 0) {
      return sanitized;
    }
    int credentialsStart = schemeStart + 3;
    int credentialsEnd = sanitized.indexOf('@', credentialsStart);
    if (credentialsEnd < 0) {
      return sanitized;
    }
    return sanitized.substring(0, credentialsStart) + "***:***" + sanitized.substring(credentialsEnd);
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
