package com.ideas.contracts.core;

public interface RuleEngine {
  CompatibilityResult evaluateBackward(SchemaDiff diff, PolicyPack policyPack);
}
