package com.ideas.contracts.service;

/** Raised while streaming an evidence request that exceeds the configured HTTP payload cap. */
public class EvidencePayloadLimitExceededException extends RuntimeException {
  public EvidencePayloadLimitExceededException() {
    super("EVIDENCE_PAYLOAD_TOO_LARGE: evidence payload exceeds the configured size limit.");
  }
}
