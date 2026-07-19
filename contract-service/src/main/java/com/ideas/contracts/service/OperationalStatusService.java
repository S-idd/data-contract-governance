package com.ideas.contracts.service;

import com.ideas.contracts.service.model.OperationalStatusResponse;
import com.ideas.contracts.service.model.OperationalStatusResponse.ComponentStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OperationalStatusService {
  private final MetadataStore metadataStore;
  private final ArtifactStore artifactStore;
  private final NotificationService notificationService;
  private final boolean securityEnabled;

  public OperationalStatusService(
      MetadataStore metadataStore,
      ArtifactStore artifactStore,
      NotificationService notificationService,
      @Value("${app.security.enabled:false}") boolean securityEnabled) {
    this.metadataStore = metadataStore;
    this.artifactStore = artifactStore;
    this.notificationService = notificationService;
    this.securityEnabled = securityEnabled;
  }

  public OperationalStatusResponse currentStatus() {
    List<ComponentStatus> components = new ArrayList<>();
    components.add(metadataStatus());
    components.add(artifactStatus());
    components.add(notificationStatus());
    components.add(securityStatus());
    return new OperationalStatusResponse(overallStatus(components), components);
  }

  private ComponentStatus metadataStatus() {
    MetadataStore.HealthSnapshot snapshot = metadataStore.healthSnapshot();
    String storeType = metadataStoreType(metadataStore.configuredDbTarget());
    if (snapshot.available()) {
      return new ComponentStatus(
          "metadata-store",
          "Metadata store",
          "HEALTHY",
          storeType + " metadata store is reachable.",
          "No action required.");
    }
    return new ComponentStatus(
        "metadata-store",
        "Metadata store",
        "UNAVAILABLE",
        storeType + " metadata store is unavailable.",
        "Verify database reachability, credentials, and migrations, then check /actuator/health.");
  }

  private ComponentStatus artifactStatus() {
    ArtifactStore.HealthSnapshot snapshot = artifactStore.healthSnapshot();
    String backend = snapshot.backend().toUpperCase(Locale.ROOT);
    return switch (snapshot.status()) {
      case HEALTHY -> new ComponentStatus(
          "artifact-store",
          "Artifact store",
          "HEALTHY",
          backend + " artifact reads are available.",
          "No action required.");
      case DEGRADED -> new ComponentStatus(
          "artifact-store",
          "Artifact store",
          "DEGRADED",
          snapshot.detail(),
          "Check artifact-store configuration before relying on fallback behavior.");
      case UNAVAILABLE -> new ComponentStatus(
          "artifact-store",
          "Artifact store",
          "UNAVAILABLE",
          backend + " artifact reads are unavailable.",
          "Verify artifact-store credentials, access, and recovery runbooks.");
    };
  }

  private ComponentStatus notificationStatus() {
    NotificationService.Readiness readiness = notificationService.readiness();
    return switch (readiness.status()) {
      case READY -> new ComponentStatus(
          "notifications", "Notifications", "HEALTHY", readiness.detail(), readiness.action());
      case DISABLED -> new ComponentStatus(
          "notifications", "Notifications", "DISABLED", readiness.detail(), readiness.action());
      case ACTION_REQUIRED -> new ComponentStatus(
          "notifications", "Notifications", "ACTION_REQUIRED", readiness.detail(), readiness.action());
    };
  }

  private ComponentStatus securityStatus() {
    if (securityEnabled) {
      return new ComponentStatus(
          "security", "Security mode", "HEALTHY",
          "Authentication is enabled for the UI and protected write routes.",
          "No action required.");
    }
    return new ComponentStatus(
        "security", "Security mode", "DEGRADED",
        "Authentication is disabled for this environment.",
        "Enable app.security.enabled before exposing this service to shared users.");
  }

  private String metadataStoreType(String target) {
    String normalized = target == null ? "" : target.toLowerCase(Locale.ROOT);
    if (normalized.startsWith("jdbc:postgresql:")) {
      return "PostgreSQL";
    }
    if (normalized.startsWith("jdbc:mysql:")) {
      return "MySQL";
    }
    if (normalized.startsWith("jdbc:sqlite:")) {
      return "SQLite";
    }
    return "Configured";
  }

  private String overallStatus(List<ComponentStatus> components) {
    if (components.stream().anyMatch(component -> "UNAVAILABLE".equals(component.status())
        || "ACTION_REQUIRED".equals(component.status()))) {
      return "ACTION_REQUIRED";
    }
    if (components.stream().anyMatch(component -> "DEGRADED".equals(component.status()))) {
      return "DEGRADED";
    }
    return "HEALTHY";
  }
}
