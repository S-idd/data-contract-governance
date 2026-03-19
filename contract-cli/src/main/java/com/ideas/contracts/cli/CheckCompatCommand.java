package com.ideas.contracts.cli;

import com.ideas.contracts.core.CheckStatus;
import com.ideas.contracts.core.CompatibilityMode;
import com.ideas.contracts.core.CompatibilityResult;
import com.ideas.contracts.core.ContractEngine;
import com.ideas.contracts.core.DefaultContractEngine;
import com.ideas.contracts.core.PolicyPack;
import com.ideas.contracts.core.PolicyPackConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "check-compat",
    description = "Check schema compatibility",
    mixinStandardHelpOptions = true)
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
      names = "--record-jdbc-url",
      description = "Optional JDBC URL to persist this check result (for example: jdbc:postgresql://localhost:5432/contracts)")
  private String recordJdbcUrl;

  @CommandLine.Option(
      names = "--record-db-user",
      description = "Optional database username used with --record-jdbc-url")
  private String recordDbUser;

  @CommandLine.Option(
      names = "--record-db-user-env",
      description = "Optional environment variable name that holds database username used with --record-jdbc-url")
  private String recordDbUserEnv;

  @CommandLine.Option(
      names = "--record-db-password",
      description = "Optional database password used with --record-jdbc-url")
  private String recordDbPassword;

  @CommandLine.Option(
      names = "--record-db-password-env",
      description = "Optional environment variable name that holds database password used with --record-jdbc-url")
  private String recordDbPasswordEnv;

  @CommandLine.Option(
      names = "--contract-id",
      description = "Contract ID used for persistence (required with --record-db or --record-jdbc-url)")
  private String contractId;

  @CommandLine.Option(
      names = "--commit-sha",
      defaultValue = "",
      description = "Commit SHA used for persistence")
  private String commitSha;

  @Override
  public Integer call() {
    ContractEngine engine = new DefaultContractEngine();
    CompatibilityResult result;
    try {
      PolicyPack policyPack = resolvePolicyPack();
      result = engine.checkCompatibility(baseSchema, candidateSchema, mode, policyPack);
    } catch (IllegalStateException ex) {
      System.err.println("Schema compatibility check failed: " + ex.getMessage());
      return 2;
    }

    System.out.println("Schema compatibility: " + result.status());
    if (!result.breakingChanges().isEmpty()) {
      System.out.println("Breaking changes: " + result.breakingChanges());
    }
    if (!result.warnings().isEmpty()) {
      System.out.println("Warnings: " + result.warnings());
    }

    try {
      if (persistIfRequested(result)) {
        System.out.println("Persistence: RECORDED");
      }
    } catch (IllegalStateException ex) {
      System.err.println("Persistence failed: " + ex.getMessage());
      return 2;
    }

    return result.status() == CheckStatus.PASS ? 0 : 1;
  }

  private PolicyPack resolvePolicyPack() {
    Path contractDir = resolveContractDir();
    Path configPath = resolvePolicyPackConfigPath(contractDir);
    PolicyPackConfig config = PolicyPackConfig.load(configPath);
    String packName = readPolicyPackName(contractDir);
    return config.resolve(packName);
  }

  private Path resolveContractDir() {
    Path baseDir = baseSchema == null ? null : baseSchema.toAbsolutePath().getParent();
    Path candidateDir = candidateSchema == null ? null : candidateSchema.toAbsolutePath().getParent();
    if (baseDir != null && candidateDir != null && baseDir.equals(candidateDir)) {
      return baseDir;
    }
    return baseDir != null ? baseDir : candidateDir;
  }

  private Path resolvePolicyPackConfigPath(Path contractDir) {
    if (contractDir == null) {
      return null;
    }
    Path root = contractDir.getParent();
    if (root == null) {
      return null;
    }
    return root.resolve("policy-packs.json");
  }

  private String readPolicyPackName(Path contractDir) {
    if (contractDir == null) {
      return null;
    }
    Path metadataPath = contractDir.resolve("metadata.yaml");
    if (!Files.exists(metadataPath)) {
      return null;
    }
    try {
      List<String> lines = Files.readAllLines(metadataPath, StandardCharsets.UTF_8);
      for (String rawLine : lines) {
        String line = rawLine.trim();
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        int separator = line.indexOf(':');
        if (separator <= 0) {
          continue;
        }
        String key = line.substring(0, separator).trim();
        String value = line.substring(separator + 1).trim();
        if ("policyPack".equals(key) && !value.isBlank()) {
          return value;
        }
      }
      return null;
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to read metadata.yaml for policy pack.", ex);
    }
  }

  private boolean persistIfRequested(CompatibilityResult result) {
    boolean persistToSqlite = recordDbPath != null;
    boolean persistToJdbc = recordJdbcUrl != null && !recordJdbcUrl.isBlank();
    if (!persistToSqlite && !persistToJdbc) {
      return false;
    }
    if (persistToSqlite && persistToJdbc) {
      throw new IllegalStateException("Use either --record-db or --record-jdbc-url, not both.");
    }
    if (contractId == null || contractId.isBlank()) {
      throw new IllegalStateException("--contract-id is required when check recording is enabled.");
    }

    String baseVersion = versionName(baseSchema);
    String candidateVersion = versionName(candidateSchema);
    CheckRunRecorder recorder = createRecorder();

    if (persistToSqlite) {
      recorder.record(recordDbPath, contractId, baseVersion, candidateVersion, result, commitSha, mode);
      return true;
    }

    String resolvedUsername = resolveCredential(recordDbUser, recordDbUserEnv, "--record-db-user-env");
    String resolvedPassword =
        resolveCredential(recordDbPassword, recordDbPasswordEnv, "--record-db-password-env");

    recorder.record(
        recordJdbcUrl.trim(),
        resolvedUsername,
        resolvedPassword,
        contractId,
        baseVersion,
        candidateVersion,
        result,
        commitSha,
        mode);
    return true;
  }

  private String versionName(Path path) {
    String name = path.getFileName().toString();
    return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
  }

  private String normalizeCredential(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private String resolveCredential(String value, String envVarName, String envOptionName) {
    String normalizedValue = normalizeCredential(value);
    if (normalizedValue != null) {
      return normalizedValue;
    }

    String normalizedEnvVarName = normalizeCredential(envVarName);
    if (normalizedEnvVarName == null) {
      return null;
    }

    String envValue = readEnvironmentVariable(normalizedEnvVarName);
    if (envValue == null || envValue.isBlank()) {
      throw new IllegalStateException(
          "Environment variable '" + normalizedEnvVarName + "' configured via "
              + envOptionName
              + " is not set or blank.");
    }
    return envValue.trim();
  }

  protected CheckRunRecorder createRecorder() {
    return new CheckRunRecorder();
  }

  protected String readEnvironmentVariable(String key) {
    return System.getenv(key);
  }
}
