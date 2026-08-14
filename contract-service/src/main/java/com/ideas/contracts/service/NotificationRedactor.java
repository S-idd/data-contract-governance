package com.ideas.contracts.service;

final class NotificationRedactor {
  private NotificationRedactor() {}

  static String safeFailureMessage(RuntimeException error) {
    if (error == null) {
      return "Notification delivery failed.";
    }
    String message = error.getMessage();
    if (message == null || message.isBlank()) {
      return error.getClass().getSimpleName();
    }
    return message
        .replaceAll(
            "(?i)(authorization|password|secret|token|api[-_]?key|access[-_]?key)"
                + "(\\s*[:=]\\s*)(?:(?:bearer|basic)\\s+)?[^\\s,;]+",
            "$1$2[REDACTED]")
        .replaceAll("(?i)(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+", "$1 [REDACTED]");
  }
}
