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
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class DefaultSchemaLoader implements SchemaLoader {
  private static final Pattern VERSION_FILE_PATTERN = Pattern.compile("^v[1-9][0-9]*\\.json$");
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
  private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
  // Keywords are classified so the loader can validate them by shape and, for
  // behavior that cannot be represented as a field, retain a fail-closed
  // compatibility restriction instead of silently ignoring it.
  private static final Set<String> ASSERTION_KEYWORDS = Set.of(
      "type",
      "enum",
      "const",
      "multipleOf",
      "maximum",
      "exclusiveMaximum",
      "minimum",
      "exclusiveMinimum",
      "maxLength",
      "minLength",
      "pattern",
      "format",
      "maxItems",
      "minItems",
      "uniqueItems",
      "maxContains",
      "minContains",
      "maxProperties",
      "minProperties",
      "required",
      "dependentRequired");
  private static final Set<String> APPLICATOR_KEYWORDS = Set.of(
      "properties",
      "patternProperties",
      "additionalProperties",
      "propertyNames",
      "items",
      "prefixItems",
      "contains",
      "unevaluatedItems",
      "unevaluatedProperties",
      "allOf",
      "anyOf",
      "oneOf",
      "not",
      "if",
      "then",
      "else",
      "dependentSchemas");
  private static final Set<String> CORE_KEYWORDS = Set.of(
      "$schema", "$id", "$ref", "$anchor", "$dynamicRef", "$dynamicAnchor",
      "$defs", "$comment", "$vocabulary");
  private static final Set<String> ANNOTATION_KEYWORDS = Set.of(
      "title", "description", "default", "deprecated", "readOnly", "writeOnly", "examples");
  private static final Set<String> TRACKED_CONSTRAINT_KEYWORDS = Set.of(
      "const", "multipleOf", "maximum", "exclusiveMaximum", "minimum", "exclusiveMinimum",
      "maxLength", "minLength", "pattern", "format", "maxItems", "minItems", "uniqueItems",
      "maxContains", "minContains", "maxProperties", "minProperties");

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
    ExtractionState state = new ExtractionState();
    validateKeywordShapes(root, schemaPath, "root");
    collectProperties(root, "", root, state, new HashSet<>(), schemaPath);
    return new SchemaSnapshot(state.fields, state.conditionalRestrictions, state.schemaRestrictions);
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

  private void collectProperties(
      ObjectNode schema,
      String path,
      ObjectNode root,
      ExtractionState state,
      Set<String> referenceStack,
      Path schemaPath) {
    ObjectNode effectiveSchema = resolveReferences(schema, root, referenceStack, schemaPath, path);
    validateKeywordShapes(effectiveSchema, schemaPath, displayPath(path));
    Set<String> requiredFields = parseRequiredFields(effectiveSchema, schemaPath, path);
    JsonNode propertiesNode = effectiveSchema.get("properties");
    if (propertiesNode == null) {
      if (!requiredFields.isEmpty()) {
        throw new SchemaValidationException(
            "'required' references unknown properties in "
                + schemaPath.getFileName()
                + " for '"
                + displayPath(path)
                + "'.");
      }
    } else {
      if (!propertiesNode.isObject()) {
        throw new SchemaValidationException("'properties' must be an object in " + schemaPath.getFileName() + ".");
      }

      ObjectNode properties = (ObjectNode) propertiesNode;
      properties.fields().forEachRemaining(entry -> {
        String fieldName = entry.getKey();
        String fieldPath = path.isEmpty() ? fieldName : path + "." + fieldName;
        collectField(
            fieldPath,
            entry.getValue(),
            requiredFields.contains(fieldName),
            root,
            state,
            referenceStack,
            schemaPath);
      });
      ensureRequiredFieldsExist(requiredFields, properties, schemaPath, path);
    }
    collectCompositions(effectiveSchema, path, root, state, referenceStack, schemaPath);
    collectApplicatorRestrictions(effectiveSchema, path, state, schemaPath);
  }

  private void collectField(
      String fieldPath,
      JsonNode fieldNode,
      boolean required,
      ObjectNode root,
      ExtractionState state,
      Set<String> referenceStack,
      Path schemaPath) {
    if (fieldNode.isBoolean()) {
      putField(
          state.fields,
          fieldPath,
          new SchemaField(fieldNode.booleanValue() ? "any" : "never", required, false, Set.of()));
      state.schemaRestrictions.put(displayPath(fieldPath) + " booleanSchema", fieldNode.toString());
      return;
    }
    if (!fieldNode.isObject()) {
      throw new SchemaValidationException(
          "Property '" + fieldPath + "' must be an object in " + schemaPath.getFileName() + ".");
    }
    ObjectNode effectiveField = resolveReferences((ObjectNode) fieldNode, root, referenceStack, schemaPath, fieldPath);
    validateKeywordShapes(effectiveField, schemaPath, fieldPath);
    ParsedType parsedType = parseType(fieldPath, effectiveField, schemaPath);
    putField(
        state.fields,
        fieldPath,
        new SchemaField(
            parsedType.canonicalType(),
            required,
            parsedType.nullable(),
            parseEnumValues(fieldPath, effectiveField, schemaPath),
            parseConstraints(effectiveField)));

    collectProperties(effectiveField, fieldPath, root, state, referenceStack, schemaPath);
    collectArrayItems(effectiveField, fieldPath, root, state, referenceStack, schemaPath);
    collectOneOfBranches(effectiveField, fieldPath, root, state, referenceStack, schemaPath);
    collectApplicatorRestrictions(effectiveField, fieldPath, state, schemaPath);
  }

  private void collectArrayItems(
      ObjectNode schema,
      String path,
      ObjectNode root,
      ExtractionState state,
      Set<String> referenceStack,
      Path schemaPath) {
    JsonNode items = schema.get("items");
    if (items == null) {
      return;
    }
    if (items.isBoolean()) {
      state.schemaRestrictions.put(displayPath(path) + " items", items.toString());
      return;
    }
    if (!items.isObject()) {
      throw new SchemaValidationException("'items' must be a schema object in " + schemaPath.getFileName() + ".");
    }
    collectField(path + "[]", items, false, root, state, referenceStack, schemaPath);
  }

  private void collectOneOfBranches(
      ObjectNode schema,
      String path,
      ObjectNode root,
      ExtractionState state,
      Set<String> referenceStack,
      Path schemaPath) {
    JsonNode oneOf = schema.get("oneOf");
    if (oneOf == null) {
      return;
    }
    if (!oneOf.isArray() || oneOf.isEmpty()) {
      throw new SchemaValidationException("'oneOf' must be a non-empty array in " + schemaPath.getFileName() + ".");
    }
    Set<String> labels = new HashSet<>();
    for (int index = 0; index < oneOf.size(); index++) {
      JsonNode branch = oneOf.get(index);
      String label = branch.isObject() ? oneOfBranchLabel((ObjectNode) branch, index) : "branch-" + (index + 1);
      if (!labels.add(label)) {
        label = "branch-" + (index + 1);
      }
      collectField(
          path + ".oneOf[" + label + "]",
          branch,
          false,
          root,
          state,
          referenceStack,
          schemaPath);
    }
  }

  private String oneOfBranchLabel(ObjectNode branch, int index) {
    JsonNode discriminator = branch.path("properties").path("type").get("const");
    if (discriminator != null && discriminator.isValueNode()) {
      return "type=" + discriminator.asText().replaceAll("[^A-Za-z0-9_.-]", "_");
    }
    return "branch-" + (index + 1);
  }

  private void collectCompositions(
      ObjectNode schema,
      String path,
      ObjectNode root,
      ExtractionState state,
      Set<String> referenceStack,
      Path schemaPath) {
    collectConditionalRestriction(schema, path, state.conditionalRestrictions, schemaPath);

    JsonNode allOf = schema.get("allOf");
    if (allOf == null) {
      return;
    }
    if (!allOf.isArray() || allOf.isEmpty()) {
      throw new SchemaValidationException("'allOf' must be a non-empty array in " + schemaPath.getFileName() + ".");
    }
    state.schemaRestrictions.put(displayPath(path) + " allOf", canonicalJson(allOf));
    for (JsonNode component : allOf) {
      if (component.isBoolean()) {
        continue;
      }
      ObjectNode effectiveComponent = resolveReferences(
          (ObjectNode) component, root, referenceStack, schemaPath, displayPath(path));
      collectConditionalRestriction(effectiveComponent, path, state.conditionalRestrictions, schemaPath);

      ObjectNode unconditionalComponent = effectiveComponent.deepCopy();
      unconditionalComponent.remove(List.of("if", "then", "else"));
      if (unconditionalComponent.has("properties") || unconditionalComponent.has("allOf")) {
        collectProperties(
            unconditionalComponent,
            path,
            root,
            state,
            referenceStack,
            schemaPath);
      }
    }
  }

  private void collectConditionalRestriction(
      ObjectNode schema,
      String path,
      Map<String, String> conditionalRestrictions,
      Path schemaPath) {
    JsonNode condition = schema.get("if");
    if (condition == null) {
      return;
    }
    if (!condition.isObject()) {
      throw new SchemaValidationException("'if' must be a schema object in " + schemaPath.getFileName() + ".");
    }
    JsonNode thenSchema = schema.get("then");
    JsonNode elseSchema = schema.get("else");
    if (thenSchema == null && elseSchema == null) {
      throw new SchemaValidationException("'if' must define 'then' or 'else' in " + schemaPath.getFileName() + ".");
    }
    String key = displayPath(path) + " when " + conditionLabel((ObjectNode) condition);
    String restriction = "then=" + canonicalJson(thenSchema) + ";else=" + canonicalJson(elseSchema);
    conditionalRestrictions.merge(
        key,
        restriction,
        (existing, candidate) -> existing.equals(candidate) ? existing : existing + " && " + candidate);
  }

  private String conditionLabel(ObjectNode condition) {
    JsonNode properties = condition.get("properties");
    if (properties != null && properties.isObject()) {
      List<String> labels = new ArrayList<>();
      properties.fields().forEachRemaining(entry -> {
        JsonNode constant = entry.getValue().get("const");
        if (constant != null && constant.isValueNode()) {
          labels.add(entry.getKey() + "=" + constant.asText());
        }
      });
      if (!labels.isEmpty()) {
        labels.sort(String::compareTo);
        return String.join(" and ", labels);
      }
    }
    return canonicalJson(condition);
  }

  private String canonicalJson(JsonNode node) {
    if (node == null) {
      return "null";
    }
    if (node.isObject()) {
      Map<String, String> values = new TreeMap<>();
      node.fields().forEachRemaining(entry -> values.put(entry.getKey(), canonicalJson(entry.getValue())));
      return values.entrySet().stream()
          .map(entry -> entry.getKey() + ":" + entry.getValue())
          .reduce((left, right) -> left + "," + right)
          .map(value -> "{" + value + "}")
          .orElse("{}");
    }
    if (node.isArray()) {
      List<String> values = new ArrayList<>();
      node.forEach(value -> values.add(canonicalJson(value)));
      return "[" + String.join(",", values) + "]";
    }
    return node.toString();
  }

  private void putField(Map<String, SchemaField> fields, String path, SchemaField candidate) {
    SchemaField existing = fields.get(path);
    if (existing == null) {
      fields.put(path, candidate);
      return;
    }
    Map<String, String> constraints = new HashMap<>(existing.constraints());
    constraints.putAll(candidate.constraints());
    Set<String> enumValues = existing.enumValues().isEmpty()
        ? candidate.enumValues()
        : candidate.enumValues().isEmpty() ? existing.enumValues() : intersection(existing.enumValues(), candidate.enumValues());
    fields.put(
        path,
        new SchemaField(
            mergeTypes(existing.type(), candidate.type()),
            existing.required() || candidate.required(),
            existing.nullable() && candidate.nullable(),
            enumValues,
            constraints));
  }

  private Set<String> intersection(Set<String> left, Set<String> right) {
    Set<String> result = new HashSet<>(left);
    result.retainAll(right);
    return result;
  }

  private String mergeTypes(String left, String right) {
    if (left.equals(right)) {
      return left;
    }
    List<String> values = new ArrayList<>(List.of(left, right));
    values.sort(String::compareTo);
    return String.join("&", values);
  }

  private Set<String> parseRequiredFields(ObjectNode root, Path schemaPath, String path) {
    Set<String> requiredFields = new HashSet<>();
    JsonNode requiredNode = root.get("required");
    if (requiredNode == null) {
      return requiredFields;
    }
    if (!requiredNode.isArray()) {
      throw new SchemaValidationException("'required' must be an array for '" + displayPath(path) + "' in " + schemaPath.getFileName() + ".");
    }
    for (JsonNode item : requiredNode) {
      if (!item.isTextual()) {
        throw new SchemaValidationException("Each value in 'required' must be a string in " + schemaPath.getFileName() + ".");
      }
      requiredFields.add(item.asText());
    }
    return requiredFields;
  }

  private void ensureRequiredFieldsExist(
      Set<String> requiredFields, ObjectNode fields, Path schemaPath, String path) {
    List<String> missing = new ArrayList<>();
    for (String requiredField : requiredFields) {
      if (!fields.has(requiredField)) {
        missing.add(requiredField);
      }
    }
    if (!missing.isEmpty()) {
      throw new SchemaValidationException(
          "'required' references unknown properties in "
              + schemaPath.getFileName()
              + ": "
              + String.join(", ", missing)
              + " for '"
              + displayPath(path)
              + "'.");
    }
  }

  private ParsedType parseType(String fieldName, ObjectNode fieldNode, Path schemaPath) {
    JsonNode typeNode = fieldNode.get("type");
    boolean nullableFromFlag = fieldNode.path("nullable").asBoolean(false);
    if (typeNode == null) {
      if (fieldNode.has("properties")) {
        return new ParsedType("object", nullableFromFlag);
      }
      if (fieldNode.has("items")) {
        return new ParsedType("array", nullableFromFlag);
      }
      if (fieldNode.has("oneOf")) {
        return new ParsedType("oneOf", nullableFromFlag);
      }
      if (fieldNode.has("anyOf")) {
        return new ParsedType("anyOf", nullableFromFlag);
      }
      if (fieldNode.has("allOf")) {
        return new ParsedType("allOf", nullableFromFlag);
      }
      if (fieldNode.has("prefixItems") || fieldNode.has("contains") || fieldNode.has("unevaluatedItems")) {
        return new ParsedType("array", nullableFromFlag);
      }
      if (fieldNode.has("patternProperties")
          || fieldNode.has("additionalProperties")
          || fieldNode.has("propertyNames")
          || fieldNode.has("dependentSchemas")
          || fieldNode.has("unevaluatedProperties")) {
        return new ParsedType("object", nullableFromFlag);
      }
      if (fieldNode.has("not") || fieldNode.has("if")) {
        return new ParsedType("composed", nullableFromFlag);
      }
      JsonNode constant = fieldNode.get("const");
      if (constant != null) {
        return new ParsedType(typeForValue(constant), nullableFromFlag || constant.isNull());
      }
      throw new SchemaValidationException(
          "Property '" + fieldName + "' in " + schemaPath.getFileName()
              + " must define a type-bearing or supported applicator keyword.");
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

  private String typeForValue(JsonNode value) {
    if (value.isTextual()) {
      return "string";
    }
    if (value.isIntegralNumber()) {
      return "integer";
    }
    if (value.isNumber()) {
      return "number";
    }
    if (value.isBoolean()) {
      return "boolean";
    }
    if (value.isObject()) {
      return "object";
    }
    if (value.isArray()) {
      return "array";
    }
    return "null";
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

  private void collectApplicatorRestrictions(
      ObjectNode schema,
      String path,
      ExtractionState state,
      Path schemaPath) {
    for (String keyword : Set.of(
        "patternProperties",
        "additionalProperties",
        "propertyNames",
        "prefixItems",
        "contains",
        "unevaluatedItems",
        "unevaluatedProperties",
        "anyOf",
        "not",
        "dependentSchemas",
        "dependentRequired")) {
      JsonNode value = schema.get(keyword);
      if (value != null) {
        state.schemaRestrictions.put(
            displayPath(path) + " " + keyword,
            canonicalJson(value));
      }
    }
  }

  private void validateKeywordShapes(ObjectNode schema, Path schemaPath, String location) {
    schema.fields().forEachRemaining(entry -> {
      String keyword = entry.getKey();
      JsonNode value = entry.getValue();
      if (ASSERTION_KEYWORDS.contains(keyword)) {
        validateAssertionKeyword(keyword, value, schemaPath, location);
      } else if (APPLICATOR_KEYWORDS.contains(keyword)) {
        validateApplicatorKeyword(keyword, value, schemaPath, location);
      } else if (CORE_KEYWORDS.contains(keyword)) {
        validateCoreKeyword(keyword, value, schemaPath, location);
      } else if (ANNOTATION_KEYWORDS.contains(keyword)) {
        validateAnnotationKeyword(keyword, value, schemaPath, location);
      }
      validateNestedSchemas(keyword, value, schemaPath, location);
    });
  }

  private void validateNestedSchemas(String keyword, JsonNode value, Path schemaPath, String location) {
    if (!APPLICATOR_KEYWORDS.contains(keyword) && !"$defs".equals(keyword)) {
      return;
    }
    if (value.isBoolean()) {
      return;
    }
    if (Set.of("properties", "patternProperties", "dependentSchemas", "$defs").contains(keyword)) {
      var properties = value.fields();
      while (properties.hasNext()) {
        var property = properties.next();
        validateSchemaNode(property.getValue(), schemaPath, location + "." + keyword + "." + property.getKey());
      }
      return;
    }
    if (Set.of("prefixItems", "allOf", "anyOf", "oneOf").contains(keyword)) {
      for (int index = 0; index < value.size(); index++) {
        validateSchemaNode(value.get(index), schemaPath, location + "." + keyword + "[" + index + "]");
      }
      return;
    }
    if (Set.of(
        "additionalProperties", "propertyNames", "items", "contains", "unevaluatedItems",
        "unevaluatedProperties", "not", "if", "then", "else").contains(keyword)) {
      validateSchemaNode(value, schemaPath, location + "." + keyword);
    }
  }

  private void validateSchemaNode(JsonNode value, Path schemaPath, String location) {
    if (value.isObject()) {
      validateKeywordShapes((ObjectNode) value, schemaPath, location);
    }
  }

  private void validateAssertionKeyword(String keyword, JsonNode value, Path schemaPath, String location) {
    boolean valid = switch (keyword) {
      case "type" -> value.isTextual() || (value.isArray() && arrayItemsAre(value, JsonNode::isTextual));
      case "enum" -> value.isArray() && !value.isEmpty();
      case "multipleOf", "maximum", "exclusiveMaximum", "minimum", "exclusiveMinimum" -> value.isNumber();
      case "maxLength", "minLength", "maxItems", "minItems", "maxContains", "minContains", "maxProperties", "minProperties" ->
          value.canConvertToInt() && value.intValue() >= 0;
      case "pattern", "format" -> value.isTextual();
      case "uniqueItems" -> value.isBoolean();
      case "required" -> value.isArray() && arrayItemsAre(value, JsonNode::isTextual);
      case "dependentRequired" -> value.isObject() && objectValuesAre(
          value,
          dependency -> dependency.isArray() && arrayItemsAre(dependency, JsonNode::isTextual));
      case "const" -> true;
      default -> false;
    };
    if (!valid) {
      throw invalidKeyword(keyword, schemaPath, location);
    }
  }

  private void validateApplicatorKeyword(String keyword, JsonNode value, Path schemaPath, String location) {
    boolean schemaValue = value.isObject() || value.isBoolean();
    boolean valid = switch (keyword) {
      case "properties", "patternProperties", "dependentSchemas" -> value.isObject()
          && objectValuesAre(value, this::isSchema);
      case "additionalProperties", "propertyNames", "items", "contains", "unevaluatedItems", "unevaluatedProperties", "not", "if", "then", "else" -> schemaValue;
      case "prefixItems", "allOf", "anyOf", "oneOf" -> value.isArray() && !value.isEmpty()
          && arrayItemsAre(value, this::isSchema);
      default -> false;
    };
    if (!valid) {
      throw invalidKeyword(keyword, schemaPath, location);
    }
  }

  private boolean isSchema(JsonNode value) {
    return value.isObject() || value.isBoolean();
  }

  private void validateCoreKeyword(String keyword, JsonNode value, Path schemaPath, String location) {
    if ("$dynamicRef".equals(keyword)) {
      throw new SchemaValidationException(
          "'$dynamicRef' is not supported for compatibility analysis in "
              + schemaPath.getFileName() + " at '" + location + "'. Use a local '$ref' instead.");
    }
    boolean valid = switch (keyword) {
      case "$schema", "$id", "$ref", "$anchor", "$dynamicAnchor", "$comment" -> value.isTextual();
      case "$defs" -> value.isObject() && objectValuesAre(value, this::isSchema);
      case "$vocabulary" -> value.isObject() && objectValuesAre(value, JsonNode::isBoolean);
      default -> false;
    };
    if (!valid) {
      throw invalidKeyword(keyword, schemaPath, location);
    }
  }

  private void validateAnnotationKeyword(String keyword, JsonNode value, Path schemaPath, String location) {
    boolean valid = switch (keyword) {
      case "title", "description" -> value.isTextual();
      case "deprecated", "readOnly", "writeOnly" -> value.isBoolean();
      case "examples" -> value.isArray();
      case "default" -> true;
      default -> false;
    };
    if (!valid) {
      throw invalidKeyword(keyword, schemaPath, location);
    }
  }

  private SchemaValidationException invalidKeyword(String keyword, Path schemaPath, String location) {
    return new SchemaValidationException(
        "Invalid '" + keyword + "' keyword in " + schemaPath.getFileName() + " at '" + location + "'.");
  }

  private boolean arrayItemsAre(JsonNode array, Predicate<JsonNode> predicate) {
    for (JsonNode item : array) {
      if (!predicate.test(item)) {
        return false;
      }
    }
    return true;
  }

  private boolean objectValuesAre(JsonNode object, Predicate<JsonNode> predicate) {
    var values = object.elements();
    while (values.hasNext()) {
      if (!predicate.test(values.next())) {
        return false;
      }
    }
    return true;
  }

  private Map<String, String> parseConstraints(ObjectNode fieldNode) {
    Map<String, String> constraints = new HashMap<>();
    TRACKED_CONSTRAINT_KEYWORDS.stream()
        .filter(fieldNode::has)
        .forEach(keyword -> constraints.put(keyword, fieldNode.get(keyword).toString()));
    return constraints;
  }

  private ObjectNode resolveReferences(
      ObjectNode schema,
      ObjectNode root,
      Set<String> referenceStack,
      Path schemaPath,
      String path) {
    JsonNode reference = schema.get("$ref");
    if (reference == null) {
      return schema;
    }
    if (!reference.isTextual()) {
      throw new SchemaValidationException("'$ref' must be a string for '" + displayPath(path) + "'.");
    }
    String ref = reference.asText();
    if (!ref.equals("#") && !ref.startsWith("#/")) {
      throw new SchemaValidationException(
          "Only local JSON Pointer references are supported. Unsupported $ref '" + ref + "' in " + schemaPath.getFileName() + ".");
    }
    if (!referenceStack.add(ref)) {
      throw new SchemaValidationException("Circular $ref detected for '" + displayPath(path) + "': " + ref);
    }
    try {
      JsonNode target = ref.equals("#") ? root : root.at(ref.substring(1));
      if (target.isMissingNode() || !target.isObject()) {
        throw new SchemaValidationException("Unable to resolve $ref '" + ref + "' in " + schemaPath.getFileName() + ".");
      }
      ObjectNode resolved = resolveReferences((ObjectNode) target, root, referenceStack, schemaPath, path).deepCopy();
      schema.fields().forEachRemaining(entry -> {
        if (!"$ref".equals(entry.getKey())) {
          resolved.set(entry.getKey(), entry.getValue());
        }
      });
      return resolved;
    } finally {
      referenceStack.remove(ref);
    }
  }

  private String displayPath(String path) {
    return path.isEmpty() ? "root" : path;
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

  private static final class ExtractionState {
    private final Map<String, SchemaField> fields = new HashMap<>();
    private final Map<String, String> conditionalRestrictions = new HashMap<>();
    private final Map<String, String> schemaRestrictions = new HashMap<>();
  }

  private record MetadataYaml(
      String ownerTeam,
      String domain,
      String compatibilityMode,
      String policyPack
  ) {}

  private record ParsedType(String canonicalType, boolean nullable) {}
}
