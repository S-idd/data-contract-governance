package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class S3ArtifactStoreConfigTest {
  private final S3ArtifactStoreConfig configuration = new S3ArtifactStoreConfig();

  @Test
  void rejectsPartialStaticCredentialConfiguration() {
    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> configuration.s3Client("ap-south-1", "", false, "access-key-only", ""));

    assertTrue(error.getMessage().contains("access-key and contracts.artifact.s3.secret-key"));
  }

  @Test
  void rejectsNonHttpS3EndpointOverride() {
    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> configuration.s3Client("ap-south-1", "s3://local-bucket", true, "", ""));

    assertTrue(error.getMessage().contains("absolute http(s) URL"));
  }
}
