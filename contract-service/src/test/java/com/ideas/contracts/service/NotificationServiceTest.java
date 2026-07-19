package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class NotificationServiceTest {
  @Test
  void disabledNotificationsDoNotDeliver() {
    NotificationProperties properties = new NotificationProperties();
    properties.setEnabled(false);
    RecordingSink sink = new RecordingSink("log");

    NotificationService service = new NotificationService(properties, List.of(sink));
    service.publish(sampleEvent());

    assertEquals(0, sink.events.size());
  }

  @Test
  void enabledNotificationsDeliverToConfiguredSink() {
    NotificationProperties properties = new NotificationProperties();
    properties.setEnabled(true);
    properties.setSinks(List.of("log"));
    RecordingSink sink = new RecordingSink("log");

    NotificationService service = new NotificationService(properties, List.of(sink));
    service.publish(sampleEvent());

    assertEquals(1, sink.events.size());
    assertEquals(NotificationEventType.CONTRACT_CHECK_FAILED, sink.events.get(0).eventType());
  }

  @Test
  void deliveryFailureDoesNotEscape() {
    NotificationProperties properties = new NotificationProperties();
    properties.setEnabled(true);
    properties.setSinks(List.of("broken"));

    NotificationService service = new NotificationService(properties, List.of(new FailingSink()));

    assertDoesNotThrow(() -> service.publish(sampleEvent()));
  }

  @Test
  void deliveryFailureRetriesConfiguredAttempts() {
    NotificationProperties properties = new NotificationProperties();
    properties.setEnabled(true);
    properties.setSinks(List.of("broken"));
    properties.getRetry().setMaxAttempts(2);
    CountingFailingSink sink = new CountingFailingSink("sink unavailable");

    NotificationService service = new NotificationService(properties, List.of(sink));

    service.publish(sampleEvent());

    assertEquals(2, sink.attempts);
  }

  @Test
  void deliveryFailureLogsRedactedMessage(CapturedOutput output) {
    NotificationProperties properties = new NotificationProperties();
    properties.setEnabled(true);
    properties.setSinks(List.of("broken"));
    properties.getRetry().setMaxAttempts(1);

    NotificationService service = new NotificationService(
        properties,
        List.of(new CountingFailingSink("Authorization=Bearer secret-token password=letmein token=abc123")));

    service.publish(sampleEvent());

    String logs = output.toString();
    assertTrue(logs.contains("event=notification_delivery_failed"));
    assertTrue(logs.contains("[REDACTED]"));
    assertFalse(logs.contains("secret-token"));
    assertFalse(logs.contains("letmein"));
    assertFalse(logs.contains("abc123"));
  }

  private NotificationEvent sampleEvent() {
    return new NotificationEvent(
        "event-1",
        NotificationEventType.CONTRACT_CHECK_FAILED,
        NotificationSeverity.HIGH,
        null,
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

  private static final class FailingSink implements NotificationSink {
    @Override
    public String name() {
      return "broken";
    }

    @Override
    public void deliver(NotificationEvent event) {
      throw new IllegalStateException("sink unavailable");
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
