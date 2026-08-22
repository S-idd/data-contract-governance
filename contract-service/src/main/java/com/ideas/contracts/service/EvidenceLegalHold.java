package com.ideas.contracts.service;

import java.time.Instant;

/** A legal or investigation hold that blocks evidence retention actions. */
public record EvidenceLegalHold(
    String holdId,
    String evidenceId,
    String contractId,
    String repository,
    boolean active,
    String reason,
    String createdBy,
    Instant createdAt,
    String releasedBy,
    Instant releasedAt
) {
  public EvidenceLegalHold {
    evidenceId = optional(evidenceId);
    contractId = optional(contractId);
    repository = optional(repository);
    if (evidenceId == null && contractId == null) {
      throw new IllegalArgumentException("A legal hold must target an evidence ID or a contract ID.");
    }
    reason = required("reason", reason);
    createdBy = required("createdBy", createdBy);
    createdAt = createdAt == null ? Instant.now() : createdAt;
    releasedBy = optional(releasedBy);
  }

  public static EvidenceLegalHold active(
      String evidenceId, String contractId, String repository, String reason, String createdBy) {
    return new EvidenceLegalHold(
        java.util.UUID.randomUUID().toString(), evidenceId, contractId, repository, true, reason, createdBy,
        Instant.now(), null, null);
  }

  private static String required(String name, String value) {
    String normalized = optional(value);
    if (normalized == null) {
      throw new IllegalArgumentException(name + " must not be blank.");
    }
    return normalized;
  }

  private static String optional(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
