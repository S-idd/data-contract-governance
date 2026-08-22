package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceRateLimitServiceTest {
  @TempDir
  Path tempDir;

  @Test
  void sharedBucketLimitsSameIdentityAndRepositoryButNotAnotherRepositoryOrWindow() {
    CheckRunStore store = newStore();
    try {
      EvidenceRateLimitProperties properties = new EvidenceRateLimitProperties();
      properties.setRequestsPerWindow(2);
      properties.setWindow(Duration.ofMinutes(1));
      Instant firstWindow = Instant.parse("2026-08-20T10:00:30Z");
      EvidenceRateLimitService limiter = new EvidenceRateLimitService(
          store, properties, Clock.fixed(firstWindow, ZoneOffset.UTC));

      assertDoesNotThrow(() -> limiter.check(provenance("acme/orders")));
      assertDoesNotThrow(() -> limiter.check(provenance("acme/orders")));
      EvidenceRateLimitExceededException blocked = assertThrows(
          EvidenceRateLimitExceededException.class, () -> limiter.check(provenance("acme/orders")));
      org.junit.jupiter.api.Assertions.assertTrue(blocked.retryAfterSeconds() >= 30);
      assertDoesNotThrow(() -> limiter.check(provenance("acme/catalog")));

      EvidenceRateLimitService nextWindow = new EvidenceRateLimitService(
          store, properties, Clock.fixed(firstWindow.plusSeconds(31), ZoneOffset.UTC));
      assertDoesNotThrow(() -> nextWindow.check(provenance("acme/orders")));
    } finally {
      store.shutdown();
    }
  }

  private CheckRunStore newStore() {
    CheckStoreProperties properties = new CheckStoreProperties();
    properties.setPath(tempDir.resolve("rate-limit.db").toString());
    CheckRunStore store = new CheckRunStore(properties);
    store.initialize();
    return store;
  }

  private EvidenceProvenance provenance(String repository) {
    return new EvidenceProvenance(
        "OIDC", "issuer:workload", "https://issuer.example.test", "workload", "dcg", repository,
        "refs/heads/main");
  }
}
