package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class NotificationServiceTest {
  @Test
  void disabledNotificationsDoNotEnqueueOrDeliver() {
    NotificationProperties properties = new NotificationProperties();
    properties.setEnabled(false);
    RecordingSink sink = new RecordingSink("log");
    MetadataStore store = mock(MetadataStore.class);

    NotificationService service = new NotificationService(properties, List.of(sink), store);
    service.publish(sampleEvent());
    service.dispatchPendingDeliveries();

    verify(store, never()).enqueueNotificationDelivery(any(), any());
    verify(store, never()).claimNextNotificationDelivery(any(), any());
    assertEquals(0, sink.events.size());
  }

  @Test
  void enabledNotificationsEnqueueConfiguredSinkWithoutSynchronousDelivery() {
    NotificationProperties properties = enabledProperties("log");
    RecordingSink sink = new RecordingSink("log");
    MetadataStore store = mock(MetadataStore.class);
    when(store.enqueueNotificationDelivery(any(), eq("log")))
        .thenReturn(new MetadataStore.NotificationEnqueueResult(sampleDelivery("log", 0), true));

    NotificationService service = new NotificationService(properties, List.of(sink), store);
    service.publish(sampleEvent());

    verify(store).enqueueNotificationDelivery(any(), eq("log"));
    assertEquals(0, sink.events.size());
  }

  @Test
  void deliveryFailureDoesNotEscapeAndIsMarkedRetryable() {
    NotificationProperties properties = enabledProperties("broken");
    properties.getRetry().setMaxAttempts(2);
    CountingFailingSink sink = new CountingFailingSink("sink unavailable");
    MetadataStore store = mock(MetadataStore.class);
    NotificationDelivery delivery = sampleDelivery("broken", 1);
    when(store.claimNextNotificationDelivery(any(), any()))
        .thenReturn(Optional.of(delivery), Optional.empty());
    when(store.markNotificationDeliveryFailed(eq(delivery.deliveryId()), any(), any(), eq(false)))
        .thenReturn(true);

    NotificationService service = new NotificationService(properties, List.of(sink), store);

    assertDoesNotThrow(service::dispatchPendingDeliveries);

    assertEquals(1, sink.attempts);
    verify(store).markNotificationDeliveryFailed(eq(delivery.deliveryId()), eq("sink unavailable"), any(), eq(false));
  }

  @Test
  void finalFailedAttemptIsMarkedPermanent() {
    NotificationProperties properties = enabledProperties("broken");
    properties.getRetry().setMaxAttempts(2);
    CountingFailingSink sink = new CountingFailingSink("sink unavailable");
    MetadataStore store = mock(MetadataStore.class);
    NotificationDelivery delivery = sampleDelivery("broken", 2);
    when(store.claimNextNotificationDelivery(any(), any()))
        .thenReturn(Optional.of(delivery), Optional.empty());
    when(store.markNotificationDeliveryFailed(eq(delivery.deliveryId()), any(), isNull(), eq(true)))
        .thenReturn(true);

    NotificationService service = new NotificationService(properties, List.of(sink), store);
    service.dispatchPendingDeliveries();

    verify(store).markNotificationDeliveryFailed(
        eq(delivery.deliveryId()), eq("sink unavailable"), isNull(), eq(true));
  }

  @Test
  void deliveryFailureLogsAndStoresRedactedMessage(CapturedOutput output) {
    NotificationProperties properties = enabledProperties("broken");
    properties.getRetry().setMaxAttempts(1);
    CountingFailingSink sink = new CountingFailingSink(
        "Authorization=Bearer secret-token password=letmein token=abc123");
    MetadataStore store = mock(MetadataStore.class);
    NotificationDelivery delivery = sampleDelivery("broken", 1);
    when(store.claimNextNotificationDelivery(any(), any()))
        .thenReturn(Optional.of(delivery), Optional.empty());
    when(store.markNotificationDeliveryFailed(eq(delivery.deliveryId()), any(), isNull(), eq(true)))
        .thenReturn(true);

    NotificationService service = new NotificationService(properties, List.of(sink), store);
    service.dispatchPendingDeliveries();

    String logs = output.toString();
    assertTrue(logs.contains("event=notification_delivery_failed"));
    assertTrue(logs.contains("[REDACTED]"));
    assertFalse(logs.contains("secret-token"));
    assertFalse(logs.contains("letmein"));
    assertFalse(logs.contains("abc123"));
    verify(store).markNotificationDeliveryFailed(
        eq(delivery.deliveryId()),
        eq("Authorization=[REDACTED] password=[REDACTED] token=[REDACTED]"),
        isNull(),
        eq(true));
  }

  @Test
  void successfulDeliveryIsMarkedDelivered() {
    NotificationProperties properties = enabledProperties("log");
    RecordingSink sink = new RecordingSink("log");
    MetadataStore store = mock(MetadataStore.class);
    NotificationDelivery delivery = sampleDelivery("log", 1);
    when(store.claimNextNotificationDelivery(any(), any()))
        .thenReturn(Optional.of(delivery), Optional.empty());
    when(store.markNotificationDeliveryDelivered(eq(delivery.deliveryId()), any())).thenReturn(true);

    NotificationService service = new NotificationService(properties, List.of(sink), store);
    service.dispatchPendingDeliveries();

    assertEquals(1, sink.events.size());
    verify(store).markNotificationDeliveryDelivered(eq(delivery.deliveryId()), any());
  }

  private NotificationProperties enabledProperties(String sinkName) {
    NotificationProperties properties = new NotificationProperties();
    properties.setEnabled(true);
    properties.setSinks(List.of(sinkName));
    properties.getDispatch().setMaxPerPoll(1);
    return properties;
  }

  private NotificationDelivery sampleDelivery(String sinkName, int attemptCount) {
    return new NotificationDelivery(
        "delivery-1",
        sampleEvent(),
        sinkName,
        NotificationDeliveryStatus.IN_FLIGHT,
        attemptCount,
        Instant.parse("2026-05-23T00:00:00Z"),
        Instant.parse("2026-05-23T00:00:01Z"),
        null,
        null,
        null);
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

  private static final class RecordingSink implements NotificationSink {
    private final String name;
    private final List<NotificationEvent> events = new ArrayList<>();

    private RecordingSink(String name) {
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public void deliver(NotificationEvent event) {
      events.add(event);
    }
  }

  private static final class CountingFailingSink implements NotificationSink {
    private final String message;
    private int attempts;

    private CountingFailingSink(String message) {
      this.message = message;
    }

    @Override
    public String name() {
      return "broken";
    }

    @Override
    public void deliver(NotificationEvent event) {
      attempts++;
      throw new IllegalStateException(message);
    }
  }
}
