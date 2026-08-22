package com.ideas.contracts.maven;

import com.ideas.contracts.build.CompatibilityBuildRequest;
import com.ideas.contracts.build.CompatibilityBuildResult;
import com.ideas.contracts.build.OfflineCompatibilityWorkflow;
import com.ideas.contracts.build.RemoteReportStatus;
import com.ideas.contracts.build.RemoteReportingMode;
import com.ideas.contracts.core.CompatibilityMode;
import java.io.File;
import java.time.Duration;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Offline-first schema compatibility gate for Maven builds. */
@Mojo(name = "check-compat", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class CheckCompatibilityMojo extends AbstractMojo {
  @Parameter(property = "dcg.baseSchema", required = true)
  private File baseSchema;

  @Parameter(property = "dcg.candidateSchema", required = true)
  private File candidateSchema;

  @Parameter(property = "dcg.mode", defaultValue = "BACKWARD")
  private String mode;

  @Parameter(property = "dcg.reportFile", defaultValue = "${project.build.directory}/dcg-compatibility-report.json")
  private File reportFile;

  @Parameter(property = "dcg.remoteReportingMode", defaultValue = "DISABLED")
  private String remoteReportingMode;

  @Parameter(property = "dcg.remoteServiceUrl")
  private String remoteServiceUrl;

  @Parameter(property = "dcg.contractId")
  private String contractId;

  @Parameter(property = "dcg.commitSha")
  private String commitSha;

  @Parameter(property = "dcg.triggeredBy", defaultValue = "maven-plugin")
  private String triggeredBy;

  @Parameter(property = "dcg.remoteTimeoutSeconds", defaultValue = "5")
  private long remoteTimeoutSeconds;

  @Parameter(property = "dcg.remoteMaxAttempts", defaultValue = "2")
  private int remoteMaxAttempts;

  @Parameter(property = "dcg.ciIdentity")
  private String ciIdentity;

  @Parameter(property = "dcg.buildUrl")
  private String buildUrl;

  /** Complete Authorization header value, normally sourced from CI secret injection. */
  @Parameter(property = "dcg.remoteAuthorization")
  private String remoteAuthorization;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    try {
      CompatibilityBuildResult result = new OfflineCompatibilityWorkflow().run(new CompatibilityBuildRequest(
          requiredFile("dcg.baseSchema", baseSchema).toPath(),
          requiredFile("dcg.candidateSchema", candidateSchema).toPath(),
          CompatibilityMode.valueOf(mode.trim().toUpperCase()),
          requiredFile("dcg.reportFile", reportFile).toPath(),
          contractId,
          commitSha,
          triggeredBy,
          remoteServiceUrl,
          RemoteReportingMode.parse(remoteReportingMode),
          Duration.ofSeconds(remoteTimeoutSeconds),
          remoteMaxAttempts,
          ciIdentity,
          buildUrl,
          remoteAuthorization));
      getLog().info("DCG local compatibility: " + result.status());
      getLog().info("DCG evidence report: " + result.reportFile());
      if (result.remoteReportStatus() == RemoteReportStatus.FAILED_OPTIONAL) {
        getLog().warn("DCG optional evidence import was not accepted: " + result.remoteReportMessage());
      }
      if (!result.compatible()) {
        throw new MojoFailureException("DCG compatibility failed: " + result.breakingChanges());
      }
    } catch (MojoFailureException ex) {
      throw ex;
    } catch (IllegalArgumentException ex) {
      throw new MojoFailureException("Invalid DCG Maven plugin configuration: " + ex.getMessage(), ex);
    } catch (RuntimeException ex) {
      throw new MojoExecutionException(
          "DCG execution failed after local evidence was written when possible: " + ex.getMessage(), ex);
    }
  }

  private File requiredFile(String name, File value) {
    if (value == null) {
      throw new IllegalArgumentException(name + " is required.");
    }
    return value;
  }
}
