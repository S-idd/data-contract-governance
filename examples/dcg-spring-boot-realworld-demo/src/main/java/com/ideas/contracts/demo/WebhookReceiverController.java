package com.ideas.contracts.demo;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demo/webhooks")
class WebhookReceiverController {
  private static final Logger LOGGER = LoggerFactory.getLogger(WebhookReceiverController.class);
  private final List<ReceivedWebhook> events = new CopyOnWriteArrayList<>();

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  void receive(@RequestBody JsonNode payload) {
    String eventId = value(payload, "eventId");
    String eventType = value(payload, "eventType");
    String contractId = value(payload, "contractId");
    events.add(new ReceivedWebhook(eventId, eventType, contractId, Instant.now().toString(), payload));
    LOGGER.info(
        "event=demo_webhook_received event_id={} event_type={} contract_id={}",
        eventId,
        eventType,
        contractId);
  }

  @GetMapping
  List<ReceivedWebhook> events() {
    return List.copyOf(events);
  }

  private String value(JsonNode payload, String field) {
    if (payload == null || !payload.hasNonNull(field)) {
      return "-";
    }
    return payload.get(field).asText("-");
  }

  record ReceivedWebhook(
      String eventId,
      String eventType,
      String contractId,
      String receivedAt,
      JsonNode payload) {}
}
