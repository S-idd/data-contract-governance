package com.ideas.contracts.service.model;

import java.util.List;

/** Read-safe evidence metadata. Raw evidence remains retained for audit but is not returned by default. */
public record EvidenceResponse(
    String evidenceId,
    String idempotencyKey,
    String contractId,
    String baseVersion,
    String candidateVersion,
    String compatibilityMode,
    String commitSha,
    String engineVersion,
    String engineCompatibilityProtocol,
    String policyPackName,
    String policyPackSha256,
    String localStatus,
    List<String> breakingChanges,
    List<String> warnings,
    String ciIdentity,
    String authenticatedIdentity,
    String authenticationScheme,
    String oidcIssuer,
    String oidcSubject,
    String oidcAudience,
    String oidcRepository,
    String oidcRef,
    String buildUrl,
    String importStatus,
    String verificationReason,
    String importedAt
) {}
