package com.ideas.contracts.service;

import java.time.Instant;
import java.util.List;

/** Immutable report for a retention evaluation; dry-run never mutates evidence. */
public record EvidenceRetentionReport(
    String policyVersion,
    boolean dryRun,
    Instant evaluatedAt,
    List<String> verifiedOrRejectedEvidenceIds,
    List<String> operationalEvidenceIds,
    List<String> purgedEvidenceIds
) {
  public int candidateCount() {
    return verifiedOrRejectedEvidenceIds.size() + operationalEvidenceIds.size();
  }
}
