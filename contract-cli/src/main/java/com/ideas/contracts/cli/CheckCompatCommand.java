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

  @CommandLine.Option(
      names = "--record-db",
      description = "Optional SQLite path to persist this check result")
  private Path recordDbPath;

  @CommandLine.Option(
      names = "--contract-id",
      description = "Contract ID used for persistence (required with --record-db)")
  private String contractId;

  @CommandLine.Option(
      names = "--commit-sha",
      defaultValue = "",
      description = "Commit SHA used for persistence")
  private String commitSha;

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
      persistIfRequested(result);
      return result.status() == CheckStatus.PASS ? 0 : 1;
    } catch (IllegalStateException ex) {
      System.err.println("Compatibility check failed: " + ex.getMessage());
      return 2;
    }
  }

  private void persistIfRequested(CompatibilityResult result) {
    if (recordDbPath == null) {
      return;
    }
    if (contractId == null || contractId.isBlank()) {
      throw new IllegalStateException("--contract-id is required when --record-db is used.");
    }
    String baseVersion = versionName(baseSchema);
    String candidateVersion = versionName(candidateSchema);
    new CheckRunRecorder().record(recordDbPath, contractId, baseVersion, candidateVersion, result, commitSha);
  }

  private String versionName(Path path) {
    String name = path.getFileName().toString();
    return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
  }
}
