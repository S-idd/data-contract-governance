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
  private boolean failFastStartup;
  private boolean enforceSecurePostgres;
  private Duration queryTimeout = Duration.ofSeconds(5);
  private final Pool pool = new Pool();
  private final Ssl ssl = new Ssl();

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

  public static class Pool {
    private int minimumIdle = 1;
    private int maximumSize = 10;
    private Duration connectionTimeout = Duration.ofSeconds(1);
    private Duration idleTimeout = Duration.ofMinutes(2);
    private Duration maxLifetime = Duration.ofMinutes(30);
    private Duration validationTimeout = Duration.ofSeconds(3);
    private Duration initializationFailTimeout = Duration.ofMillis(-1);

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
}
