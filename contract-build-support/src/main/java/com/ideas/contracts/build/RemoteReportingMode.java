package com.ideas.contracts.build;

import java.util.Locale;

/** Controls what a build does when the optional DCG follow-up endpoint is unavailable. */
public enum RemoteReportingMode {
  DISABLED,
  OPTIONAL,
  REQUIRED;

  public static RemoteReportingMode parse(String value) {
    if (value == null || value.isBlank()) {
      return DISABLED;
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("remoteReportingMode must be DISABLED, OPTIONAL, or REQUIRED.");
    }
  }
}
