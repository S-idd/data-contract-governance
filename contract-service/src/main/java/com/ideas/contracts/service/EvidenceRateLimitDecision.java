package com.ideas.contracts.service;

/** Result from the shared metadata-store limiter; the bucket key is never exposed or logged. */
public record EvidenceRateLimitDecision(boolean allowed, long retryAfterSeconds) {
  public EvidenceRateLimitDecision {
    retryAfterSeconds = Math.max(1, retryAfterSeconds);
  }
}
