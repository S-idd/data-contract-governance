package com.ideas.contracts.build;

import com.ideas.contracts.core.CheckStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record CompatibilityBuildResult(
    CheckStatus status,
    List<String> breakingChanges,
    List<String> warnings,
    String mode,
    Path baseSchema,
    Path candidateSchema,
    Instant completedAt,
    Path reportFile,
    RemoteReportStatus remoteReportStatus,
    String remoteReportMessage
) {
  public CompatibilityBuildResult {
    breakingChanges = breakingChanges == null ? List.of() : List.copyOf(breakingChanges);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }

  public boolean compatible() {
    return status == CheckStatus.PASS;
  }
}
