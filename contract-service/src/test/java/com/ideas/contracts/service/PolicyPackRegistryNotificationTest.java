package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

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
    NotificationSink sink = new RecordingSink();
    MetadataStore metadataStore = mock(MetadataStore.class);
    when(metadataStore.enqueueNotificationDelivery(any(), eq("recording")))
        .thenAnswer(invocation -> new MetadataStore.NotificationEnqueueResult(
            new NotificationDelivery(
                "delivery-1",
                invocation.getArgument(0),
                "recording",
                NotificationDeliveryStatus.PENDING,
                0,
                null,
                null,
                null,
                null,
                null),
            true));
    NotificationService notificationService = new NotificationService(
        properties, List.of(sink), metadataStore);

    assertThrows(
        IllegalStateException.class,
        () -> new PolicyPackRegistry(configPath.toString(), tempDir.toString(), notificationService));

    ArgumentCaptor<NotificationEvent> event = ArgumentCaptor.forClass(NotificationEvent.class);
    verify(metadataStore).enqueueNotificationDelivery(event.capture(), eq("recording"));
    assertEquals(NotificationEventType.POLICY_PACK_CONFIG_INVALID, event.getValue().eventType());
    assertEquals(NotificationSeverity.HIGH, event.getValue().severity());
  }

  private static final class RecordingSink implements NotificationSink {
    @Override
    public String name() {
      return "recording";
    }

    @Override
    public void deliver(NotificationEvent event) {
      // The outbox is verified above; this sink is intentionally not invoked synchronously.
    }
  }
}
