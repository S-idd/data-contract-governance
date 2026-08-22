package com.ideas.contracts.build;

import java.nio.file.Path;
import java.time.Duration;

/** Replays an unchanged evidence artifact after a transient reporting outage. */
public class EvidenceReplayWorkflow {
  private final RemoteFollowUpReporter reporter;

  public EvidenceReplayWorkflow() {
    this(new RemoteFollowUpReporter());
  }

  EvidenceReplayWorkflow(RemoteFollowUpReporter reporter) {
    this.reporter = reporter;
  }

  public void replay(
      Path evidenceFile,
      String remoteServiceUrl,
      String remoteAuthorization,
      Duration remoteTimeout,
      int remoteMaxAttempts) {
    reporter.reportArtifact(
        remoteServiceUrl, remoteAuthorization, remoteTimeout, remoteMaxAttempts, evidenceFile);
  }
}
