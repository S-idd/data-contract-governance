package com.ideas.contracts.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "checks.db")
public class CheckStoreProperties {
  private String url = "";
  private String path = "checks.db";
  private String username = "";
  private String password = "";
  private String usernameEnv = "";
  private String passwordEnv = "";
  private String migrationUsername = "";
  private String migrationPassword = "";
  private String migrationUsernameEnv = "";
  private String migrationPasswordEnv = "";
  private String expectedSchema = "";
  private boolean failFastStartup;
  private boolean enforceSecurePostgres;
  private boolean enforceSecureMysql;
  private boolean enforceSeparateMigrationCredentials;
  private Duration queryTimeout = Duration.ofSeconds(5);
  private final Pool pool = new Pool();
  private final Ssl ssl = new Ssl();
  private final Mysql mysql = new Mysql();
  private final Sqlite sqlite = new Sqlite();

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getUsernameEnv() {
    return usernameEnv;
  }

  public void setUsernameEnv(String usernameEnv) {
    this.usernameEnv = usernameEnv;
  }

  public String getPasswordEnv() {
    return passwordEnv;
  }

  public void setPasswordEnv(String passwordEnv) {
    this.passwordEnv = passwordEnv;
  }

  public String getMigrationUsername() {
    return migrationUsername;
  }

  public void setMigrationUsername(String migrationUsername) {
    this.migrationUsername = migrationUsername;
  }

  public String getMigrationPassword() {
    return migrationPassword;
  }

  public void setMigrationPassword(String migrationPassword) {
    this.migrationPassword = migrationPassword;
  }

  public String getMigrationUsernameEnv() {
    return migrationUsernameEnv;
  }

  public void setMigrationUsernameEnv(String migrationUsernameEnv) {
    this.migrationUsernameEnv = migrationUsernameEnv;
  }

  public String getMigrationPasswordEnv() {
    return migrationPasswordEnv;
  }

  public void setMigrationPasswordEnv(String migrationPasswordEnv) {
    this.migrationPasswordEnv = migrationPasswordEnv;
  }

  public String getExpectedSchema() {
    return expectedSchema;
  }

  public void setExpectedSchema(String expectedSchema) {
    this.expectedSchema = expectedSchema;
  }

  public boolean isFailFastStartup() {
    return failFastStartup;
  }

  public void setFailFastStartup(boolean failFastStartup) {
    this.failFastStartup = failFastStartup;
  }

  public boolean isEnforceSecurePostgres() {
    return enforceSecurePostgres;
  }

  public void setEnforceSecurePostgres(boolean enforceSecurePostgres) {
    this.enforceSecurePostgres = enforceSecurePostgres;
  }

  public boolean isEnforceSecureMysql() {
    return enforceSecureMysql;
  }

  public void setEnforceSecureMysql(boolean enforceSecureMysql) {
    this.enforceSecureMysql = enforceSecureMysql;
  }

  public boolean isEnforceSeparateMigrationCredentials() {
    return enforceSeparateMigrationCredentials;
  }

  public void setEnforceSeparateMigrationCredentials(boolean enforceSeparateMigrationCredentials) {
    this.enforceSeparateMigrationCredentials = enforceSeparateMigrationCredentials;
  }

  public Duration getQueryTimeout() {
    return queryTimeout;
  }

  public void setQueryTimeout(Duration queryTimeout) {
    this.queryTimeout = queryTimeout;
  }

  public Pool getPool() {
    return pool;
  }

  public Ssl getSsl() {
    return ssl;
  }

  public Mysql getMysql() {
    return mysql;
  }

  public Sqlite getSqlite() {
    return sqlite;
  }

  public static class Pool {
    private int minimumIdle = 1;
    private int maximumSize = 10;
    private Duration connectionTimeout = Duration.ofSeconds(1);
    private Duration idleTimeout = Duration.ofMinutes(2);
    private Duration maxLifetime = Duration.ofMinutes(30);
    private Duration validationTimeout = Duration.ofSeconds(3);
    private Duration initializationFailTimeout = Duration.ofMillis(-1);
    private int replicaCount = 1;
    private int databaseConnectionBudget;

    public int getMinimumIdle() {
      return minimumIdle;
    }

    public void setMinimumIdle(int minimumIdle) {
      this.minimumIdle = minimumIdle;
    }

    public int getMaximumSize() {
      return maximumSize;
    }

    public void setMaximumSize(int maximumSize) {
      this.maximumSize = maximumSize;
    }

    public Duration getConnectionTimeout() {
      return connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout) {
      this.connectionTimeout = connectionTimeout;
    }

    public Duration getIdleTimeout() {
      return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
      this.idleTimeout = idleTimeout;
    }

    public Duration getMaxLifetime() {
      return maxLifetime;
    }

    public void setMaxLifetime(Duration maxLifetime) {
      this.maxLifetime = maxLifetime;
    }

    public Duration getValidationTimeout() {
      return validationTimeout;
    }

    public void setValidationTimeout(Duration validationTimeout) {
      this.validationTimeout = validationTimeout;
    }

    public Duration getInitializationFailTimeout() {
      return initializationFailTimeout;
    }

    public void setInitializationFailTimeout(Duration initializationFailTimeout) {
      this.initializationFailTimeout = initializationFailTimeout;
    }

    public int getReplicaCount() {
      return replicaCount;
    }

    public void setReplicaCount(int replicaCount) {
      this.replicaCount = replicaCount;
    }

    public int getDatabaseConnectionBudget() {
      return databaseConnectionBudget;
    }

    public void setDatabaseConnectionBudget(int databaseConnectionBudget) {
      this.databaseConnectionBudget = databaseConnectionBudget;
    }
  }

  public static class Ssl {
    private boolean enabled;
    private String mode = "require";
    private String rootCertPath = "";
    private String certPath = "";
    private String keyPath = "";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getMode() {
      return mode;
    }

    public void setMode(String mode) {
      this.mode = mode;
    }

    public String getRootCertPath() {
      return rootCertPath;
    }

    public void setRootCertPath(String rootCertPath) {
      this.rootCertPath = rootCertPath;
    }

    public String getCertPath() {
      return certPath;
    }

    public void setCertPath(String certPath) {
      this.certPath = certPath;
    }

    public String getKeyPath() {
      return keyPath;
    }

    public void setKeyPath(String keyPath) {
      this.keyPath = keyPath;
    }
  }

  public static class Mysql {
    private String trustStoreUrl = "";
    private String trustStoreType = "PKCS12";
    private String trustStorePasswordEnv = "";
    private String tlsVersions = "TLSv1.3,TLSv1.2";

    public String getTrustStoreUrl() {
      return trustStoreUrl;
    }

    public void setTrustStoreUrl(String trustStoreUrl) {
      this.trustStoreUrl = trustStoreUrl;
    }

    public String getTrustStoreType() {
      return trustStoreType;
    }

    public void setTrustStoreType(String trustStoreType) {
      this.trustStoreType = trustStoreType;
    }

    public String getTrustStorePasswordEnv() {
      return trustStorePasswordEnv;
    }

    public void setTrustStorePasswordEnv(String trustStorePasswordEnv) {
      this.trustStorePasswordEnv = trustStorePasswordEnv;
    }

    public String getTlsVersions() {
      return tlsVersions;
    }

    public void setTlsVersions(String tlsVersions) {
      this.tlsVersions = tlsVersions;
    }
  }

  public static class Sqlite {
    private boolean walEnabled = true;
    private Duration busyTimeout = Duration.ofSeconds(5);
    private boolean enforceSingleNode;
    private boolean integrityCheckOnStartup;
    private String synchronous = "NORMAL";

    public boolean isWalEnabled() {
      return walEnabled;
    }

    public void setWalEnabled(boolean walEnabled) {
      this.walEnabled = walEnabled;
    }

    public Duration getBusyTimeout() {
      return busyTimeout;
    }

    public void setBusyTimeout(Duration busyTimeout) {
      this.busyTimeout = busyTimeout;
    }

    public boolean isEnforceSingleNode() {
      return enforceSingleNode;
    }

    public void setEnforceSingleNode(boolean enforceSingleNode) {
      this.enforceSingleNode = enforceSingleNode;
    }

    public boolean isIntegrityCheckOnStartup() {
      return integrityCheckOnStartup;
    }

    public void setIntegrityCheckOnStartup(boolean integrityCheckOnStartup) {
      this.integrityCheckOnStartup = integrityCheckOnStartup;
    }

    public String getSynchronous() {
      return synchronous;
    }

    public void setSynchronous(String synchronous) {
      this.synchronous = synchronous;
    }
  }
}
