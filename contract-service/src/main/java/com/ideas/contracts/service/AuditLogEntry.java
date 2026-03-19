package com.ideas.contracts.service;

import java.util.Map;

public record AuditLogEntry(
    String action,
    String status,
    String actor,
    String actorRoles,
    String source,
    String requestId,
    String httpMethod,
    String path,
    String resourceType,
    String resourceId,
    Map<String, Object> detail
) {}
