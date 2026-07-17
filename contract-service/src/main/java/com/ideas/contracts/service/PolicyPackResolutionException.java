package com.ideas.contracts.service;

class PolicyPackResolutionException extends RuntimeException {
  private final String contractId;
  private final String runId;
  private final String policyPack;

  PolicyPackResolutionException(String contractId, String runId, String policyPack, RuntimeException cause) {
    super("Policy pack resolution failed for " + safe(contractId) + ".", cause);
    this.contractId = contractId;
    this.runId = runId;
    this.policyPack = policyPack;
  }

  String contractId() {
    return contractId;
  }

  String runId() {
    return runId;
  }

  String policyPack() {
    return policyPack;
  }

  private static String safe(String value) {
    return value == null || value.isBlank() ? "unknown contract" : value.trim();
  }
}
