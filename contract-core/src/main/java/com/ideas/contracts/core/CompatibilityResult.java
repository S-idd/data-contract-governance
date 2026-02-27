package com.ideas.contracts.core;

import java.util.List;

public record CompatibilityResult(
    CheckStatus status,
    List<String> breakingChanges,
    List<String> warnings
) {
  public static CompatibilityResult pass() {
    return new CompatibilityResult(CheckStatus.PASS, List.of(), List.of());
  }
}
