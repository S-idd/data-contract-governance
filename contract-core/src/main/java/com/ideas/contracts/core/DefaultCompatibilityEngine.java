package com.ideas.contracts.core;

import java.util.ArrayList;
import java.util.List;

public class DefaultCompatibilityEngine implements CompatibilityEngine {
  private final DiffEngine diffEngine;
  private final RuleEngine ruleEngine;

  public DefaultCompatibilityEngine(DiffEngine diffEngine, RuleEngine ruleEngine) {
    this.diffEngine = diffEngine;
    this.ruleEngine = ruleEngine;
  }

  @Override
  public CompatibilityResult checkCompatibility(
      SchemaSnapshot base,
      SchemaSnapshot candidate,
      CompatibilityMode mode,
      PolicyPack policyPack) {
    if (mode == null) {
      throw new CompatibilityException("Compatibility mode is required.");
    }

    PolicyPack effectivePack = policyPack == null ? PolicyPackDefaults.baselinePack() : policyPack;
    List<String> breakingChanges = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    if (mode == CompatibilityMode.BACKWARD || mode == CompatibilityMode.FULL) {
      CompatibilityResult backward = ruleEngine.evaluateBackward(diffEngine.diff(base, candidate), effectivePack);
      breakingChanges.addAll(backward.breakingChanges());
      warnings.addAll(backward.warnings());
    }
    if (mode == CompatibilityMode.FORWARD || mode == CompatibilityMode.FULL) {
      CompatibilityResult forward = ruleEngine.evaluateBackward(diffEngine.diff(candidate, base), effectivePack);
      forward.breakingChanges().forEach(item -> breakingChanges.add("[FORWARD] " + item));
      forward.warnings().forEach(item -> warnings.add("[FORWARD] " + item));
    }

    return breakingChanges.isEmpty()
        ? new CompatibilityResult(CheckStatus.PASS, List.of(), warnings)
        : new CompatibilityResult(CheckStatus.FAIL, breakingChanges, warnings);
  }
}
