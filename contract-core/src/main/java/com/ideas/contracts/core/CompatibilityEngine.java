package com.ideas.contracts.core;

public interface CompatibilityEngine {
  CompatibilityResult checkCompatibility(
      SchemaSnapshot base,
      SchemaSnapshot candidate,
      CompatibilityMode mode,
      PolicyPack policyPack);
}
