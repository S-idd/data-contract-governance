package com.ideas.contracts.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
class WebhookNotificationSink implements NotificationSink {
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

  private final NotificationProperties properties;
  private final Environment environment;
  private final ObjectMapper objectMapper;

  WebhookNotificationSink(
      NotificationProperties properties,
      Environment environment,
      ObjectMapper objectMapper) {
    this.properties = properties;
    this.environment = environment;
    this.objectMapper = objectMapper;
  }

  @Override
  public String name() {
    return "webhook";
  }

  @Override
  public void deliver(NotificationEvent event) {
    NotificationProperties.Webhook webhook = properties.getWebhook();
    if (!webhook.isEnabled()) {
      return;
    }

    URI target = resolveWebhookUri(webhook);
    Duration timeout = positiveTimeout(webhook.getTimeout());
    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(target)
        .timeout(timeout)
        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .POST(HttpRequest.BodyPublishers.ofString(toJson(event)));

    String authHeader = resolveOptionalEnv(webhook.getAuthHeaderEnv(), "webhook auth header");
    if (authHeader != null) {
      requestBuilder.header(HttpHeaders.AUTHORIZATION, authHeader);
    }

    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .build();
    try {
      HttpResponse<String> response = client.send(
          requestBuilder.build(),
          HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "Webhook delivery returned HTTP status " + response.statusCode() + ".");
      }
    } catch (IOException ex) {
      throw new IllegalStateException("Webhook delivery failed: " + ex.getClass().getSimpleName(), ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Webhook delivery was interrupted.", ex);
    }
  }

  private URI resolveWebhookUri(NotificationProperties.Webhook webhook) {
    String urlEnv = normalize(webhook.getUrlEnv());
    String url = urlEnv == null
        ? normalize(webhook.getUrl())
        : resolveRequiredEnv(urlEnv, "webhook URL");
    if (url == null) {
      throw new IllegalStateException("Webhook URL is required when webhook notifications are enabled.");
    }

    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException("Webhook URL is not a valid URI.", ex);
    }

    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!List.of("http", "https").contains(scheme) || uri.getHost() == null) {
      throw new IllegalStateException("Webhook URL must be an absolute HTTP or HTTPS URI.");
    }
    return uri;
  }

  private String toJson(NotificationEvent event) {
    try {
      return objectMapper.writeValueAsString(payload(event));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Webhook payload could not be serialized.", ex);
    }
  }

  private Map<String, Object> payload(NotificationEvent event) {
    int maxBreakingChanges = Math.max(0, properties.getPayload().getMaxBreakingChanges());
    List<String> breakingChanges = event.breakingChanges().stream()
        .limit(maxBreakingChanges)
        .toList();
    int truncatedBreakingChanges = Math.max(0, event.breakingChanges().size() - breakingChanges.size());

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("eventId", event.eventId());
    payload.put("eventType", event.eventType());
    payload.put("severity", event.severity());
    payload.put("occurredAt", event.occurredAt().toString());
    payload.put("contractId", event.contractId());
    payload.put("runId", event.runId());
    payload.put("baseVersion", event.baseVersion());
    payload.put("candidateVersion", event.candidateVersion());
    payload.put("commitSha", event.commitSha());
    payload.put("triggeredBy", event.triggeredBy());
    payload.put("policyPack", event.policyPack());
    payload.put("summary", event.summary());
    payload.put("breakingChanges", breakingChanges);
    payload.put("breakingChangesTruncated", truncatedBreakingChanges);
    payload.put("warnings", event.warnings());
    payload.put("links", event.links());
    payload.put("dedupeKey", event.dedupeKey());
    return payload;
  }

  private Duration positiveTimeout(Duration timeout) {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      return DEFAULT_TIMEOUT;
    }
    return timeout;
  }

  private String resolveRequiredEnv(String name, String label) {
    String value = resolveOptionalEnv(name, label);
    if (value == null) {
      throw new IllegalStateException(label + " environment variable is configured but not set.");
    }
    return value;
  }

  private String resolveOptionalEnv(String name, String label) {
    String normalizedName = normalize(name);
    if (normalizedName == null) {
      return null;
    }
    String value = normalize(environment.getProperty(normalizedName));
    if (value == null) {
      value = normalize(System.getenv(normalizedName));
    }
    if (value == null) {
      throw new IllegalStateException(label + " environment variable is configured but not set.");
    }
    return value;
  }

  private String normalize(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
