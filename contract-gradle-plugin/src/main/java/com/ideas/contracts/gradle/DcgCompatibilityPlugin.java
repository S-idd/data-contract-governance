package com.ideas.contracts.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** Registers the offline-first dcgCheckCompatibility task. */
public class DcgCompatibilityPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    DcgCompatibilityExtension extension = project.getExtensions().create(
        "dcgCompatibility", DcgCompatibilityExtension.class);
    extension.setReportFile(project.getLayout().getBuildDirectory()
        .file("reports/dcg/compatibility.json").get().getAsFile());
    project.getTasks().register("dcgCheckCompatibility", DcgCheckCompatibilityTask.class, task -> {
      task.setGroup("verification");
      task.setDescription("Runs offline DCG schema compatibility and writes a JSON evidence report.");
      task.setExtension(extension);
    });
    project.getTasks().register("dcgReplayEvidence", DcgReplayEvidenceTask.class, task -> {
      task.setGroup("verification");
      task.setDescription("Replays an existing immutable DCG JSON evidence report.");
      task.setExtension(extension);
    });
  }
}
