package com.ideas.contracts.service;

import java.util.regex.Pattern;

/**
 * Canonical object-key strategy for future remote artifact stores.
 *
 * <p>The current filesystem store keeps {@code v1.json} directly under the contract directory.
 * S3 keys use a version prefix so each version can later carry schema, checksum, signature, and
 * generated artifacts without changing the public contract id/version model.
 */
public final class ArtifactKeyStrategy {
  private static final Pattern CONTRACT_ID_PATTERN = Pattern.compile("^[a-z0-9]+(\\.[a-z0-9]+)*$");
  private static final Pattern VERSION_PATTERN = Pattern.compile("^v[1-9][0-9]*$");

  private final String rootPrefix;

  public ArtifactKeyStrategy() {
    this("contracts");
  }

  public ArtifactKeyStrategy(String rootPrefix) {
    this.rootPrefix = normalizePrefix(rootPrefix);
  }

  public String rootPrefix() {
    return rootPrefix;
  }

  public String contractPrefix(String contractId) {
    return rootPrefix + "/" + normalizeContractId(contractId);
  }

  public String metadataKey(String contractId) {
    return contractPrefix(contractId) + "/metadata.yaml";
  }

  public String versionPrefix(String contractId, String version) {
    return contractPrefix(contractId) + "/versions/" + normalizeVersion(version);
  }

  public String schemaKey(String contractId, String version) {
    return versionPrefix(contractId, version) + "/schema.json";
  }

  public String checksumKey(String contractId, String version) {
    return versionPrefix(contractId, version) + "/schema.sha256";
  }

  private String normalizePrefix(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("artifact root prefix must not be blank.");
    }
    String normalized = value.trim().replace('\\', '/');
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    if (normalized.isBlank()
        || normalized.contains("//")
        || normalized.contains("..")
        || normalized.startsWith(".")) {
      throw new IllegalArgumentException("artifact root prefix must be a safe relative object prefix.");
    }
    return normalized;
  }

  private String normalizeContractId(String contractId) {
    if (contractId == null || contractId.isBlank()) {
      throw new IllegalArgumentException("contractId must not be blank.");
    }
    String normalized = contractId.trim();
    if (!CONTRACT_ID_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException("contractId must use lowercase dot-separated format.");
    }
    return normalized;
  }

  private String normalizeVersion(String version) {
    if (version == null || version.isBlank()) {
      throw new IllegalArgumentException("version must not be blank.");
    }
    String candidate = version.trim();
    String normalized = candidate.endsWith(".json") ? candidate.substring(0, candidate.length() - 5) : candidate;
    if (!VERSION_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException("version must match the format v{number}.");
    }
    return normalized;
  }
}
