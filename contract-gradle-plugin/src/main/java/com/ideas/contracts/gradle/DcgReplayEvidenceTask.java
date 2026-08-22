package com.ideas.contracts.gradle;

import com.ideas.contracts.build.EvidenceReplayWorkflow;
import java.io.File;
import java.time.Duration;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.TaskAction;

/** Replays a persisted evidence artifact without re-running the local compatibility check. */
public abstract class DcgReplayEvidenceTask extends DefaultTask {
  private DcgCompatibilityExtension extension;

  void setExtension(DcgCompatibilityExtension extension) {
    this.extension = extension;
  }

  @TaskAction
  public void replayEvidence() {
    File evidenceFile = extension.getEvidenceFile() == null
        ? extension.getReportFile() : extension.getEvidenceFile();
    if (evidenceFile == null || !evidenceFile.isFile()) {
      throw new GradleException("dcgCompatibility.evidenceFile must point to an existing JSON evidence artifact.");
    }
    try {
      new EvidenceReplayWorkflow().replay(
          evidenceFile.toPath(), extension.getRemoteServiceUrl(), extension.getRemoteAuthorization(),
          Duration.ofSeconds(extension.getRemoteTimeoutSeconds()), extension.getRemoteMaxAttempts());
      getLogger().lifecycle("DCG evidence replay accepted: {}", evidenceFile);
    } catch (Exception exception) {
      throw new GradleException("DCG evidence replay failed: " + exception.getMessage(), exception);
    }
  }
}
