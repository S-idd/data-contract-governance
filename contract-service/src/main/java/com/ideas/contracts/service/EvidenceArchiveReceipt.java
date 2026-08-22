package com.ideas.contracts.service;

import java.time.Instant;

/** Checksum-verified result of archiving one immutable evidence payload. */
public record EvidenceArchiveReceipt(
    String evidenceId,
    String location,
    String sha256,
    Instant archivedAt
) {
  public EvidenceArchiveReceipt {
    if (evidenceId == null || evidenceId.isBlank()) {
      throw new IllegalArgumentException("evidenceId must not be blank.");
    }
    if (location == null || location.isBlank()) {
      throw new IllegalArgumentException("location must not be blank.");
    }
    if (sha256 == null || !sha256.matches("^[a-f0-9]{64}$")) {
      throw new IllegalArgumentException("sha256 must be a SHA-256 hex digest.");
    }
    archivedAt = archivedAt == null ? Instant.now() : archivedAt;
  }
}
