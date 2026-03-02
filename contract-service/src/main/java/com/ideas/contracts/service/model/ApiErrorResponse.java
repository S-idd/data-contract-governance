package com.ideas.contracts.service.model;

public record ApiErrorResponse(
    String timestamp,
    int status,
    String error,
    String code,
    String message,
    String path,
    String requestId
) {}
