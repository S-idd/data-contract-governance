package com.ideas.contracts.demo;

import java.util.Locale;

enum DemoCheckScenario {
  HAPPY("v1", "v2"),
  BREAKING("v2", "v3");

  private final String baseVersion;
  private final String candidateVersion;

  DemoCheckScenario(String baseVersion, String candidateVersion) {
    this.baseVersion = baseVersion;
    this.candidateVersion = candidateVersion;
  }

  String baseVersion() {
    return baseVersion;
  }

  String candidateVersion() {
    return candidateVersion;
  }

  static DemoCheckScenario parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("scenario must be happy or breaking.");
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("scenario must be happy or breaking.");
    }
  }
}
