package com.ideas.contracts.build;

import com.ideas.contracts.core.CompatibilityMode;
import java.nio.file.Path;
import java.time.Duration;

public record CompatibilityBuildRequest(
    Path baseSchema,
    Path candidateSchema,
    CompatibilityMode compatibilityMode,
    Path reportFile,
    String contractId,
    String commitSha,
    String triggeredBy,
    String remoteServiceUrl,
    RemoteReportingMode remoteReportingMode,
    Duration remoteTimeout,
    int remoteMaxAttempts,
    String ciIdentity,
    String buildUrl,
    String remoteAuthorization
) {
  public CompatibilityBuildRequest {
    if (baseSchema == null || candidateSchema == null) {
      throw new IllegalArgumentException("baseSchema and candidateSchema are required.");
    }
    compatibilityMode = compatibilityMode == null ? CompatibilityMode.BACKWARD : compatibilityMode;
    if (reportFile == null) {
      throw new IllegalArgumentException("reportFile is required.");
    }
    remoteReportingMode = remoteReportingMode == null ? RemoteReportingMode.DISABLED : remoteReportingMode;
    remoteTimeout = remoteTimeout == null ? Duration.ofSeconds(5) : remoteTimeout;
    if (remoteTimeout.isNegative() || remoteTimeout.isZero()) {
      throw new IllegalArgumentException("remoteTimeout must be positive.");
    }
    if (remoteMaxAttempts < 1) {
      throw new IllegalArgumentException("remoteMaxAttempts must be at least 1.");
    }
  }
}
