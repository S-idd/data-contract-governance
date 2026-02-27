package com.ideas.contracts.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DefaultContractEngine implements ContractEngine {
  private static final Pattern CONTRACT_ID_PATTERN = Pattern.compile("^[a-z0-9]+(\\.[a-z0-9]+)*$");
  private static final Pattern VERSION_FILE_PATTERN = Pattern.compile("^v[1-9][0-9]*\\.json$");
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Override
  public void lint(Path contractDirectory) {
    if (contractDirectory == null) {
      throw new IllegalStateException("Contract path must not be null.");
    }
    if (!java.nio.file.Files.exists(contractDirectory)) {
      throw new IllegalStateException("Contract directory does not exist: " + contractDirectory);
    }
    if (!java.nio.file.Files.isDirectory(contractDirectory)) {
      throw new IllegalStateException("Expected a directory path: " + contractDirectory);
    }

    String contractId = contractDirectory.getFileName().toString();
    if (!CONTRACT_ID_PATTERN.matcher(contractId).matches()) {
      throw new IllegalStateException(
          "Invalid contract directory name '" + contractId + "'. Expected lowercase dot-separated format.");
    }

    Path metadataPath = contractDirectory.resolve("metadata.yaml");
    if (!java.nio.file.Files.exists(metadataPath)) {
      throw new IllegalStateException("Missing metadata.yaml in contract directory: " + contractDirectory);
    }
    validateMetadataFile(metadataPath);

    List<Path> versionFiles;
    try (Stream<Path> files = java.nio.file.Files.list(contractDirectory)) {
      versionFiles = files
          .filter(java.nio.file.Files::isRegularFile)
          .filter(path -> VERSION_FILE_PATTERN.matcher(path.getFileName().toString()).matches())
          .sorted()
          .collect(Collectors.toList());
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read contract directory: " + contractDirectory, e);
    }

    if (versionFiles.isEmpty()) {
      throw new IllegalStateException("No versioned schema files found. Expected files like v1.json.");
    }

    for (Path versionFile : versionFiles) {
      validateJsonSchemaFile(versionFile);
    }
  }

  @Override
  public String diff(Path baseSchema, Path candidateSchema) {
    SchemaSnapshot base = loadSchemaSnapshot(baseSchema);
    SchemaSnapshot candidate = loadSchemaSnapshot(candidateSchema);
    Comparison comparison = compare(base, candidate);

    List<String> lines = new ArrayList<>();
    lines.add("Schema diff:");
    comparison.fieldAdded().forEach(field -> lines.add("+ field added: " + field));
    comparison.fieldRemoved().forEach(field -> lines.add("- field removed: " + field));
    comparison.typeChanged().forEach(change -> lines.add("~ type changed: " + change));
    comparison.requiredAdded().forEach(field -> lines.add("! required added: " + field));
    comparison.requiredRemoved().forEach(field -> lines.add("! required removed: " + field));
    comparison.enumAdded().forEach(change -> lines.add("~ enum value added: " + change));
    comparison.enumRemoved().forEach(change -> lines.add("~ enum value removed: " + change));

    if (lines.size() == 1) {
      lines.add("No semantic differences found.");
    }
    return String.join(System.lineSeparator(), lines);
  }

  @Override
  public CompatibilityResult checkCompatibility(Path baseSchema, Path candidateSchema, CompatibilityMode mode) {
    SchemaSnapshot base = loadSchemaSnapshot(baseSchema);
    SchemaSnapshot candidate = loadSchemaSnapshot(candidateSchema);
    List<String> breakingChanges = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    if (mode == CompatibilityMode.BACKWARD || mode == CompatibilityMode.FULL) {
      applyBackwardRules(base, candidate, breakingChanges, warnings);
    }
    if (mode == CompatibilityMode.FORWARD || mode == CompatibilityMode.FULL) {
      List<String> forwardBreaking = new ArrayList<>();
      List<String> forwardWarnings = new ArrayList<>();
      applyBackwardRules(candidate, base, forwardBreaking, forwardWarnings);
      forwardBreaking.forEach(item -> breakingChanges.add("[FORWARD] " + item));
      forwardWarnings.forEach(item -> warnings.add("[FORWARD] " + item));
    }

    if (breakingChanges.isEmpty()) {
      return new CompatibilityResult(CheckStatus.PASS, List.of(), warnings);
    }
    return new CompatibilityResult(CheckStatus.FAIL, breakingChanges, warnings);
  }

  private void validateJsonSchemaFile(Path schemaPath) {
    try {
      var root = OBJECT_MAPPER.readTree(schemaPath.toFile());
      if (root == null || !root.isObject()) {
        throw new IllegalStateException("Schema must be a JSON object: " + schemaPath.getFileName());
      }
    } catch (IOException e) {
      throw new IllegalStateException("Invalid JSON in schema file: " + schemaPath.getFileName(), e);
    }
  }

  private void validateMetadataFile(Path metadataPath) {
    Map<String, String> metadata = new HashMap<>();
    List<String> lines;
    try {
      lines = java.nio.file.Files.readAllLines(metadataPath, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read metadata file: " + metadataPath.getFileName(), e);
    }

    for (String rawLine : lines) {
      String line = rawLine.trim();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      int separator = line.indexOf(':');
      if (separator <= 0) {
        continue;
      }
      String key = line.substring(0, separator).trim();
      String value = line.substring(separator + 1).trim();
      if (!key.isEmpty() && !value.isEmpty()) {
        metadata.put(key, value);
      }
    }

    String ownerTeam = metadata.get("ownerTeam");
    if (ownerTeam == null || ownerTeam.isBlank()) {
      throw new IllegalStateException("metadata.yaml must define non-empty 'ownerTeam'.");
    }

    String domain = metadata.get("domain");
    if (domain == null || domain.isBlank()) {
      throw new IllegalStateException("metadata.yaml must define non-empty 'domain'.");
    }

    String compatibilityMode = metadata.get("compatibilityMode");
    if (compatibilityMode == null || compatibilityMode.isBlank()) {
      throw new IllegalStateException("metadata.yaml must define non-empty 'compatibilityMode'.");
    }

    boolean validMode = Arrays.stream(CompatibilityMode.values())
        .anyMatch(mode -> mode.name().equals(compatibilityMode));
    if (!validMode) {
      throw new IllegalStateException(
          "Invalid compatibilityMode '" + compatibilityMode + "'. Allowed: BACKWARD, FORWARD, FULL.");
    }
  }

  private SchemaSnapshot loadSchemaSnapshot(Path schemaPath) {
    if (schemaPath == null || !java.nio.file.Files.exists(schemaPath)) {
      throw new IllegalStateException("Schema file does not exist: " + schemaPath);
    }
    ObjectNode root;
    try {
      var node = OBJECT_MAPPER.readTree(schemaPath.toFile());
      if (node == null || !node.isObject()) {
        throw new IllegalStateException("Schema must be a JSON object: " + schemaPath.getFileName());
      }
      root = (ObjectNode) node;
    } catch (IOException e) {
      throw new IllegalStateException("Invalid JSON in schema file: " + schemaPath.getFileName(), e);
    }

    Set<String> requiredFields = new HashSet<>();
    if (root.has("required") && root.get("required").isArray()) {
      ArrayNode requiredNode = (ArrayNode) root.get("required");
      requiredNode.forEach(item -> {
        if (item.isTextual()) {
          requiredFields.add(item.asText());
        }
      });
    }

    Map<String, FieldInfo> fields = new HashMap<>();
    if (root.has("properties") && root.get("properties").isObject()) {
      ObjectNode props = (ObjectNode) root.get("properties");
      props.fields().forEachRemaining(entry -> {
        String name = entry.getKey();
        if (!entry.getValue().isObject()) {
          return;
        }
        ObjectNode fieldNode = (ObjectNode) entry.getValue();
        String type = fieldNode.has("type") && fieldNode.get("type").isTextual()
            ? fieldNode.get("type").asText()
            : "unknown";
        Set<String> enumValues = new HashSet<>();
        if (fieldNode.has("enum") && fieldNode.get("enum").isArray()) {
          fieldNode.get("enum").forEach(v -> enumValues.add(v.asText()));
        }
        fields.put(name, new FieldInfo(type, requiredFields.contains(name), enumValues));
      });
    }

    return new SchemaSnapshot(fields);
  }

  private Comparison compare(SchemaSnapshot base, SchemaSnapshot candidate) {
    List<String> fieldAdded = new ArrayList<>();
    List<String> fieldRemoved = new ArrayList<>();
    List<String> typeChanged = new ArrayList<>();
    List<String> requiredAdded = new ArrayList<>();
    List<String> requiredRemoved = new ArrayList<>();
    List<String> enumAdded = new ArrayList<>();
    List<String> enumRemoved = new ArrayList<>();

    Set<String> allFields = new HashSet<>();
    allFields.addAll(base.fields().keySet());
    allFields.addAll(candidate.fields().keySet());

    for (String field : allFields) {
      FieldInfo before = base.fields().get(field);
      FieldInfo after = candidate.fields().get(field);
      if (before == null && after != null) {
        fieldAdded.add(field);
        if (after.required) {
          requiredAdded.add(field);
        }
        continue;
      }
      if (before != null && after == null) {
        fieldRemoved.add(field);
        if (before.required) {
          requiredRemoved.add(field);
        }
        continue;
      }
      if (!before.type.equals(after.type)) {
        typeChanged.add(field + " (" + before.type + " -> " + after.type + ")");
      }
      if (!before.required && after.required) {
        requiredAdded.add(field);
      }
      if (before.required && !after.required) {
        requiredRemoved.add(field);
      }

      Set<String> beforeEnum = before.enumValues;
      Set<String> afterEnum = after.enumValues;
      for (String value : afterEnum) {
        if (!beforeEnum.contains(value)) {
          enumAdded.add(field + "." + value);
        }
      }
      for (String value : beforeEnum) {
        if (!afterEnum.contains(value)) {
          enumRemoved.add(field + "." + value);
        }
      }
    }

    fieldAdded.sort(String::compareTo);
    fieldRemoved.sort(String::compareTo);
    typeChanged.sort(String::compareTo);
    requiredAdded.sort(String::compareTo);
    requiredRemoved.sort(String::compareTo);
    enumAdded.sort(String::compareTo);
    enumRemoved.sort(String::compareTo);
    return new Comparison(fieldAdded, fieldRemoved, typeChanged, requiredAdded, requiredRemoved, enumAdded, enumRemoved);
  }

  private void applyBackwardRules(
      SchemaSnapshot base,
      SchemaSnapshot candidate,
      List<String> breakingChanges,
      List<String> warnings) {
    Comparison c = compare(base, candidate);
    c.fieldRemoved().forEach(field -> breakingChanges.add("Field removed: " + field));
    c.typeChanged().forEach(change -> breakingChanges.add("Field type changed: " + change));
    c.requiredAdded().forEach(field -> breakingChanges.add("Required field added: " + field));
    c.enumRemoved().forEach(change -> breakingChanges.add("Enum value removed: " + change));
    c.enumAdded().forEach(change -> warnings.add("Enum value added: " + change));
  }

  private record SchemaSnapshot(Map<String, FieldInfo> fields) {}

  private record FieldInfo(String type, boolean required, Set<String> enumValues) {}

  private record Comparison(
      List<String> fieldAdded,
      List<String> fieldRemoved,
      List<String> typeChanged,
      List<String> requiredAdded,
      List<String> requiredRemoved,
      List<String> enumAdded,
      List<String> enumRemoved) {}
}
