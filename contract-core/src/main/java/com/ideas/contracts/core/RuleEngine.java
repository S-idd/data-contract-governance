package com.ideas.contracts.core;

public interface RuleEngine {
  CompatibilityResult evaluateBackward(SchemaDiff diff, PolicyPack policyPack);

  CompatibilityResult evaluateForward(
      SchemaDiff diff,
      SchemaDiff reversedDiff,
      SchemaSnapshot oldConsumer,
      PolicyPack policyPack);
}
