package com.ideas.contracts.service;

public class CheckRunNotFoundException extends RuntimeException {
  private final String runId;

  public CheckRunNotFoundException(String runId) {
    super("Check run not found: " + runId);
    this.runId = runId;
  }

  public String getRunId() {
    return runId;
  }
}
