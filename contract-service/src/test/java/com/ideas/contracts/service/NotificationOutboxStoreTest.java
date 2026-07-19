package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotificationOutboxStoreTest {
  @TempDir
  Path tempDir;

  @Test
  void persistsDeduplicatesRetriesAndCompletesNotificationDeliveries() {
    CheckStoreProperties properties = new CheckStoreProperties();
    properties.setPath(tempDir.resolve("notification-outbox.db").toString());
    CheckRunStore store = new CheckRunStore(properties);
    store.initialize();

    NotificationEvent event = sampleEvent();
    MetadataStore.NotificationEnqueueResult first = store.enqueueNotificationDelivery(event, "webhook");
    MetadataStore.NotificationEnqueueResult duplicate = store.enqueueNotificationDelivery(event, "webhook");

    assertTrue(first.created());
    assertFalse(duplicate.created());
    assertEquals(first.delivery().deliveryId(), duplicate.delivery().deliveryId());

    Instant claimedAt = Instant.now();
    NotificationDelivery firstClaim = store.claimNextNotificationDelivery(
        claimedAt, claimedAt.minusSeconds(60)).orElseThrow();
    assertEquals(NotificationDeliveryStatus.IN_FLIGHT, firstClaim.status());
    assertEquals(1, firstClaim.attemptCount());

    Instant retryAt = claimedAt.plusSeconds(30);
    assertTrue(store.markNotificationDeliveryFailed(
        firstClaim.deliveryId(), "HTTP status 503", retryAt, false));
    assertTrue(store.claimNextNotificationDelivery(
        claimedAt.plusSeconds(29), claimedAt.minusSeconds(31)).isEmpty());

    NotificationDelivery retryClaim = store.claimNextNotificationDelivery(
        retryAt, retryAt.minusSeconds(60)).orElseThrow();
    assertEquals(2, retryClaim.attemptCount());
    assertEquals("HTTP status 503", retryClaim.failureMessage());
    assertTrue(store.markNotificationDeliveryDelivered(retryClaim.deliveryId(), retryAt.plusSeconds(1)));

    List<NotificationDelivery> recent = store.listNotificationDeliveries(10);
    assertEquals(1, recent.size());
    assertEquals(NotificationDeliveryStatus.DELIVERED, recent.get(0).status());
    assertEquals("HTTP status 503", recent.get(0).failureMessage());
  }

  @Test
  void reclaimsDeliveryLeftInFlightByAnInterruptedWorker() {
    CheckStoreProperties properties = new CheckStoreProperties();
    properties.setPath(tempDir.resolve("notification-reclaim.db").toString());
    CheckRunStore store = new CheckRunStore(properties);
    store.initialize();
    store.enqueueNotificationDelivery(sampleEvent(), "webhook");

    Instant firstClaimAt = Instant.now();
    NotificationDelivery firstClaim = store.claimNextNotificationDelivery(
        firstClaimAt, firstClaimAt.minusSeconds(60)).orElseThrow();

    NotificationDelivery reclaimed = store.claimNextNotificationDelivery(
        firstClaimAt.plusSeconds(61), firstClaimAt.plusSeconds(1)).orElseThrow();

    assertEquals(firstClaim.deliveryId(), reclaimed.deliveryId());
    assertEquals(NotificationDeliveryStatus.IN_FLIGHT, reclaimed.status());
    assertEquals(2, reclaimed.attemptCount());
  }

  private NotificationEvent sampleEvent() {
    return new NotificationEvent(
        "event-1",
        NotificationEventType.CONTRACT_CHECK_FAILED,
        NotificationSeverity.HIGH,
        Instant.parse("2026-05-23T00:00:00Z"),
        "orders.created",
        "run-1",
        "v1",
        "v2",
        "abc123",
        "ci",
        "baseline",
        "Compatibility check failed.",
        List.of("Field type changed: orderId"),
        List.of(),
        java.util.Map.of("checkRun", "/checks/run-1"),
        "CONTRACT_CHECK_FAILED:orders.created:abc123:v1:v2");
  }
}
