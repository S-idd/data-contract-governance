package com.ideas.contracts.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ArtifactStoreBackendSelectionTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(
          FilesystemArtifactStore.class,
          S3ArtifactStore.class,
          S3ArtifactStoreConfig.class);

  @Test
  void filesystemBackendLoadsFilesystemArtifactStore() {
    contextRunner
        .withPropertyValues("contracts.artifact.backend=filesystem")
        .run(context -> {
          assertThat(context).hasSingleBean(ArtifactStore.class);
          assertThat(context.getBean(ArtifactStore.class)).isInstanceOf(FilesystemArtifactStore.class);
        });
  }

  @Test
  void s3BackendLoadsS3ArtifactStore() {
    contextRunner
        .withPropertyValues(
            "contracts.artifact.backend=s3",
            "contracts.artifact.s3.bucket=dcg-test-artifacts",
            "contracts.artifact.s3.access-key=test-access",
            "contracts.artifact.s3.secret-key=test-secret")
        .run(context -> {
          assertThat(context).hasSingleBean(ArtifactStore.class);
          assertThat(context.getBean(ArtifactStore.class)).isInstanceOf(S3ArtifactStore.class);
        });
  }
}
