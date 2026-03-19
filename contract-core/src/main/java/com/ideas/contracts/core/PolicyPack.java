package com.ideas.contracts.core;

import java.util.Map;

public record PolicyPack(String name, Map<RuleId, RuleSeverity> rules) {
  public RuleSeverity severityFor(RuleId ruleId) {
    if (rules == null || ruleId == null) {
      return RuleSeverity.BREAKING;
    }
    return rules.getOrDefault(ruleId, RuleSeverity.BREAKING);
  }
}
