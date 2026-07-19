package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class WebhookNotificationSinkTest {
  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private final List<CapturedWebhookRequest> requests = new CopyOnWriteArrayList<>();
  private final AtomicInteger responseStatus = new AtomicInteger(200);
  private final AtomicReference<String> responseBody = new AtomicReference<>("");

  private HttpServer server;
  private String webhookUrl;

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/hook", this::handleWebhook);
    server.start();
    webhookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void postsConfiguredWebhookPayload() throws Exception {
    NotificationProperties properties = new NotificationProperties();
    properties.getWebhook().setEnabled(true);
    properties.getWebhook().setUrl(webhookUrl);
    properties.getPayload().setMaxBreakingChanges(1);

    WebhookNotificationSink sink = new WebhookNotificationSink(
        properties,
        new MockEnvironment(),
        objectMapper);

    sink.deliver(sampleEvent());

    assertEquals(1, requests.size());
    CapturedWebhookRequest request = requests.get(0);
    assertEquals("POST", request.method());
    assertTrue(request.contentType().contains("application/json"));

    JsonNode body = objectMapper.readTree(request.body());
    assertEquals("event-1", body.get("eventId").asText());
    assertEquals("CONTRACT_CHECK_FAILED", body.get("eventType").asText());
    assertEquals("HIGH", body.get("severity").asText());
    assertEquals("2026-05-23T00:00:00Z", body.get("occurredAt").asText());
    assertEquals("orders.created", body.get("contractId").asText());
    assertEquals("run-1", body.get("runId").asText());
    assertEquals("v1", body.get("baseVersion").asText());
    assertEquals("v2", body.get("candidateVersion").asText());
    assertEquals("baseline", body.get("policyPack").asText());
    assertEquals(1, body.get("breakingChanges").size());
    assertEquals("Field type changed: orderId", body.get("breakingChanges").get(0).asText());
    assertEquals(1, body.get("breakingChangesTruncated").asInt());
    assertEquals("/checks/run-1", body.get("links").get("checkRun").asText());
    assertEquals("CONTRACT_CHECK_FAILED:orders.created:abc123:v1:v2", body.get("dedupeKey").asText());
  }

  @Test
  void resolvesUrlAndAuthHeaderFromEnvironment() {
    NotificationProperties properties = new NotificationProperties();
    properties.getWebhook().setEnabled(true);
    properties.getWebhook().setUrlEnv("DCG_TEST_WEBHOOK_URL");
    properties.getWebhook().setAuthHeaderEnv("DCG_TEST_WEBHOOK_AUTH");
    MockEnvironment environment = new MockEnvironment()
        .withProperty("DCG_TEST_WEBHOOK_URL", webhookUrl)
        .withProperty("DCG_TEST_WEBHOOK_AUTH", "Bearer test-token");

    WebhookNotificationSink sink = new WebhookNotificationSink(properties, environment, objectMapper);

    sink.deliver(sampleEvent());

    assertEquals(1, requests.size());
    assertEquals("Bearer test-token", requests.get(0).authorization());
  }

  @Test
  void disabledWebhookSinkDoesNothing() {
    NotificationProperties properties = new NotificationProperties();
    properties.getWebhook().setEnabled(false);
    properties.getWebhook().setUrl(webhookUrl);

    WebhookNotificationSink sink = new WebhookNotificationSink(
        properties,
        new MockEnvironment(),
        objectMapper);

    sink.deliver(sampleEvent());

    assertEquals(0, requests.size());
  }

  @Test
  void nonSuccessResponseThrowsWithoutLeakingResponseBody() {
    responseStatus.set(500);
    responseBody.set("secret-token-from-downstream");
    NotificationProperties properties = new NotificationProperties();
    properties.getWebhook().setEnabled(true);
    properties.getWebhook().setUrl(webhookUrl);

    WebhookNotificationSink sink = new WebhookNotificationSink(
        properties,
        new MockEnvironment(),
        objectMapper);

    IllegalStateException exception = assertThrows(
        IllegalStateException.class,
        () -> sink.deliver(sampleEvent()));

    assertTrue(exception.getMessage().contains("HTTP status 500"));
    assertFalse(exception.getMessage().contains("secret-token-from-downstream"));
  }

  @Test
  void configuredButMissingUrlEnvThrowsSafeMessage() {
    NotificationProperties properties = new NotificationProperties();
    properties.getWebhook().setEnabled(true);
    properties.getWebhook().setUrlEnv("DCG_MISSING_WEBHOOK_URL");

    WebhookNotificationSink sink = new WebhookNotificationSink(
        properties,
        new MockEnvironment(),
        objectMapper);

    IllegalStateException exception = assertThrows(
        IllegalStateException.class,
        () -> sink.deliver(sampleEvent()));

    assertEquals("webhook URL environment variable is configured but not set.", exception.getMessage());
  }

  private void handleWebhook(HttpExchange exchange) throws java.io.IOException {
    String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    requests.add(new CapturedWebhookRequest(
        exchange.getRequestMethod(),
        exchange.getRequestHeaders().getFirst("Content-Type"),
        exchange.getRequestHeaders().getFirst("Authorization"),
        requestBody));

    byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(responseStatus.get(), body.length);
    try (var response = exchange.getResponseBody()) {
      response.write(body);
    }
  }

  private NotificationEvent sampleEvent() {
    return new NotificationEvent(
        "event-1",
        NotificationEventType.CONTRACT_CHECK_FAILED,
        NotificationSeverity.HIGH,
        Instant.parse("2026-05-23T00:00:00Z"),
        "orders.created",
        "run-1",
        "v1",
        "v2",
        "abc123",
        "ci",
        "baseline",
        "Compatibility check failed.",
        List.of("Field type changed: orderId", "Required field removed: status"),
        List.of("Optional field added: region"),
        Map.of("checkRun", "/checks/run-1"),
        "CONTRACT_CHECK_FAILED:orders.created:abc123:v1:v2");
  }

  private record CapturedWebhookRequest(
      String method,
      String contentType,
      String authorization,
      String body) {
  }
}
