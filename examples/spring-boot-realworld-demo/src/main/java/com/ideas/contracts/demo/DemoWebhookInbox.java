package com.ideas.contracts.demo;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
class DemoWebhookInbox {
  private static final int MAX_EVENTS = 100;

  private final Deque<ReceivedWebhookEvent> events = new ArrayDeque<>();

  synchronized void record(JsonNode payload) {
    events.addFirst(new ReceivedWebhookEvent(
        text(payload, "eventId"),
        text(payload, "eventType"),
        text(payload, "contractId"),
        text(payload, "runId"),
        text(payload, "summary"),
        Instant.now()));
    while (events.size() > MAX_EVENTS) {
      events.removeLast();
    }
  }

  synchronized List<ReceivedWebhookEvent> events() {
    return List.copyOf(new ArrayList<>(events));
  }

  synchronized void clear() {
    events.clear();
  }

  private String text(JsonNode payload, String field) {
    JsonNode value = payload == null ? null : payload.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }
}
