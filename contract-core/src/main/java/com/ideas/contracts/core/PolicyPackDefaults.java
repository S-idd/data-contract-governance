package com.ideas.contracts.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class PolicyPackDefaults {
  public static final String BASELINE_NAME = "baseline";

  private static final Map<RuleId, RuleSeverity> BASELINE_RULES = buildBaselineRules();
  private static final PolicyPack BASELINE_PACK = new PolicyPack(BASELINE_NAME, BASELINE_RULES);

  private PolicyPackDefaults() {
  }

  public static Map<RuleId, RuleSeverity> baselineRules() {
    return BASELINE_RULES;
  }

  public static PolicyPack baselinePack() {
    return BASELINE_PACK;
  }

  private static Map<RuleId, RuleSeverity> buildBaselineRules() {
    EnumMap<RuleId, RuleSeverity> rules = new EnumMap<>(RuleId.class);
    rules.put(RuleId.FIELD_REMOVED, RuleSeverity.BREAKING);
    rules.put(RuleId.FIELD_TYPE_CHANGED, RuleSeverity.BREAKING);
    rules.put(RuleId.REQUIRED_FIELD_ADDED, RuleSeverity.BREAKING);
    rules.put(RuleId.ENUM_VALUE_REMOVED, RuleSeverity.BREAKING);
    rules.put(RuleId.ENUM_VALUE_ADDED, RuleSeverity.WARNING);
    return Collections.unmodifiableMap(rules);
  }
}
