package com.ideas.contracts.service;

public enum NotificationDeliveryStatus {
  PENDING,
  IN_FLIGHT,
  DELIVERED,
  FAILED_RETRYABLE,
  FAILED_PERMANENT
}
