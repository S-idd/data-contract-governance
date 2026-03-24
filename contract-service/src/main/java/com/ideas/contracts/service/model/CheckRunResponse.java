package com.ideas.contracts.service.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CheckRunResponse(
    String runId,
    String contractId,
    String baseVersion,
    String candidateVersion,
    String status,
    java.util.List<String> breakingChanges,
    java.util.List<String> warnings,
    String commitSha,
    String createdAt,
    String triggeredBy,
    String startedAt,
    String finishedAt
) {
  @JsonProperty("executionState")
  public String executionState() {
    if (status == null) {
      return "UNKNOWN";
    }
    return switch (status.toUpperCase()) {
      case "PASS" -> "SUCCESS";
      case "FAIL" -> "FAILED";
      default -> status.toUpperCase();
    };
  }
}
