package com.ideas.contracts.service.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Pattern;

public record CreateContractVersionRequest(
    String version,
    JsonNode schema
) {
  private static final Pattern VERSION_PATTERN = Pattern.compile("^v[1-9][0-9]*$");

  public CreateContractVersionRequest {
    version = normalizeVersion(version);
    schema = normalizeSchema(schema);
  }

  private static String normalizeVersion(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("version must not be blank.");
    }
    String normalized = value.trim();
    if (!VERSION_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException("version must match the format v{number}.");
    }
    return normalized;
  }

  private static JsonNode normalizeSchema(JsonNode value) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("schema must be a JSON object.");
    }
    return value;
  }
}
