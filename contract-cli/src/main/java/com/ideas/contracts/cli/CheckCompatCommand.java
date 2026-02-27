package com.ideas.contracts.cli;

import com.ideas.contracts.core.CheckStatus;
import com.ideas.contracts.core.CompatibilityMode;
import com.ideas.contracts.core.CompatibilityResult;
import com.ideas.contracts.core.ContractEngine;
import com.ideas.contracts.core.DefaultContractEngine;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(name = "check-compat", description = "Check schema compatibility")
public class CheckCompatCommand implements Callable<Integer> {
  @CommandLine.Option(names = "--base", required = true, description = "Base schema file path")
  private Path baseSchema;

  @CommandLine.Option(names = "--candidate", required = true, description = "Candidate schema file path")
  private Path candidateSchema;

  @CommandLine.Option(
      names = "--mode",
      defaultValue = "BACKWARD",
      description = "Compatibility mode: ${COMPLETION-CANDIDATES}")
  private CompatibilityMode mode;

  @Override
  public Integer call() {
    ContractEngine engine = new DefaultContractEngine();
    try {
      CompatibilityResult result = engine.checkCompatibility(baseSchema, candidateSchema, mode);
      System.out.println("Status: " + result.status());
      if (!result.breakingChanges().isEmpty()) {
        System.out.println("Breaking changes: " + result.breakingChanges());
      }
      if (!result.warnings().isEmpty()) {
        System.out.println("Warnings: " + result.warnings());
      }
      return result.status() == CheckStatus.PASS ? 0 : 1;
    } catch (IllegalStateException ex) {
      System.err.println("Compatibility check failed: " + ex.getMessage());
      return 2;
    }
  }
}
