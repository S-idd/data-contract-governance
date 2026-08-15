package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class S3ProductionProfileConfigurationTest {

  @Test
  void productionProfilesDefaultS3FallbackToDisabled() throws IOException {
    assertEquals("${CONTRACTS_ARTIFACT_S3_FALLBACK_ENABLED:false}",
        loadProfile("application-prod.properties").getProperty("contracts.artifact.s3.fallback-enabled"));
    assertEquals("${CONTRACTS_ARTIFACT_S3_FALLBACK_ENABLED:false}",
        loadProfile("application-sqlite-prod-lite.properties")
            .getProperty("contracts.artifact.s3.fallback-enabled"));
  }

  private Properties loadProfile(String resourceName) throws IOException {
    Properties properties = new Properties();
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resourceName)) {
      if (stream == null) {
        throw new IOException("Missing profile resource: " + resourceName);
      }
      properties.load(stream);
    }
    return properties;
  }
}
