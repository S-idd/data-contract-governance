package com.ideas.contracts.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Enforces a shared fixed-window quota keyed by authenticated workload identity and repository. */
@Service
public class EvidenceRateLimitService {
  private final MetadataStore metadataStore;
  private final EvidenceRateLimitProperties properties;
  private final Clock clock;

  @Autowired
  public EvidenceRateLimitService(MetadataStore metadataStore, EvidenceRateLimitProperties properties) {
    this(metadataStore, properties, Clock.systemUTC());
  }

  EvidenceRateLimitService(
      MetadataStore metadataStore, EvidenceRateLimitProperties properties, Clock clock) {
    this.metadataStore = metadataStore;
    this.properties = properties;
    this.clock = clock;
  }

  public void check(EvidenceProvenance provenance) {
    if (!properties.isEnabled()) {
      return;
    }
    if (provenance == null) {
      throw new IllegalArgumentException("Evidence provenance is required for rate limiting.");
    }
    int limit = properties.getRequestsPerWindow();
    Duration window = properties.getWindow();
    if (limit < 1 || window == null || window.isNegative() || window.isZero()) {
      throw new IllegalStateException("Evidence rate-limit configuration must have a positive limit and window.");
    }
    long windowSeconds = Math.max(1, Math.min(window.toSeconds(), Duration.ofHours(1).toSeconds()));
    Instant now = Instant.now(clock);
    Instant windowStart = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), windowSeconds) * windowSeconds);
    EvidenceRateLimitDecision decision = metadataStore.tryAcquireEvidenceRateLimit(
        bucketKey(provenance), "fixed-" + windowSeconds + "s", windowStart, limit, now);
    if (!decision.allowed()) {
      throw new EvidenceRateLimitExceededException(decision.retryAfterSeconds());
    }
  }

  private String bucketKey(EvidenceProvenance provenance) {
    String repository = provenance.repository() == null ? "-" : provenance.repository();
    String material = provenance.authenticationScheme() + "\u0000" + provenance.authenticatedIdentity()
        + "\u0000" + repository;
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
      return "evidence:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable for rate-limit key derivation.", error);
    }
  }
}
