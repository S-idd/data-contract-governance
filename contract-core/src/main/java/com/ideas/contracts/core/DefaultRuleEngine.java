package com.ideas.contracts.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    applyRule(
        effectivePack,
        RuleId.CONSTRAINT_TIGHTENED,
        diff.constraintTightened(),
        "Constraint tightened: ",
        breakingChanges,
        warnings);
    applyRule(
        effectivePack,
        RuleId.CONDITIONAL_RESTRICTION_ADDED,
        diff.conditionalRestrictionAdded(),
        "Conditional restriction added or changed: ",
        breakingChanges,
        warnings);
    applyRule(
        effectivePack,
        RuleId.SCHEMA_RESTRICTION_ADDED,
        diff.schemaRestrictionAdded(),
        "Schema restriction added or changed: ",
        breakingChanges,
        warnings);

    return breakingChanges.isEmpty()
        ? new CompatibilityResult(CheckStatus.PASS, List.of(), warnings)
        : new CompatibilityResult(CheckStatus.FAIL, breakingChanges, warnings);
  }

  @Override
  public CompatibilityResult evaluateForward(
      SchemaDiff diff,
      SchemaDiff reversedDiff,
      SchemaSnapshot oldConsumer,
      PolicyPack policyPack) {
    if (diff == null || reversedDiff == null || oldConsumer == null) {
      throw new CompatibilityException("Forward compatibility requires both diffs and the old consumer schema.");
    }

    Set<String> additions = new HashSet<>(diff.fieldAdded());
    List<String> reversedRemovals = reversedDiff.fieldRemoved().stream()
        .filter(field -> !additions.contains(field))
        .toList();
    SchemaDiff nonAdditionReversedDiff = new SchemaDiff(
        reversedDiff.fieldAdded(),
        reversedRemovals,
        reversedDiff.typeChanged(),
        reversedDiff.requiredAdded(),
        reversedDiff.requiredRemoved(),
        reversedDiff.enumAdded(),
        reversedDiff.enumRemoved(),
        reversedDiff.constraintTightened(),
        reversedDiff.conditionalRestrictionAdded(),
        reversedDiff.schemaRestrictionAdded());
    CompatibilityResult reversedResult = evaluateBackward(nonAdditionReversedDiff, policyPack);

    PolicyPack effectivePack = policyPack == null ? PolicyPackDefaults.baselinePack() : policyPack;
    List<String> breakingChanges = new ArrayList<>(reversedResult.breakingChanges());
    List<String> warnings = new ArrayList<>(reversedResult.warnings());
    for (String field : topLevelAdditions(diff.fieldAdded())) {
      String objectPath = parentObjectPath(field);
      String restriction = oldConsumer.schemaRestrictions().get(
          (objectPath.isEmpty() ? "root" : objectPath) + " additionalProperties");
      if ("false".equals(restriction)) {
        breakingChanges.add("Optional field added to closed consumer object: " + field);
      } else if (restriction != null && !"true".equals(restriction)) {
        breakingChanges.add("Optional field added to constrained consumer object: " + field);
      } else {
        applyRule(
            effectivePack,
            RuleId.FORWARD_OPTIONAL_FIELD_ADDED,
            List.of(field),
            "Optional field added to open consumer object: ",
            breakingChanges,
            warnings);
      }
    }

    return breakingChanges.isEmpty()
        ? new CompatibilityResult(CheckStatus.PASS, List.of(), warnings)
        : new CompatibilityResult(CheckStatus.FAIL, breakingChanges, warnings);
  }

  private List<String> topLevelAdditions(List<String> additions) {
    Set<String> added = new HashSet<>(additions);
    return additions.stream()
        .filter(field -> !hasAddedAncestor(field, added))
        .sorted()
        .toList();
  }

  private boolean hasAddedAncestor(String field, Set<String> additions) {
    String ancestor = field;
    while (!ancestor.isEmpty()) {
      if (ancestor.endsWith("[]")) {
        ancestor = ancestor.substring(0, ancestor.length() - 2);
      } else {
        int separator = ancestor.lastIndexOf('.');
        if (separator < 0) {
          return false;
        }
        ancestor = ancestor.substring(0, separator);
      }
      if (additions.contains(ancestor)) {
        return true;
      }
    }
    return false;
  }

  private String parentObjectPath(String field) {
    int separator = field.lastIndexOf('.');
    return separator < 0 ? "" : field.substring(0, separator);
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
