package com.ideas.contracts.sdk;

import java.util.List;

public record CheckRun(
    String runId,
    String contractId,
    String baseVersion,
    String candidateVersion,
    String status,
    List<String> breakingChanges,
    List<String> warnings,
    String commitSha,
    String createdAt,
    String triggeredBy,
    String startedAt,
    String finishedAt,
    String executionState
) {}
