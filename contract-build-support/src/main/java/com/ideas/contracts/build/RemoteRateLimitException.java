package com.ideas.contracts.build;

import java.time.Duration;

/** A remote 429 response with a server-specified minimum retry delay. */
public class RemoteRateLimitException extends RuntimeException {
  private final Duration retryAfter;

  public RemoteRateLimitException(Duration retryAfter) {
    super("DCG service rate limited evidence reporting (HTTP 429).");
    this.retryAfter = retryAfter == null || retryAfter.isNegative() || retryAfter.isZero()
        ? Duration.ofSeconds(1) : retryAfter;
  }

  public Duration retryAfter() { return retryAfter; }
}
