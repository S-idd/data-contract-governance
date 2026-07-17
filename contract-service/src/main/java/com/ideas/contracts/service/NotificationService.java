package com.ideas.contracts.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
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
}
