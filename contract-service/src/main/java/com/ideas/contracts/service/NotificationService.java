package com.ideas.contracts.service;

import java.util.List;
import java.util.Locale;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {
  private static final Logger LOGGER = LoggerFactory.getLogger(NotificationService.class);

  private final NotificationProperties properties;
  private final List<NotificationSink> sinks;
  private final MetadataStore metadataStore;

  public NotificationService(
      NotificationProperties properties,
      List<NotificationSink> sinks,
      MetadataStore metadataStore) {
    this.properties = properties;
    this.sinks = List.copyOf(sinks);
    this.metadataStore = metadataStore;
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
      try {
        MetadataStore.NotificationEnqueueResult result =
            metadataStore.enqueueNotificationDelivery(event, sinkName);
        if (!result.created()) {
          LOGGER.debug(
              "event=notification_delivery_deduplicated component=notification_service event_id={} sink={} delivery_id={}",
              event.eventId(),
              sinkName,
              result.delivery().deliveryId());
        }
      } catch (RuntimeException error) {
        LOGGER.warn(
            "event=notification_enqueue_failed component=notification_service event_id={} event_type={} sink={} error_type={} error_message={}",
            event.eventId(),
            event.eventType(),
            sinkName,
            error.getClass().getSimpleName(),
            NotificationRedactor.safeFailureMessage(error));
      }
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

    try {
      List<NotificationDelivery> deliveries = metadataStore.listNotificationDeliveries(20);
      if (deliveries.stream().anyMatch(delivery ->
          delivery.status() == NotificationDeliveryStatus.FAILED_PERMANENT)) {
        return new Readiness(
            ReadinessStatus.ACTION_REQUIRED,
            "At least one notification delivery failed permanently.",
            "Inspect notification delivery history and fix the sink configuration before retrying.");
      }
      if (deliveries.stream().anyMatch(delivery ->
          delivery.status() == NotificationDeliveryStatus.FAILED_RETRYABLE
              || delivery.status() == NotificationDeliveryStatus.IN_FLIGHT)) {
        return new Readiness(
            ReadinessStatus.DEGRADED,
            "Notification delivery is waiting for a retry or is in progress.",
            "Inspect notification delivery history; retries use bounded backoff.");
      }
      if (deliveries.stream().anyMatch(delivery ->
          delivery.status() == NotificationDeliveryStatus.DELIVERED)) {
        return new Readiness(
            ReadinessStatus.READY,
            "Notifications are configured and recent delivery succeeded.",
            "Review authenticated delivery history when investigating an alert.");
      }
    } catch (RuntimeException error) {
      return new Readiness(
          ReadinessStatus.ACTION_REQUIRED,
          "Notification delivery history is unavailable.",
          "Restore metadata-store access before relying on operational alerts.");
    }

    return new Readiness(
        ReadinessStatus.READY,
        "Notifications are configured for "
            + configuredSinks.stream().sorted().collect(Collectors.joining(", ")) + ".",
        "No notification events have been queued in this environment yet.");
  }

  public List<NotificationDelivery> recentDeliveries(int limit) {
    return metadataStore.listNotificationDeliveries(limit);
  }

  @Scheduled(fixedDelayString = "${notifications.dispatch.poll-interval-ms:5000}")
  public void dispatchPendingDeliveries() {
    if (!properties.isEnabled()) {
      return;
    }

    int maxPerPoll = Math.max(1, properties.getDispatch().getMaxPerPoll());
    for (int processed = 0; processed < maxPerPoll; processed++) {
      Instant now = Instant.now();
      NotificationDelivery delivery = metadataStore.claimNextNotificationDelivery(
          now, now.minus(claimTimeout())).orElse(null);
      if (delivery == null) {
        return;
      }
      deliverClaimed(delivery);
    }
  }

  private void deliverClaimed(NotificationDelivery delivery) {
    NotificationSink sink = sinks.stream()
        .filter(candidate -> delivery.sinkName().equalsIgnoreCase(candidate.name()))
        .findFirst()
        .orElse(null);
    if (sink == null) {
      markDeliveryFailed(delivery, "Configured notification sink is not registered.");
      return;
    }

    try {
      sink.deliver(delivery.event());
      if (!metadataStore.markNotificationDeliveryDelivered(delivery.deliveryId(), Instant.now())) {
        LOGGER.warn(
            "event=notification_delivery_completion_skipped component=notification_service delivery_id={} sink={}",
            delivery.deliveryId(),
            delivery.sinkName());
      }
    } catch (RuntimeException error) {
      markDeliveryFailed(delivery, NotificationRedactor.safeFailureMessage(error));
    }
  }

  private void markDeliveryFailed(NotificationDelivery delivery, String failureMessage) {
    int maxAttempts = Math.max(1, properties.getRetry().getMaxAttempts());
    boolean permanentlyFailed = delivery.attemptCount() >= maxAttempts;
    Instant nextAttemptAt = permanentlyFailed ? null : Instant.now().plus(retryDelay(delivery.attemptCount()));
    boolean updated = metadataStore.markNotificationDeliveryFailed(
        delivery.deliveryId(), failureMessage, nextAttemptAt, permanentlyFailed);
    LOGGER.warn(
        "event=notification_delivery_failed component=notification_service delivery_id={} event_id={} event_type={} sink={} attempt={} max_attempts={} will_retry={} error_message={}",
        delivery.deliveryId(),
        delivery.event().eventId(),
        delivery.event().eventType(),
        delivery.sinkName(),
        delivery.attemptCount(),
        maxAttempts,
        !permanentlyFailed,
        failureMessage);
    if (!updated) {
      LOGGER.warn(
          "event=notification_delivery_failure_state_skipped component=notification_service delivery_id={}",
          delivery.deliveryId());
    }
  }

  private Duration retryDelay(int attemptCount) {
    Duration configured = properties.getRetry().getInitialDelay();
    Duration base = configured == null || configured.isZero() || configured.isNegative()
        ? Duration.ofSeconds(1)
        : configured;
    long multiplier = 1L << Math.min(Math.max(0, attemptCount - 1), 8);
    try {
      return base.multipliedBy(multiplier).compareTo(Duration.ofMinutes(15)) > 0
          ? Duration.ofMinutes(15)
          : base.multipliedBy(multiplier);
    } catch (ArithmeticException ignored) {
      return Duration.ofMinutes(15);
    }
  }

  private Duration claimTimeout() {
    Duration configured = properties.getDispatch().getClaimTimeout();
    if (configured == null || configured.isZero() || configured.isNegative()) {
      return Duration.ofMinutes(1);
    }
    return configured;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  public record Readiness(ReadinessStatus status, String detail, String action) {}

  public enum ReadinessStatus {
    READY,
    DISABLED,
    DEGRADED,
    ACTION_REQUIRED
  }
}
