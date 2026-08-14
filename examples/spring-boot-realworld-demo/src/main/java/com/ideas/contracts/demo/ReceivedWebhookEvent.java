package com.ideas.contracts.demo;

import java.time.Instant;

public record ReceivedWebhookEvent(
    String eventId,
    String eventType,
    String contractId,
    String runId,
    String summary,
    Instant receivedAt
) {}
