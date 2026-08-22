package com.ideas.contracts.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Retention policy is intentionally report-only until an immutable archive adapter is configured. */
@Component
@ConfigurationProperties(prefix = "checks.evidence.retention")
public class EvidenceRetentionProperties {
  private boolean enabled;
  private boolean dryRun = true;
  private String policyVersion = "evidence-retention-v1";
  private Duration verifiedRejectedRetention = Duration.ofDays(2555);
  private Duration operationalRetention = Duration.ofDays(180);
  private int batchSize = 100;

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public boolean isDryRun() { return dryRun; }
  public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
  public String getPolicyVersion() { return policyVersion; }
  public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
  public Duration getVerifiedRejectedRetention() { return verifiedRejectedRetention; }
  public void setVerifiedRejectedRetention(Duration value) { this.verifiedRejectedRetention = value; }
  public Duration getOperationalRetention() { return operationalRetention; }
  public void setOperationalRetention(Duration value) { this.operationalRetention = value; }
  public int getBatchSize() { return batchSize; }
  public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
}
