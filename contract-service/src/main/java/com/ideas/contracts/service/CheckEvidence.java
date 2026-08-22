package com.ideas.contracts.service;

import com.ideas.contracts.service.model.EvidenceImportRequest;
import java.time.Instant;
import java.util.List;

/** Immutable imported CI evidence; it is deliberately separate from an authoritative check run. */
public record CheckEvidence(
    String evidenceId,
    EvidenceImportRequest request,
    String payloadSha256,
    String rawEvidence,
    EvidenceProvenance provenance,
    EvidenceImportStatus importStatus,
    String verificationReason,
    String authoritativeRunId,
    Instant importedAt
) {
  public CheckEvidence {
    if (evidenceId == null || evidenceId.isBlank()) {
      throw new IllegalArgumentException("evidenceId must not be blank.");
    }
    if (request == null) {
      throw new IllegalArgumentException("request must not be null.");
    }
    if (payloadSha256 == null || !payloadSha256.matches("^[a-f0-9]{64}$")) {
      throw new IllegalArgumentException("payloadSha256 must be a SHA-256 hex digest.");
    }
    if (rawEvidence == null) {
      throw new IllegalArgumentException("rawEvidence must not be null.");
    }
    if (provenance == null) {
      throw new IllegalArgumentException("provenance must not be null.");
    }
    importStatus = importStatus == null ? EvidenceImportStatus.UNVERIFIED : importStatus;
    verificationReason = verificationReason == null || verificationReason.isBlank()
        ? null : verificationReason.trim();
    importedAt = importedAt == null ? Instant.now() : importedAt;
  }

  public static CheckEvidence newImport(
      EvidenceImportRequest request,
      String payloadSha256,
      String rawEvidence,
      EvidenceProvenance provenance,
      EvidenceImportStatus importStatus,
      String verificationReason) {
    return new CheckEvidence(
        java.util.UUID.randomUUID().toString(), request, payloadSha256, rawEvidence, provenance,
        importStatus, verificationReason, null, Instant.now());
  }

  public List<String> breakingChanges() {
    return request.breakingChanges();
  }

  public List<String> warnings() {
    return request.warnings();
  }

  /** A blank value is a tombstone: the original raw JSON was checksum-archived then purged. */
  public boolean hasRawEvidence() {
    return !rawEvidence.isBlank();
  }
}
