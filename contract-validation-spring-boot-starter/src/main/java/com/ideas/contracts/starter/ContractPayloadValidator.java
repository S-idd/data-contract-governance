package com.ideas.contracts.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ContractPayloadValidator {
  private final Path contractsRoot;
  private final ObjectMapper objectMapper;
  private final JsonSchemaFactory schemaFactory;
  private final ConcurrentMap<Path, JsonSchema> schemaCache;

  public ContractPayloadValidator(String contractsRoot, ObjectMapper objectMapper) {
    this.contractsRoot = Paths.get(contractsRoot == null || contractsRoot.isBlank() ? "contracts" : contractsRoot);
    this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    this.schemaCache = new ConcurrentHashMap<>();
  }

  public void validate(String contractId, String version, Object payload) {
    if (payload == null) {
      throw new ContractPayloadValidationException("Payload must not be null.");
    }
    Path schemaPath = resolveSchemaPath(contractId, version);
    JsonSchema schema = schemaCache.computeIfAbsent(schemaPath, this::loadSchema);
    JsonNode payloadNode = objectMapper.valueToTree(payload);
    Set<ValidationMessage> errors = schema.validate(payloadNode);
    if (!errors.isEmpty()) {
      String message = errors.stream()
          .map(ValidationMessage::getMessage)
          .sorted(Comparator.naturalOrder())
          .findFirst()
          .orElse("Payload does not satisfy contract schema.");
      throw new ContractPayloadValidationException(message);
    }
  }

  private JsonSchema loadSchema(Path schemaPath) {
    try {
      JsonNode schemaNode = objectMapper.readTree(schemaPath.toFile());
      return schemaFactory.getSchema(schemaNode);
    } catch (Exception ex) {
      throw new ContractPayloadValidationException("Unable to load schema: " + schemaPath, ex);
    }
  }

  private Path resolveSchemaPath(String contractId, String version) {
    if (contractId == null || contractId.isBlank()) {
      throw new ContractPayloadValidationException("contractId must not be blank.");
    }
    if (version == null || version.isBlank()) {
      throw new ContractPayloadValidationException("version must not be blank.");
    }
    String normalizedVersion = version.endsWith(".json") ? version.substring(0, version.length() - 5) : version;
    Path normalizedRoot = contractsRoot.toAbsolutePath().normalize();
    Path schemaPath = normalizedRoot
        .resolve(contractId.trim())
        .resolve(normalizedVersion.trim() + ".json")
        .normalize();
    if (!schemaPath.startsWith(normalizedRoot)) {
      throw new ContractPayloadValidationException("Resolved schema path escapes contracts root.");
    }
    if (!Files.exists(schemaPath)) {
      throw new ContractPayloadValidationException("Contract schema not found: " + schemaPath);
    }
    return schemaPath;
  }
}
