package com.ideas.contracts.service;

import java.time.Instant;

/** A persisted delivery attempt for one notification event and one configured sink. */
public record NotificationDelivery(
    String deliveryId,
    NotificationEvent event,
    String sinkName,
    NotificationDeliveryStatus status,
    int attemptCount,
    Instant createdAt,
    Instant lastAttemptAt,
    Instant deliveredAt,
    Instant nextAttemptAt,
    String failureMessage) {

  public NotificationDelivery {
    if (deliveryId == null || deliveryId.isBlank()) {
      throw new IllegalArgumentException("deliveryId must not be blank.");
    }
    if (event == null) {
      throw new IllegalArgumentException("event must not be null.");
    }
    if (sinkName == null || sinkName.isBlank()) {
      throw new IllegalArgumentException("sinkName must not be blank.");
    }
    sinkName = sinkName.trim();
    status = status == null ? NotificationDeliveryStatus.PENDING : status;
    attemptCount = Math.max(0, attemptCount);
    createdAt = createdAt == null ? Instant.now() : createdAt;
    failureMessage = failureMessage == null || failureMessage.isBlank() ? null : failureMessage.trim();
  }
}
