package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolicyPackRegistryNotificationTest {
  @TempDir
  Path tempDir;

  @Test
  void invalidPolicyPackConfigPublishesNotificationBeforeFailFast() throws Exception {
    Path configPath = tempDir.resolve("policy-packs.json");
    Files.writeString(configPath, "{");
    NotificationProperties properties = new NotificationProperties();
    properties.setEnabled(true);
    properties.setSinks(List.of("recording"));
    RecordingSink sink = new RecordingSink();
    NotificationService notificationService = new NotificationService(properties, List.of(sink));

    assertThrows(
        IllegalStateException.class,
        () -> new PolicyPackRegistry(configPath.toString(), tempDir.toString(), notificationService));

    assertEquals(1, sink.events.size());
    assertEquals(NotificationEventType.POLICY_PACK_CONFIG_INVALID, sink.events.get(0).eventType());
    assertEquals(NotificationSeverity.HIGH, sink.events.get(0).severity());
  }

  private static final class RecordingSink implements NotificationSink {
    private final List<NotificationEvent> events = new ArrayList<>();

    @Override
    public String name() {
      return "recording";
    }

    @Override
    public void deliver(NotificationEvent event) {
      events.add(event);
    }
  }
}
