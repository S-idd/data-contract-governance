package com.ideas.contracts.demo;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demo/webhooks")
class DemoWebhookController {
  private final DemoWebhookInbox inbox;

  DemoWebhookController(DemoWebhookInbox inbox) {
    this.inbox = inbox;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  void receive(@RequestBody JsonNode payload) {
    inbox.record(payload);
  }

  @GetMapping
  WebhookEventsResponse list() {
    List<ReceivedWebhookEvent> events = inbox.events();
    return new WebhookEventsResponse(events.size(), events);
  }

  record WebhookEventsResponse(int count, List<ReceivedWebhookEvent> events) {}
}
