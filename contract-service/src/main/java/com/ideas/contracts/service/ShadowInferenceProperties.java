package com.ideas.contracts.service;

import java.time.Duration;
import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "shadow.inference")
public class ShadowInferenceProperties {
  private boolean enabled;
  private String endpoint = "http://127.0.0.1:8080/v1/shadow/predict";
  private Duration timeout = Duration.ofMillis(500);
  private int workerThreads = 2;
  private int queueCapacity = 100;
  private boolean testOnlyAdapter;
  @Autowired(required = false)
  private Environment environment;

  @PostConstruct
  void validateTestOnlyMode() {
    if (testOnlyAdapter && (environment == null || Arrays.stream(environment.getActiveProfiles())
        .noneMatch("phase2-rehearsal"::equals))) {
      throw new IllegalStateException(
          "Test-only advisory adapter requires the phase2-rehearsal profile.");
    }
  }

  public boolean isTestOnlyAdapter() {
    return testOnlyAdapter;
  }

  public void setTestOnlyAdapter(boolean testOnlyAdapter) {
    this.testOnlyAdapter = testOnlyAdapter;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }

  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration timeout) {
    this.timeout = timeout;
  }

  public int getWorkerThreads() {
    return workerThreads;
  }

  public void setWorkerThreads(int workerThreads) {
    this.workerThreads = workerThreads;
  }

  public int getQueueCapacity() {
    return queueCapacity;
  }

  public void setQueueCapacity(int queueCapacity) {
    this.queueCapacity = queueCapacity;
  }
}
