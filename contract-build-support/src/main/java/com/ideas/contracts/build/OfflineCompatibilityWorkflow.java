package com.ideas.contracts.build;

import com.ideas.contracts.core.CompatibilityResult;
import com.ideas.contracts.core.ContractEngine;
import com.ideas.contracts.core.DefaultContractEngine;
import java.time.Instant;

/** Runs the local gate first, writes evidence, then optionally contacts DCG. */
public class OfflineCompatibilityWorkflow {
  private final ContractEngine contractEngine;
  private final CompatibilityReportWriter reportWriter;
  private final RemoteFollowUpReporter remoteReporter;

  public OfflineCompatibilityWorkflow() {
    this(new DefaultContractEngine(), new CompatibilityReportWriter(), new RemoteFollowUpReporter());
  }

  OfflineCompatibilityWorkflow(
      ContractEngine contractEngine,
      CompatibilityReportWriter reportWriter,
      RemoteFollowUpReporter remoteReporter) {
    this.contractEngine = contractEngine;
    this.reportWriter = reportWriter;
    this.remoteReporter = remoteReporter;
  }

  public CompatibilityBuildResult run(CompatibilityBuildRequest request) {
    CompatibilityResult local = contractEngine.checkCompatibility(
        request.baseSchema(), request.candidateSchema(), request.compatibilityMode());
    CompatibilityBuildResult localResult = new CompatibilityBuildResult(
        local.status(),
        local.breakingChanges(),
        local.warnings(),
        request.compatibilityMode().name(),
        request.baseSchema().toAbsolutePath(),
        request.candidateSchema().toAbsolutePath(),
        Instant.now(),
        request.reportFile().toAbsolutePath(),
        RemoteReportStatus.NOT_REQUESTED,
        null);
    DcgEvidenceDocument evidence = DcgEvidenceDocument.from(request, localResult);
    reportWriter.write(request.reportFile(), evidence);

    if (request.remoteReportingMode() == RemoteReportingMode.DISABLED) {
      return localResult;
    }
    try {
      remoteReporter.report(request, request.reportFile());
      CompatibilityBuildResult result = withRemoteStatus(
          localResult, RemoteReportStatus.ACCEPTED, "Remote follow-up check accepted.");
      return result;
    } catch (RuntimeException ex) {
      RemoteReportStatus status = request.remoteReportingMode() == RemoteReportingMode.REQUIRED
          ? RemoteReportStatus.FAILED_REQUIRED
          : RemoteReportStatus.FAILED_OPTIONAL;
      CompatibilityBuildResult result = withRemoteStatus(localResult, status, ex.getMessage());
      if (status == RemoteReportStatus.FAILED_REQUIRED) {
        throw ex;
      }
      return result;
    }
  }

  private CompatibilityBuildResult withRemoteStatus(
      CompatibilityBuildResult result, RemoteReportStatus status, String message) {
    return new CompatibilityBuildResult(
        result.status(), result.breakingChanges(), result.warnings(), result.mode(), result.baseSchema(),
        result.candidateSchema(), result.completedAt(), result.reportFile(), status, message);
  }
}
