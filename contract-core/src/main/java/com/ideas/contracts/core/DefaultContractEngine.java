package com.ideas.contracts.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class DefaultContractEngine implements ContractEngine {
  private final SchemaLoader schemaLoader;
  private final SchemaValidator schemaValidator;
  private final DiffEngine diffEngine;
  private final CompatibilityEngine compatibilityEngine;
  private final RuleEngine ruleEngine;

  public DefaultContractEngine() {
    this(
        new DefaultSchemaLoader(),
        new DefaultSchemaValidator(),
        new DefaultDiffEngine(),
        new DefaultRuleEngine());
  }

  public DefaultContractEngine(
      SchemaLoader schemaLoader,
      SchemaValidator schemaValidator,
      DiffEngine diffEngine,
      RuleEngine ruleEngine) {
    this.schemaLoader = schemaLoader;
    this.schemaValidator = schemaValidator;
    this.diffEngine = diffEngine;
    this.ruleEngine = ruleEngine;
    this.compatibilityEngine = new DefaultCompatibilityEngine(diffEngine, ruleEngine);
  }

  public SchemaLoader schemaLoader() {
    return schemaLoader;
  }

  public SchemaValidator schemaValidator() {
    return schemaValidator;
  }

  public DiffEngine diffEngine() {
    return diffEngine;
  }

  public CompatibilityEngine compatibilityEngine() {
    return compatibilityEngine;
  }

  public RuleEngine ruleEngine() {
    return ruleEngine;
  }

  @Override
  public void lint(Path contractDirectory) {
    try {
      schemaValidator.validateContractDirectory(contractDirectory);
      String contractId = contractDirectory.getFileName().toString();
      ContractMetadata metadata = schemaLoader.loadMetadata(contractDirectory.resolve("metadata.yaml"), contractId);
      schemaValidator.validateMetadata(metadata, contractId);

      List<Path> versionFiles = schemaLoader.listVersionFiles(contractDirectory);
      schemaValidator.validateVersionFiles(versionFiles);
      for (Path versionFile : versionFiles) {
        SchemaSnapshot snapshot = schemaLoader.loadSchema(versionFile);
        schemaValidator.validateSchema(snapshot, versionFile);
      }
    } catch (SchemaValidationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ExecutionException("Failed to lint contract directory: " + contractDirectory, ex);
    }
  }

  @Override
  public String diff(Path baseSchema, Path candidateSchema) {
    try {
      SchemaSnapshot base = loadValidatedSchema(baseSchema);
      SchemaSnapshot candidate = loadValidatedSchema(candidateSchema);
      SchemaDiff diff = diffEngine.diff(base, candidate);

      List<String> lines = new ArrayList<>();
      lines.add("Schema diff:");
      diff.fieldAdded().forEach(field -> lines.add("+ field added: " + field));
      diff.fieldRemoved().forEach(field -> lines.add("- field removed: " + field));
      diff.typeChanged().forEach(change -> lines.add("~ type changed: " + change));
      diff.requiredAdded().forEach(field -> lines.add("! required added: " + field));
      diff.requiredRemoved().forEach(field -> lines.add("! required removed: " + field));
      diff.enumAdded().forEach(change -> lines.add("~ enum value added: " + change));
      diff.enumRemoved().forEach(change -> lines.add("~ enum value removed: " + change));

      if (lines.size() == 1) {
        lines.add("No semantic differences found.");
      }
      return String.join(System.lineSeparator(), lines);
    } catch (SchemaValidationException | CompatibilityException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ExecutionException("Failed to diff schema files.", ex);
    }
  }

  @Override
  public CompatibilityResult checkCompatibility(Path baseSchema, Path candidateSchema, CompatibilityMode mode) {
    return checkCompatibility(baseSchema, candidateSchema, mode, PolicyPackDefaults.baselinePack());
  }

  @Override
  public CompatibilityResult checkCompatibility(
      Path baseSchema,
      Path candidateSchema,
      CompatibilityMode mode,
      PolicyPack policyPack) {
    try {
      SchemaSnapshot base = loadValidatedSchema(baseSchema);
      SchemaSnapshot candidate = loadValidatedSchema(candidateSchema);
      return compatibilityEngine.checkCompatibility(base, candidate, mode, policyPack);
    } catch (SchemaValidationException | CompatibilityException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ExecutionException("Failed to run compatibility check.", ex);
    }
  }

  private SchemaSnapshot loadValidatedSchema(Path schemaPath) {
    SchemaSnapshot snapshot = schemaLoader.loadSchema(schemaPath);
    schemaValidator.validateSchema(snapshot, schemaPath);
    return snapshot;
  }
}
