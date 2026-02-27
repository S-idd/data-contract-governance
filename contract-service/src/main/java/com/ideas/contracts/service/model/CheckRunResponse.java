package com.ideas.contracts.service.model;

public record CheckRunResponse(
    String runId,
    String contractId,
    String baseVersion,
    String candidateVersion,
    String status,
    String breakingChanges,
    String warnings,
    String commitSha,
    String createdAt
) {}
