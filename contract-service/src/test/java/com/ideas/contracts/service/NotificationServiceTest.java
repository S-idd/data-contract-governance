package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

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
}
