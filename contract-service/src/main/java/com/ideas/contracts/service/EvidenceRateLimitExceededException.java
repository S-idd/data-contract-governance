package com.ideas.contracts.service;

/** Signals a deliberate 429 response without exposing identity or limiter internals. */
public class EvidenceRateLimitExceededException extends RuntimeException {
  private final long retryAfterSeconds;

  public EvidenceRateLimitExceededException(long retryAfterSeconds) {
    super("Evidence import rate limit exceeded. Retry later.");
    this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
  }

  public long retryAfterSeconds() { return retryAfterSeconds; }
}
