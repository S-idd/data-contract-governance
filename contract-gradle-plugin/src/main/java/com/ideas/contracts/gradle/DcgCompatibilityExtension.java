package com.ideas.contracts.gradle;

import java.io.File;

public class DcgCompatibilityExtension {
  private File baseSchema;
  private File candidateSchema;
  private String mode = "BACKWARD";
  private File reportFile;
  private String remoteReportingMode = "DISABLED";
  private String remoteServiceUrl;
  private String contractId;
  private String commitSha;
  private String triggeredBy = "gradle-plugin";
  private long remoteTimeoutSeconds = 5;
  private int remoteMaxAttempts = 2;
  private String ciIdentity;
  private String buildUrl;
  private String remoteAuthorization;
  private File evidenceFile;

  public File getBaseSchema() { return baseSchema; }
  public void setBaseSchema(File baseSchema) { this.baseSchema = baseSchema; }
  public File getCandidateSchema() { return candidateSchema; }
  public void setCandidateSchema(File candidateSchema) { this.candidateSchema = candidateSchema; }
  public String getMode() { return mode; }
  public void setMode(String mode) { this.mode = mode; }
  public File getReportFile() { return reportFile; }
  public void setReportFile(File reportFile) { this.reportFile = reportFile; }
  public String getRemoteReportingMode() { return remoteReportingMode; }
  public void setRemoteReportingMode(String remoteReportingMode) { this.remoteReportingMode = remoteReportingMode; }
  public String getRemoteServiceUrl() { return remoteServiceUrl; }
  public void setRemoteServiceUrl(String remoteServiceUrl) { this.remoteServiceUrl = remoteServiceUrl; }
  public String getContractId() { return contractId; }
  public void setContractId(String contractId) { this.contractId = contractId; }
  public String getCommitSha() { return commitSha; }
  public void setCommitSha(String commitSha) { this.commitSha = commitSha; }
  public String getTriggeredBy() { return triggeredBy; }
  public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }
  public long getRemoteTimeoutSeconds() { return remoteTimeoutSeconds; }
  public void setRemoteTimeoutSeconds(long remoteTimeoutSeconds) { this.remoteTimeoutSeconds = remoteTimeoutSeconds; }
  public int getRemoteMaxAttempts() { return remoteMaxAttempts; }
  public void setRemoteMaxAttempts(int remoteMaxAttempts) { this.remoteMaxAttempts = remoteMaxAttempts; }
  public String getCiIdentity() { return ciIdentity; }
  public void setCiIdentity(String ciIdentity) { this.ciIdentity = ciIdentity; }
  public String getBuildUrl() { return buildUrl; }
  public void setBuildUrl(String buildUrl) { this.buildUrl = buildUrl; }
  public String getRemoteAuthorization() { return remoteAuthorization; }
  public void setRemoteAuthorization(String remoteAuthorization) { this.remoteAuthorization = remoteAuthorization; }
  public File getEvidenceFile() { return evidenceFile; }
  public void setEvidenceFile(File evidenceFile) { this.evidenceFile = evidenceFile; }
}
