package com.ideas.contracts.service.model;

public record CheckRunLogResponse(
    String logId,
    String runId,
    String level,
    String message,
    String createdAt
) {}
