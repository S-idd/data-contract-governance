package com.ideas.contracts.core;

import java.util.ArrayList;
import java.util.List;

public class DefaultRuleEngine implements RuleEngine {
  @Override
  public CompatibilityResult evaluateBackward(SchemaDiff diff, PolicyPack policyPack) {
    if (diff == null) {
      throw new CompatibilityException("Schema diff must not be null.");
    }

    PolicyPack effectivePack = policyPack == null ? PolicyPackDefaults.baselinePack() : policyPack;
    List<String> breakingChanges = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    applyRule(effectivePack, RuleId.FIELD_REMOVED, diff.fieldRemoved(), "Field removed: ", breakingChanges, warnings);
    applyRule(
        effectivePack,
        RuleId.FIELD_TYPE_CHANGED,
        diff.typeChanged(),
        "Field type changed: ",
        breakingChanges,
        warnings);
    applyRule(
        effectivePack,
        RuleId.REQUIRED_FIELD_ADDED,
        diff.requiredAdded(),
        "Required field added: ",
        breakingChanges,
        warnings);
    applyRule(
        effectivePack,
        RuleId.ENUM_VALUE_REMOVED,
        diff.enumRemoved(),
        "Enum value removed: ",
        breakingChanges,
        warnings);
    applyRule(
        effectivePack,
        RuleId.ENUM_VALUE_ADDED,
        diff.enumAdded(),
        "Enum value added: ",
        breakingChanges,
        warnings);

    return breakingChanges.isEmpty()
        ? new CompatibilityResult(CheckStatus.PASS, List.of(), warnings)
        : new CompatibilityResult(CheckStatus.FAIL, breakingChanges, warnings);
  }

  private void applyRule(
      PolicyPack policyPack,
      RuleId ruleId,
      List<String> changes,
      String label,
      List<String> breakingChanges,
      List<String> warnings) {
    if (changes == null || changes.isEmpty()) {
      return;
    }
    RuleSeverity severity = policyPack.severityFor(ruleId);
    if (severity == RuleSeverity.IGNORE) {
      return;
    }
    for (String change : changes) {
      String message = label + change;
      if (severity == RuleSeverity.BREAKING) {
        breakingChanges.add(message);
      } else {
        warnings.add(message);
      }
    }
  }
}
