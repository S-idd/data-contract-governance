package com.ideas.contracts.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {
  private static final Logger LOGGER = LoggerFactory.getLogger(NotificationService.class);

  private final NotificationProperties properties;
  private final List<NotificationSink> sinks;

  public NotificationService(NotificationProperties properties, List<NotificationSink> sinks) {
    this.properties = properties;
    this.sinks = List.copyOf(sinks);
  }

  public void publish(NotificationEvent event) {
    if (event == null || !properties.isEnabled()) {
      return;
    }

    Set<String> enabledSinks = properties.normalizedSinks();
    int attempted = 0;
    for (NotificationSink sink : sinks) {
      String sinkName = sink.name().toLowerCase(Locale.ROOT);
      if (!enabledSinks.contains(sinkName)) {
        continue;
      }
      attempted++;
      deliverSafely(event, sink);
    }

    if (attempted == 0) {
      LOGGER.warn(
          "event=notification_no_sink component=notification_service event_id={} event_type={} configured_sinks={}",
          event.eventId(),
          event.eventType(),
          enabledSinks);
    }
  }

  public Readiness readiness() {
    if (!properties.isEnabled()) {
      return new Readiness(
          ReadinessStatus.DISABLED,
          "Notifications are disabled.",
          "Enable notifications when this environment needs operational alerts.");
    }

    Set<String> configuredSinks = properties.normalizedSinks();
    Set<String> registeredSinks = sinks.stream()
        .map(NotificationSink::name)
        .filter(name -> name != null && !name.isBlank())
        .map(name -> name.trim().toLowerCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
    Set<String> unknownSinks = configuredSinks.stream()
        .filter(name -> !registeredSinks.contains(name))
        .collect(Collectors.toUnmodifiableSet());
    if (!unknownSinks.isEmpty()) {
      return new Readiness(
          ReadinessStatus.ACTION_REQUIRED,
          "Configured notification sinks are not available.",
          "Use registered sinks: " + registeredSinks.stream().sorted().collect(Collectors.joining(", ")) + ".");
    }

    if (configuredSinks.contains("webhook")) {
      NotificationProperties.Webhook webhook = properties.getWebhook();
      if (!webhook.isEnabled()) {
        return new Readiness(
            ReadinessStatus.ACTION_REQUIRED,
            "Webhook delivery is selected but disabled.",
            "Enable the webhook sink or remove it from notifications.sinks.");
      }
      if (isBlank(webhook.getUrl()) && isBlank(webhook.getUrlEnv())) {
        return new Readiness(
            ReadinessStatus.ACTION_REQUIRED,
            "Webhook delivery is selected but has no URL source.",
            "Set a webhook URL through a secret environment variable reference.");
      }
    }

    return new Readiness(
        ReadinessStatus.READY,
        "Notifications are configured for "
            + configuredSinks.stream().sorted().collect(Collectors.joining(", ")) + ".",
        "Delivery history is not persisted yet; use service logs for current delivery failures.");
  }

  private void deliverSafely(NotificationEvent event, NotificationSink sink) {
    int maxAttempts = Math.max(1, properties.getRetry().getMaxAttempts());
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        sink.deliver(event);
        return;
      } catch (RuntimeException ex) {
        LOGGER.warn(
            "event=notification_delivery_failed component=notification_service event_id={} event_type={} sink={} attempt={} max_attempts={} will_retry={} error_type={} error_message={}",
            event.eventId(),
            event.eventType(),
            sink.name(),
            attempt,
            maxAttempts,
            attempt < maxAttempts,
            ex.getClass().getSimpleName(),
            safeMessage(ex));
      }
    }
  }

  private String safeMessage(RuntimeException ex) {
    if (ex.getMessage() == null || ex.getMessage().isBlank()) {
      return ex.getClass().getSimpleName();
    }
    return redactSecrets(ex.getMessage());
  }

  private String redactSecrets(String message) {
    return message
        .replaceAll("(?i)(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+", "$1 [REDACTED]")
        .replaceAll(
            "(?i)(authorization|password|secret|token|api[-_]?key|access[-_]?key)(\\s*[:=]\\s*)[^\\s,;]+",
            "$1$2[REDACTED]");
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  public record Readiness(ReadinessStatus status, String detail, String action) {}

  public enum ReadinessStatus {
    READY,
    DISABLED,
    ACTION_REQUIRED
  }
}
