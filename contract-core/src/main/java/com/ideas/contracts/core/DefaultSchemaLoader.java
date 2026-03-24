package com.ideas.contracts.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class DefaultSchemaLoader implements SchemaLoader {
  private static final Pattern VERSION_FILE_PATTERN = Pattern.compile("^v[1-9][0-9]*\\.json$");
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
  private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

  @Override
  public ContractMetadata loadMetadata(Path metadataPath, String contractId) {
    if (metadataPath == null || Files.notExists(metadataPath)) {
      throw new SchemaValidationException("Missing metadata.yaml in contract directory: " + contractId);
    }
    MetadataYaml metadataYaml;
    try {
      metadataYaml = YAML_MAPPER.readValue(metadataPath.toFile(), MetadataYaml.class);
    } catch (IOException e) {
      throw new SchemaValidationException("Invalid YAML in metadata file: " + metadataPath.getFileName(), e);
    }
    if (metadataYaml == null) {
      throw new SchemaValidationException("metadata.yaml must not be empty.");
    }
    CompatibilityMode mode = parseCompatibilityMode(metadataYaml.compatibilityMode());
    return new ContractMetadata(
        contractId,
        required("ownerTeam", metadataYaml.ownerTeam()),
        required("domain", metadataYaml.domain()),
        mode,
        optional(metadataYaml.policyPack()));
  }

  @Override
  public SchemaSnapshot loadSchema(Path schemaPath) {
    if (schemaPath == null || Files.notExists(schemaPath)) {
      throw new SchemaValidationException("Schema file does not exist: " + schemaPath);
    }
    JsonNode node;
    try {
      node = JSON_MAPPER.readTree(schemaPath.toFile());
    } catch (IOException e) {
      throw new SchemaValidationException("Invalid JSON in schema file: " + schemaPath.getFileName(), e);
    }
    if (node == null || !node.isObject()) {
      throw new SchemaValidationException("Schema must be a JSON object: " + schemaPath.getFileName());
    }
    ObjectNode root = (ObjectNode) node;

    Set<String> requiredFields = parseRequiredFields(root, schemaPath);
    Map<String, SchemaField> fields = parseFields(root, requiredFields, schemaPath);
    ensureRequiredFieldsExist(requiredFields, fields, schemaPath);
    return new SchemaSnapshot(fields);
  }

  @Override
  public List<Path> listVersionFiles(Path contractDirectory) {
    try (Stream<Path> files = Files.list(contractDirectory)) {
      return files
          .filter(Files::isRegularFile)
          .filter(path -> VERSION_FILE_PATTERN.matcher(path.getFileName().toString()).matches())
          .sorted(Comparator.comparingInt(path -> versionNumber(path.getFileName().toString())))
          .toList();
    } catch (IOException e) {
      throw new SchemaValidationException("Unable to read contract directory: " + contractDirectory, e);
    }
  }

  private CompatibilityMode parseCompatibilityMode(String rawMode) {
    String normalized = required("compatibilityMode", rawMode).toUpperCase(Locale.ROOT);
    try {
      return CompatibilityMode.valueOf(normalized);
    } catch (IllegalArgumentException ex) {
      throw new SchemaValidationException(
          "Invalid compatibilityMode '" + rawMode + "'. Allowed: BACKWARD, FORWARD, FULL.");
    }
  }

  private Set<String> parseRequiredFields(ObjectNode root, Path schemaPath) {
    Set<String> requiredFields = new HashSet<>();
    JsonNode requiredNode = root.get("required");
    if (requiredNode == null) {
      return requiredFields;
    }
    if (!requiredNode.isArray()) {
      throw new SchemaValidationException("'required' must be an array in " + schemaPath.getFileName() + ".");
    }
    for (JsonNode item : requiredNode) {
      if (!item.isTextual()) {
        throw new SchemaValidationException("Each value in 'required' must be a string in " + schemaPath.getFileName() + ".");
      }
      requiredFields.add(item.asText());
    }
    return requiredFields;
  }

  private Map<String, SchemaField> parseFields(ObjectNode root, Set<String> requiredFields, Path schemaPath) {
    Map<String, SchemaField> fields = new HashMap<>();
    JsonNode propertiesNode = root.get("properties");
    if (propertiesNode == null) {
      return fields;
    }
    if (!propertiesNode.isObject()) {
      throw new SchemaValidationException("'properties' must be an object in " + schemaPath.getFileName() + ".");
    }

    ObjectNode props = (ObjectNode) propertiesNode;
    props.fields().forEachRemaining(entry -> {
      String fieldName = entry.getKey();
      JsonNode fieldNode = entry.getValue();
      if (!fieldNode.isObject()) {
        throw new SchemaValidationException("Property '" + fieldName + "' must be an object in " + schemaPath.getFileName() + ".");
      }
      ObjectNode fieldObject = (ObjectNode) fieldNode;
      ParsedType parsedType = parseType(fieldName, fieldObject, schemaPath);
      Set<String> enumValues = parseEnumValues(fieldName, fieldObject, schemaPath);
      fields.put(
          fieldName,
          new SchemaField(
              parsedType.canonicalType(),
              requiredFields.contains(fieldName),
              parsedType.nullable(),
              enumValues));
    });
    return fields;
  }

  private void ensureRequiredFieldsExist(Set<String> requiredFields, Map<String, SchemaField> fields, Path schemaPath) {
    List<String> missing = new ArrayList<>();
    for (String requiredField : requiredFields) {
      if (!fields.containsKey(requiredField)) {
        missing.add(requiredField);
      }
    }
    if (!missing.isEmpty()) {
      throw new SchemaValidationException(
          "'required' references unknown properties in "
              + schemaPath.getFileName()
              + ": "
              + String.join(", ", missing));
    }
  }

  private ParsedType parseType(String fieldName, ObjectNode fieldNode, Path schemaPath) {
    JsonNode typeNode = fieldNode.get("type");
    boolean nullableFromFlag = fieldNode.path("nullable").asBoolean(false);
    if (typeNode == null) {
      throw new SchemaValidationException(
          "Property '" + fieldName + "' in " + schemaPath.getFileName() + " must define 'type'.");
    }
    if (typeNode.isTextual()) {
      String type = typeNode.asText().trim().toLowerCase(Locale.ROOT);
      return new ParsedType(type, nullableFromFlag || "null".equals(type));
    }
    if (!typeNode.isArray()) {
      throw new SchemaValidationException(
          "Property '" + fieldName + "' in " + schemaPath.getFileName() + " has invalid 'type' definition.");
    }

    List<String> concreteTypes = new ArrayList<>();
    boolean nullable = nullableFromFlag;
    for (JsonNode item : typeNode) {
      if (!item.isTextual()) {
        throw new SchemaValidationException(
            "Property '" + fieldName + "' in " + schemaPath.getFileName() + " has non-string type entry.");
      }
      String value = item.asText().trim().toLowerCase(Locale.ROOT);
      if ("null".equals(value)) {
        nullable = true;
      } else {
        concreteTypes.add(value);
      }
    }
    if (concreteTypes.isEmpty()) {
      return new ParsedType("null", true);
    }
    concreteTypes.sort(String::compareTo);
    return new ParsedType(String.join("|", concreteTypes), nullable);
  }

  private Set<String> parseEnumValues(String fieldName, ObjectNode fieldNode, Path schemaPath) {
    JsonNode enumNode = fieldNode.get("enum");
    if (enumNode == null) {
      return Set.of();
    }
    if (!enumNode.isArray()) {
      throw new SchemaValidationException(
          "Property '" + fieldName + "' in " + schemaPath.getFileName() + " has invalid 'enum'.");
    }
    Set<String> values = new HashSet<>();
    for (JsonNode value : enumNode) {
      if (value.isValueNode()) {
        values.add(value.asText());
      } else {
        throw new SchemaValidationException(
            "Property '" + fieldName + "' in " + schemaPath.getFileName() + " has non-scalar enum value.");
      }
    }
    return values;
  }

  private String required(String fieldName, String value) {
    if (value == null || value.isBlank()) {
      throw new SchemaValidationException("metadata.yaml must define non-empty '" + fieldName + "'.");
    }
    return value.trim();
  }

  private String optional(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private int versionNumber(String fileName) {
    String raw = fileName.substring(1, fileName.length() - 5);
    return Integer.parseInt(raw);
  }

  private record MetadataYaml(
      String ownerTeam,
      String domain,
      String compatibilityMode,
      String policyPack
  ) {}

  private record ParsedType(String canonicalType, boolean nullable) {}
}
