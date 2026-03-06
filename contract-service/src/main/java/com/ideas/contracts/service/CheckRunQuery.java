package com.ideas.contracts.service;

import java.util.Locale;

public record CheckRunQuery(
    String contractId,
    String commitSha,
    String status,
    int limit,
    int offset) {
  public static final int DEFAULT_LIMIT = 50;
  public static final int DEFAULT_OFFSET = 0;
  public static final int MAX_LIMIT = 200;

  public static CheckRunQuery from(
      String contractId,
      String commitSha,
      String status,
      Integer limit,
      Integer offset) {
    int resolvedLimit = limit == null ? DEFAULT_LIMIT : limit;
    int resolvedOffset = offset == null ? DEFAULT_OFFSET : offset;
    return new CheckRunQuery(contractId, commitSha, status, resolvedLimit, resolvedOffset);
  }

  public CheckRunQuery {
    contractId = trimToNull(contractId);
    commitSha = trimToNull(commitSha);
    status = normalizeStatus(status);
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ".");
    }
    if (offset < 0) {
      throw new IllegalArgumentException("offset must be greater than or equal to 0.");
    }
  }

  private static String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private static String normalizeStatus(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    String upper = normalized.toUpperCase(Locale.ROOT);
    if (!upper.matches("^[A-Z_]+$")) {
      throw new IllegalArgumentException("status must contain only letters and underscores.");
    }
    return upper;
  }
}
