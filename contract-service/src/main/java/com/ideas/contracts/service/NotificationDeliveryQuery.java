package com.ideas.contracts.service;

import java.util.Locale;

public record NotificationDeliveryQuery(
    String status,
    String contractId,
    String sinkName,
    String eventType,
    String runId,
    int limit) {
  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 100;

  public static NotificationDeliveryQuery from(
      String status,
      String contractId,
      String sinkName,
      String eventType,
      Integer limit) {
    return from(status, contractId, sinkName, eventType, null, limit);
  }

  public static NotificationDeliveryQuery from(
      String status,
      String contractId,
      String sinkName,
      String eventType,
      String runId,
      Integer limit) {
    int resolvedLimit = limit == null ? DEFAULT_LIMIT : limit;
    return new NotificationDeliveryQuery(status, contractId, sinkName, eventType, runId, resolvedLimit);
  }

  public NotificationDeliveryQuery {
    status = normalizeEnumValue(status, "status");
    eventType = normalizeEnumValue(eventType, "eventType");
    contractId = trimToNull(contractId);
    sinkName = normalizeSinkName(sinkName);
    runId = trimToNull(runId);
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ".");
    }
  }

  private static String normalizeEnumValue(String value, String fieldName) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    String upper = normalized.toUpperCase(Locale.ROOT);
    if (!upper.matches("^[A-Z_]+$")) {
      throw new IllegalArgumentException(fieldName + " must contain only letters and underscores.");
    }
    return upper;
  }

  private static String normalizeSinkName(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    String lower = normalized.toLowerCase(Locale.ROOT);
    if (!lower.matches("^[a-z0-9_-]+$")) {
      throw new IllegalArgumentException("sink must contain only letters, numbers, underscores, or dashes.");
    }
    return lower;
  }

  private static String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
