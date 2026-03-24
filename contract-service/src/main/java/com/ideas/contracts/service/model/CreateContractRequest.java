package com.ideas.contracts.service.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.ideas.contracts.core.CompatibilityMode;
import java.util.Locale;
import java.util.regex.Pattern;

public record CreateContractRequest(
    String contractId,
    String ownerTeam,
    String domain,
    String compatibilityMode,
    String policyPack,
    String initialVersion,
    JsonNode schema
) {
  private static final Pattern CONTRACT_ID_PATTERN = Pattern.compile("^[a-z0-9]+(\\.[a-z0-9]+)*$");
  private static final Pattern VERSION_PATTERN = Pattern.compile("^v[1-9][0-9]*$");

  public CreateContractRequest {
    contractId = normalizeContractId(contractId);
    ownerTeam = normalizeRequired("ownerTeam", ownerTeam);
    domain = normalizeRequired("domain", domain);
    compatibilityMode = normalizeCompatibilityMode(compatibilityMode);
    policyPack = normalizeOptional(policyPack);
    initialVersion = normalizeInitialVersion(initialVersion);
    schema = normalizeSchema(schema);
  }

  private static String normalizeContractId(String value) {
    String normalized = normalizeRequired("contractId", value);
    if (!CONTRACT_ID_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException("contractId must use lowercase dot-separated format.");
    }
    return normalized;
  }

  private static String normalizeCompatibilityMode(String value) {
    String normalized = normalizeRequired("compatibilityMode", value).toUpperCase(Locale.ROOT);
    try {
      CompatibilityMode.valueOf(normalized);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("compatibilityMode must be one of BACKWARD, FORWARD, FULL.");
    }
    return normalized;
  }

  private static String normalizeInitialVersion(String value) {
    if (value == null || value.isBlank()) {
      return "v1";
    }
    String normalized = value.trim();
    if (!VERSION_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException("initialVersion must match the format v{number}.");
    }
    return normalized;
  }

  private static JsonNode normalizeSchema(JsonNode value) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("schema must be a JSON object.");
    }
    return value;
  }

  private static String normalizeRequired(String fieldName, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank.");
    }
    return value.trim();
  }

  private static String normalizeOptional(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
