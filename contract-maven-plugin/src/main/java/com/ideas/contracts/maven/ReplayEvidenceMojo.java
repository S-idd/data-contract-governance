package com.ideas.contracts.maven;

import com.ideas.contracts.build.EvidenceReplayWorkflow;
import java.io.File;
import java.time.Duration;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Replays a previously-written immutable DCG evidence JSON artifact. */
@Mojo(name = "replay-evidence", threadSafe = true)
public class ReplayEvidenceMojo extends AbstractMojo {
  @Parameter(property = "dcg.evidenceFile", required = true)
  private File evidenceFile;

  @Parameter(property = "dcg.remoteServiceUrl", required = true)
  private String remoteServiceUrl;

  @Parameter(property = "dcg.remoteAuthorization")
  private String remoteAuthorization;

  @Parameter(property = "dcg.remoteTimeoutSeconds", defaultValue = "5")
  private long remoteTimeoutSeconds;

  @Parameter(property = "dcg.remoteMaxAttempts", defaultValue = "2")
  private int remoteMaxAttempts;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (evidenceFile == null || !evidenceFile.isFile()) {
      throw new MojoFailureException("dcg.evidenceFile must point to an existing JSON evidence artifact.");
    }
    try {
      new EvidenceReplayWorkflow().replay(
          evidenceFile.toPath(), remoteServiceUrl, remoteAuthorization,
          Duration.ofSeconds(remoteTimeoutSeconds), remoteMaxAttempts);
      getLog().info("DCG evidence replay accepted: " + evidenceFile);
    } catch (IllegalArgumentException exception) {
      throw new MojoFailureException("Invalid DCG evidence replay configuration: " + exception.getMessage(), exception);
    } catch (RuntimeException exception) {
      throw new MojoExecutionException("DCG evidence replay failed: " + exception.getMessage(), exception);
    }
  }
}
