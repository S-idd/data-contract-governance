package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class EvidenceArchiveConfigurationTest {
  @Test
  void filesystemArchiveFailsClosedInProductionProfile() {
    EvidenceArchiveProperties properties = new EvidenceArchiveProperties();
    properties.setMode(EvidenceArchiveProperties.Mode.FILESYSTEM);
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");

    EvidenceArchiveConfiguration configuration = new EvidenceArchiveConfiguration(
        properties, retentionProperties(), environment);

    assertThrows(IllegalStateException.class, configuration::validateProductionArchiveConfiguration);
  }

  @Test
  void filesystemArchiveIsAllowedForLocalRehearsal() {
    EvidenceArchiveProperties properties = new EvidenceArchiveProperties();
    properties.setMode(EvidenceArchiveProperties.Mode.FILESYSTEM);
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("local");

    assertDoesNotThrow(
        () -> new EvidenceArchiveConfiguration(
            properties, retentionProperties(), environment).validateProductionArchiveConfiguration());
  }

  @Test
  void productionDeletionFailsClosedWithoutWormArchive() {
    EvidenceRetentionProperties retention = retentionProperties();
    retention.setEnabled(true);
    retention.setDryRun(false);
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");

    EvidenceArchiveConfiguration configuration = new EvidenceArchiveConfiguration(
        new EvidenceArchiveProperties(), retention, environment);

    assertThrows(IllegalStateException.class, configuration::validateProductionArchiveConfiguration);
  }

  @Test
  void productionWormArchiveRequiresBoundedAwsIdentityConfiguration() {
    EvidenceArchiveProperties properties = new EvidenceArchiveProperties();
    properties.setMode(EvidenceArchiveProperties.Mode.S3_WORM);
    properties.getS3().setBucket("dcg-evidence-archive");
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");

    EvidenceArchiveConfiguration missingOwner = new EvidenceArchiveConfiguration(
        properties, retentionProperties(), environment);
    assertThrows(IllegalStateException.class, missingOwner::validateProductionArchiveConfiguration);

    properties.getS3().setExpectedBucketOwner("123456789012");
    properties.getS3().setEndpoint("http://localhost:4566");
    EvidenceArchiveConfiguration endpointOverride = new EvidenceArchiveConfiguration(
        properties, retentionProperties(), environment);
    assertThrows(IllegalStateException.class, endpointOverride::validateProductionArchiveConfiguration);

    properties.getS3().setEndpoint(null);
    properties.getS3().setAccessKey("AKIAEXAMPLE");
    properties.getS3().setSecretKey("example-secret");
    EvidenceArchiveConfiguration staticCredentials = new EvidenceArchiveConfiguration(
        properties, retentionProperties(), environment);
    assertThrows(IllegalStateException.class, staticCredentials::validateProductionArchiveConfiguration);
  }

  private EvidenceRetentionProperties retentionProperties() {
    return new EvidenceRetentionProperties();
  }
}
