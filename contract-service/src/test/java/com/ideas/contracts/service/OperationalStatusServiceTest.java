package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ideas.contracts.service.model.OperationalStatusResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class OperationalStatusServiceTest {
  @Test
  void surfacesFallbackAndNotificationMisconfigurationWithoutSecrets() {
    MetadataStore metadataStore = mock(MetadataStore.class);
    when(metadataStore.healthSnapshot()).thenReturn(new MetadataStore.HealthSnapshot(true, "available"));
    when(metadataStore.configuredDbTarget()).thenReturn("jdbc:postgresql://db.example.internal/contracts");

    ArtifactStore artifactStore = mock(ArtifactStore.class);
    when(artifactStore.healthSnapshot()).thenReturn(ArtifactStore.HealthSnapshot.degraded(
        "s3", "S3 is unavailable; filesystem fallback is active (S3Exception)."));

    NotificationProperties properties = new NotificationProperties();
    properties.setEnabled(true);
    properties.setSinks(List.of("webhook"));
    NotificationService notifications = new NotificationService(properties, List.of(new LogSink()));

    OperationalStatusResponse response = new OperationalStatusService(
        metadataStore, artifactStore, notifications, false).currentStatus();

    assertEquals("ACTION_REQUIRED", response.overallStatus());
    assertEquals("HEALTHY", component(response, "metadata-store").status());
    assertEquals("DEGRADED", component(response, "artifact-store").status());
    assertEquals("ACTION_REQUIRED", component(response, "notifications").status());
    assertEquals("DEGRADED", component(response, "security").status());
  }

  private OperationalStatusResponse.ComponentStatus component(
      OperationalStatusResponse response, String id) {
    return response.components().stream()
        .filter(component -> id.equals(component.id()))
        .findFirst()
        .orElseThrow();
  }

  private static final class LogSink implements NotificationSink {
    @Override
    public String name() {
      return "log";
    }

    @Override
    public void deliver(NotificationEvent event) {
      // Not needed for readiness checks.
    }
  }
}
