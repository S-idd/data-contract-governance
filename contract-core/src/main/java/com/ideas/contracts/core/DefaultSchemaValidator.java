package com.ideas.contracts.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class DefaultSchemaValidator implements SchemaValidator {
  private static final Pattern CONTRACT_ID_PATTERN = Pattern.compile("^[a-z0-9]+(\\.[a-z0-9]+)*$");

  @Override
  public void validateContractDirectory(Path contractDirectory) {
    if (contractDirectory == null) {
      throw new SchemaValidationException("Contract path must not be null.");
    }
    if (!Files.exists(contractDirectory)) {
      throw new SchemaValidationException("Contract directory does not exist: " + contractDirectory);
    }
    if (!Files.isDirectory(contractDirectory)) {
      throw new SchemaValidationException("Expected a directory path: " + contractDirectory);
    }
    String contractId = contractDirectory.getFileName().toString();
    if (!CONTRACT_ID_PATTERN.matcher(contractId).matches()) {
      throw new SchemaValidationException(
          "Invalid contract directory name '" + contractId + "'. Expected lowercase dot-separated format.");
    }
  }

  @Override
  public void validateMetadata(ContractMetadata metadata, String contractId) {
    if (metadata == null) {
      throw new SchemaValidationException("metadata.yaml is missing or invalid for contract: " + contractId);
    }
    if (metadata.ownerTeam() == null || metadata.ownerTeam().isBlank()) {
      throw new SchemaValidationException("metadata.yaml must define non-empty 'ownerTeam'.");
    }
    if (metadata.domain() == null || metadata.domain().isBlank()) {
      throw new SchemaValidationException("metadata.yaml must define non-empty 'domain'.");
    }
    if (metadata.compatibilityMode() == null) {
      throw new SchemaValidationException(
          "metadata.yaml must define non-empty 'compatibilityMode'. Allowed: BACKWARD, FORWARD, FULL.");
    }
  }

  @Override
  public void validateVersionFiles(List<Path> versionFiles) {
    if (versionFiles == null || versionFiles.isEmpty()) {
      throw new SchemaValidationException("No versioned schema files found. Expected files like v1.json.");
    }
  }

  @Override
  public void validateSchema(SchemaSnapshot snapshot, Path schemaPath) {
    if (snapshot == null) {
      throw new SchemaValidationException("Schema must be a JSON object: " + schemaPath.getFileName());
    }
    for (Map.Entry<String, SchemaField> entry : snapshot.fields().entrySet()) {
      String fieldName = entry.getKey();
      SchemaField field = entry.getValue();
      if (field == null) {
        throw new SchemaValidationException("Property '" + fieldName + "' must be an object.");
      }
      if (field.type() == null || field.type().isBlank() || "unknown".equals(field.type())) {
        throw new SchemaValidationException(
            "Property '" + fieldName + "' in " + schemaPath.getFileName() + " must define a valid 'type'.");
      }
    }
  }
}
