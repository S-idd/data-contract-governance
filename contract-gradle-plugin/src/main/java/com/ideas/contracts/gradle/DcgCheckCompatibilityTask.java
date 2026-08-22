package com.ideas.contracts.gradle;

import com.ideas.contracts.build.CompatibilityBuildRequest;
import com.ideas.contracts.build.CompatibilityBuildResult;
import com.ideas.contracts.build.OfflineCompatibilityWorkflow;
import com.ideas.contracts.build.RemoteReportStatus;
import com.ideas.contracts.build.RemoteReportingMode;
import com.ideas.contracts.core.CompatibilityMode;
import java.io.File;
import java.time.Duration;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.TaskAction;

public abstract class DcgCheckCompatibilityTask extends DefaultTask {
  private DcgCompatibilityExtension extension;

  void setExtension(DcgCompatibilityExtension extension) {
    this.extension = extension;
  }

  @TaskAction
  public void checkCompatibility() {
    try {
      CompatibilityBuildResult result = new OfflineCompatibilityWorkflow().run(new CompatibilityBuildRequest(
          required("baseSchema", extension.getBaseSchema()).toPath(),
          required("candidateSchema", extension.getCandidateSchema()).toPath(),
          CompatibilityMode.valueOf(extension.getMode().trim().toUpperCase()),
          required("reportFile", extension.getReportFile()).toPath(),
          extension.getContractId(), extension.getCommitSha(), extension.getTriggeredBy(),
          extension.getRemoteServiceUrl(), RemoteReportingMode.parse(extension.getRemoteReportingMode()),
          Duration.ofSeconds(extension.getRemoteTimeoutSeconds()), extension.getRemoteMaxAttempts(),
          extension.getCiIdentity(), extension.getBuildUrl(), extension.getRemoteAuthorization()));
      getLogger().lifecycle("DCG local compatibility: {}", result.status());
      getLogger().lifecycle("DCG evidence report: {}", result.reportFile());
      if (result.remoteReportStatus() == RemoteReportStatus.FAILED_OPTIONAL) {
        getLogger().warn("DCG optional evidence import was not accepted: {}", result.remoteReportMessage());
      }
      if (!result.compatible()) {
        throw new GradleException("DCG compatibility failed: " + result.breakingChanges());
      }
    } catch (GradleException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new GradleException("DCG compatibility task failed: " + ex.getMessage(), ex);
    }
  }

  private File required(String name, File file) {
    if (file == null) {
      throw new GradleException("dcgCompatibility." + name + " is required.");
    }
    return file;
  }
}
