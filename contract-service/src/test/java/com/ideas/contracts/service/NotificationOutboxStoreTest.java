package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

  @Test
  void retriesNotificationClaimAfterTransientSqliteWriterContention() throws Exception {
    Path databasePath = tempDir.resolve("notification-busy-retry.db");
    CheckStoreProperties properties = new CheckStoreProperties();
    properties.setPath(databasePath.toString());
    properties.getSqlite().setBusyTimeout(Duration.ofMillis(1));
    CheckRunStore store = new CheckRunStore(properties);
    store.initialize();
    MetadataStore.NotificationEnqueueResult enqueued =
        store.enqueueNotificationDelivery(sampleEvent(), "webhook");

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection blocker = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
      blocker.setAutoCommit(false);
      try (PreparedStatement statement = blocker.prepareStatement("""
          UPDATE notification_deliveries
          SET status = status
          WHERE delivery_id = ?
          """)) {
        statement.setString(1, enqueued.delivery().deliveryId());
        assertEquals(1, statement.executeUpdate());
      }

      CountDownLatch claimStarted = new CountDownLatch(1);
      Future<Optional<NotificationDelivery>> claimed = executor.submit(() -> {
        claimStarted.countDown();
        Instant claimedAt = Instant.now();
        return store.claimNextNotificationDelivery(claimedAt, claimedAt.minusSeconds(60));
      });

      assertTrue(claimStarted.await(1, TimeUnit.SECONDS));
      Thread.sleep(80);
      blocker.rollback();

      NotificationDelivery delivery = claimed.get(2, TimeUnit.SECONDS).orElseThrow();
      assertEquals(enqueued.delivery().deliveryId(), delivery.deliveryId());
      assertEquals(NotificationDeliveryStatus.IN_FLIGHT, delivery.status());
      assertEquals(1, delivery.attemptCount());
    } finally {
      executor.shutdownNow();
      store.shutdown();
    }
  }

  @Test
  void filtersAndRequeuesFailedNotificationDeliveries() {
    CheckStoreProperties properties = new CheckStoreProperties();
    properties.setPath(tempDir.resolve("notification-filter-retry.db").toString());
    CheckRunStore store = new CheckRunStore(properties);
    store.initialize();

    MetadataStore.NotificationEnqueueResult failed = store.enqueueNotificationDelivery(
        sampleEvent(
            "event-rejected",
            NotificationEventType.CONTRACT_VERSION_REJECTED,
            "orders.created",
            "run-rejected",
            "CONTRACT_VERSION_REJECTED:orders.created:v3"),
        "webhook");
    store.enqueueNotificationDelivery(
        sampleEvent(
            "event-check",
            NotificationEventType.CONTRACT_CHECK_FAILED,
            "users.created",
            "run-check",
            "CONTRACT_CHECK_FAILED:users.created:v1:v2"),
        "log");

    Instant claimedAt = Instant.now();
    NotificationDelivery claim = store.claimNextNotificationDelivery(
        claimedAt, claimedAt.minusSeconds(60)).orElseThrow();
    assertEquals(failed.delivery().deliveryId(), claim.deliveryId());
    assertTrue(store.markNotificationDeliveryFailed(claim.deliveryId(), "HTTP status 500", null, true));

    List<NotificationDelivery> filtered = store.listNotificationDeliveries(
        NotificationDeliveryQuery.from(
            "FAILED_PERMANENT",
            "orders.created",
            "webhook",
            "CONTRACT_VERSION_REJECTED",
            10));
    assertEquals(1, filtered.size());
    assertEquals(failed.delivery().deliveryId(), filtered.get(0).deliveryId());
    assertEquals(NotificationDeliveryStatus.FAILED_PERMANENT, filtered.get(0).status());

    List<NotificationDelivery> filteredByRun = store.listNotificationDeliveries(
        NotificationDeliveryQuery.from(null, null, null, null, "run-rejected", 10));
    assertEquals(1, filteredByRun.size());
    assertEquals(failed.delivery().deliveryId(), filteredByRun.get(0).deliveryId());

    Instant retryAt = claimedAt.plusSeconds(5);
    assertTrue(store.requeueNotificationDelivery(failed.delivery().deliveryId(), retryAt));
    NotificationDelivery requeued = store.findNotificationDelivery(failed.delivery().deliveryId()).orElseThrow();
    assertEquals(NotificationDeliveryStatus.PENDING, requeued.status());
    assertEquals(retryAt, requeued.nextAttemptAt());
    assertFalse(store.requeueNotificationDelivery(failed.delivery().deliveryId(), retryAt.plusSeconds(1)));
  }

  private NotificationEvent sampleEvent() {
    return sampleEvent(
        "event-1",
        NotificationEventType.CONTRACT_CHECK_FAILED,
        "orders.created",
        "run-1",
        "CONTRACT_CHECK_FAILED:orders.created:abc123:v1:v2");
  }

  private NotificationEvent sampleEvent(
      String eventId,
      NotificationEventType eventType,
      String contractId,
      String runId,
      String dedupeKey) {
    return new NotificationEvent(
        eventId,
        eventType,
        NotificationSeverity.HIGH,
        Instant.parse("2026-05-23T00:00:00Z"),
        contractId,
        runId,
        "v1",
        "v2",
        "abc123",
        "ci",
        "baseline",
        "Compatibility check failed.",
        List.of("Field type changed: orderId"),
        List.of(),
        java.util.Map.of("checkRun", "/checks/" + runId),
        dedupeKey);
  }
}
