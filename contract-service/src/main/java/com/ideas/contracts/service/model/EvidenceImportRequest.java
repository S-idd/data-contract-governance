package com.ideas.contracts.service.model;

import com.ideas.contracts.core.CompatibilityMode;
import com.ideas.contracts.core.CheckStatus;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Versioned, replayable record of a compatibility result produced in CI. */
public record EvidenceImportRequest(
    String evidenceFormatVersion,
    String idempotencyKey,
    String contractId,
    String baseVersion,
    String candidateVersion,
    String compatibilityMode,
    String commitSha,
    String baseSchemaSha256,
    String candidateSchemaSha256,
    String engineVersion,
    String engineCompatibilityProtocol,
    String policyPackName,
    String policyPackSha256,
    String localStatus,
    List<String> breakingChanges,
    List<String> warnings,
    Instant executedAt,
    String ciIdentity,
    String buildUrl
) {
  private static final Pattern CONTRACT_ID = Pattern.compile("^[a-zA-Z0-9._-]+$");
  private static final Pattern VERSION = Pattern.compile("^v[1-9][0-9]*$");
  private static final Pattern SHA256 = Pattern.compile("^[a-fA-F0-9]{64}$");

  public EvidenceImportRequest {
    evidenceFormatVersion = required("evidenceFormatVersion", evidenceFormatVersion);
    if (!"1.0".equals(evidenceFormatVersion)) {
      throw new IllegalArgumentException("evidenceFormatVersion must be 1.0.");
    }
    idempotencyKey = required("idempotencyKey", idempotencyKey);
    if (idempotencyKey.length() > 512) {
      throw new IllegalArgumentException("idempotencyKey must not exceed 512 characters.");
    }
    contractId = required("contractId", contractId);
    if (!CONTRACT_ID.matcher(contractId).matches()) {
      throw new IllegalArgumentException("contractId contains unsupported characters.");
    }
    baseVersion = version("baseVersion", baseVersion);
    candidateVersion = version("candidateVersion", candidateVersion);
    if (baseVersion.equals(candidateVersion)) {
      throw new IllegalArgumentException("baseVersion must differ from candidateVersion.");
    }
    compatibilityMode = mode(compatibilityMode);
    commitSha = optional(commitSha);
    baseSchemaSha256 = sha256("baseSchemaSha256", baseSchemaSha256);
    candidateSchemaSha256 = sha256("candidateSchemaSha256", candidateSchemaSha256);
    engineVersion = maximum("engineVersion", required("engineVersion", engineVersion), 255);
    engineCompatibilityProtocol = maximum(
        "engineCompatibilityProtocol", required("engineCompatibilityProtocol", engineCompatibilityProtocol), 64);
    policyPackName = maximum("policyPackName", required("policyPackName", policyPackName), 255);
    policyPackSha256 = sha256("policyPackSha256", policyPackSha256);
    localStatus = status(localStatus);
    breakingChanges = strings("breakingChanges", breakingChanges);
    warnings = strings("warnings", warnings);
    if (executedAt == null) {
      throw new IllegalArgumentException("executedAt must not be null.");
    }
    ciIdentity = maximum("ciIdentity", required("ciIdentity", ciIdentity), 512);
    buildUrl = optional(buildUrl);
    if (buildUrl != null && buildUrl.length() > 2048) {
      throw new IllegalArgumentException("buildUrl must not exceed 2048 characters.");
    }
  }

  private static String mode(String value) {
    String normalized = required("compatibilityMode", value).toUpperCase(Locale.ROOT);
    try {
      return CompatibilityMode.valueOf(normalized).name();
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("compatibilityMode must be BACKWARD, FORWARD, or FULL.");
    }
  }

  private static String status(String value) {
    String normalized = required("localStatus", value).toUpperCase(Locale.ROOT);
    try {
      return CheckStatus.valueOf(normalized).name();
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("localStatus must be PASS or FAIL.");
    }
  }

  private static String version(String field, String value) {
    String normalized = required(field, value);
    if (!VERSION.matcher(normalized).matches()) {
      throw new IllegalArgumentException(field + " must match v{number}.");
    }
    return normalized;
  }

  private static String sha256(String field, String value) {
    String normalized = required(field, value);
    if (!SHA256.matcher(normalized).matches()) {
      throw new IllegalArgumentException(field + " must be a SHA-256 hex digest.");
    }
    return normalized.toLowerCase(Locale.ROOT);
  }

  private static List<String> strings(String field, List<String> values) {
    if (values == null) {
      return List.of();
    }
    if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
      throw new IllegalArgumentException(field + " cannot contain blank entries.");
    }
    return List.copyOf(values);
  }

  private static String required(String field, String value) {
    String normalized = optional(value);
    if (normalized == null) {
      throw new IllegalArgumentException(field + " must not be blank.");
    }
    return normalized;
  }

  private static String maximum(String field, String value, int maximum) {
    if (value.length() > maximum) {
      throw new IllegalArgumentException(field + " must not exceed " + maximum + " characters.");
    }
    return value;
  }

  private static String optional(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
