package com.ideas.contracts.service.model;

import com.ideas.contracts.core.CompatibilityMode;
import java.util.Locale;
import java.util.regex.Pattern;

public record CheckRunCreateRequest(
    String contractId,
    String baseVersion,
    String candidateVersion,
    String mode,
    String commitSha,
    String triggeredBy
) {
  private static final Pattern CONTRACT_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");
  private static final Pattern VERSION_PATTERN = Pattern.compile("^v[1-9][0-9]*$");

  public CheckRunCreateRequest {
    contractId = normalizeContractId(contractId);
    baseVersion = normalizeVersion("baseVersion", baseVersion);
    candidateVersion = normalizeVersion("candidateVersion", candidateVersion);
    if (baseVersion.equals(candidateVersion)) {
      throw new IllegalArgumentException("baseVersion must differ from candidateVersion.");
    }
    mode = normalizeMode(mode);
    commitSha = normalizeOptional(commitSha);
    triggeredBy = normalizeRequired("triggeredBy", triggeredBy);
  }

  private static String normalizeContractId(String value) {
    String normalized = normalizeRequired("contractId", value);
    if (!CONTRACT_ID_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException(
          "contractId must contain only letters, numbers, dots, underscores, or dashes.");
    }
    return normalized;
  }

  private static String normalizeVersion(String fieldName, String value) {
    String normalized = normalizeRequired(fieldName, value);
    if (!VERSION_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException(fieldName + " must match the format v{number}.");
    }
    return normalized;
  }

  private static String normalizeMode(String value) {
    String normalized = normalizeRequired("mode", value).toUpperCase(Locale.ROOT);
    try {
      CompatibilityMode.valueOf(normalized);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("mode must be one of BACKWARD, FORWARD, FULL.");
    }
    return normalized;
  }

  private static String normalizeOptional(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private static String normalizeRequired(String fieldName, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank.");
    }
    return value.trim();
  }
}
