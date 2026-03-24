package com.ideas.contracts.sdk;

public record RunLog(
    String logId,
    String runId,
    String level,
    String message,
    String createdAt
) {}
