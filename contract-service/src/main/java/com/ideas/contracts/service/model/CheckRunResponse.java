package com.ideas.contracts.service.model;

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
) {}
