package com.ideas.contracts.service;

import java.time.Duration;
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
