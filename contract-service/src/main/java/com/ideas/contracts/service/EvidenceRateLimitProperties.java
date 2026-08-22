package com.ideas.contracts.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Shared fixed-window rate limit for evidence imports. */
@Component
@ConfigurationProperties(prefix = "checks.evidence.rate-limit")
public class EvidenceRateLimitProperties {
  private boolean enabled = true;
  private int requestsPerWindow = 60;
  private Duration window = Duration.ofMinutes(1);

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public int getRequestsPerWindow() { return requestsPerWindow; }
  public void setRequestsPerWindow(int requestsPerWindow) { this.requestsPerWindow = requestsPerWindow; }
  public Duration getWindow() { return window; }
  public void setWindow(Duration window) { this.window = window; }
}
