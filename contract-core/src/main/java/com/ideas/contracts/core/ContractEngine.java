package com.ideas.contracts.core;

import java.nio.file.Path;

public interface ContractEngine {
  void lint(Path contractDirectory);

  String diff(Path baseSchema, Path candidateSchema);

  CompatibilityResult checkCompatibility(Path baseSchema, Path candidateSchema, CompatibilityMode mode);
}
