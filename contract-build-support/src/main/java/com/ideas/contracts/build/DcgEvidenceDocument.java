package com.ideas.contracts.build;

import com.ideas.contracts.core.CompatibilityEngineIdentity;
import com.ideas.contracts.core.PolicyPackDefaults;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The immutable, replayable wire document accepted by {@code POST /checks/evidence}. */
public record DcgEvidenceDocument(
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
  public DcgEvidenceDocument {
    breakingChanges = breakingChanges == null ? List.of() : List.copyOf(breakingChanges);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }

  static DcgEvidenceDocument from(CompatibilityBuildRequest request, CompatibilityBuildResult result) {
    try {
      return new DcgEvidenceDocument(
          "1.0",
          UUID.randomUUID().toString(),
          blankToNull(request.contractId()),
          versionName(request.baseSchema()),
          versionName(request.candidateSchema()),
          request.compatibilityMode().name(),
          blankToNull(request.commitSha()),
          CompatibilityEngineIdentity.sha256(Files.readAllBytes(request.baseSchema())),
          CompatibilityEngineIdentity.sha256(Files.readAllBytes(request.candidateSchema())),
          CompatibilityEngineIdentity.engineVersion(),
          CompatibilityEngineIdentity.COMPATIBILITY_PROTOCOL,
          PolicyPackDefaults.BASELINE_NAME,
          CompatibilityEngineIdentity.policyPackSha256(PolicyPackDefaults.baselinePack()),
          result.status().name(),
          result.breakingChanges(),
          result.warnings(),
          result.completedAt(),
          defaultIfBlank(request.ciIdentity(), request.triggeredBy(), "build-plugin"),
          blankToNull(request.buildUrl()));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to create evidence digest from schema files.", exception);
    }
  }

  private static String versionName(Path schema) {
    String fileName = schema.getFileName().toString();
    String version = fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
    return version;
  }

  private static String defaultIfBlank(String value, String fallback, String defaultValue) {
    String normalized = blankToNull(value);
    return normalized == null ? defaultIfBlank(fallback, defaultValue, defaultValue) : normalized;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
