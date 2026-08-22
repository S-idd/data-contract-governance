package com.ideas.contracts.service;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

/** Refuses the non-immutable rehearsal target in production profiles. */
@Configuration
public class EvidenceArchiveConfiguration {
  private final EvidenceArchiveProperties properties;
  private final EvidenceRetentionProperties retentionProperties;
  private final Environment environment;

  public EvidenceArchiveConfiguration(
      EvidenceArchiveProperties properties,
      EvidenceRetentionProperties retentionProperties,
      Environment environment) {
    this.properties = properties;
    this.retentionProperties = retentionProperties;
    this.environment = environment;
  }

  @PostConstruct
  void validateProductionArchiveConfiguration() {
    if (!isProduction()) {
      return;
    }
    if (properties.getMode() == EvidenceArchiveProperties.Mode.FILESYSTEM) {
      throw new IllegalStateException(
          "checks.evidence.archive.mode=FILESYSTEM is rehearsal-only and is forbidden in production profiles.");
    }
    if (retentionProperties.isEnabled() && !retentionProperties.isDryRun()
        && properties.getMode() != EvidenceArchiveProperties.Mode.S3_WORM) {
      throw new IllegalStateException(
          "Production raw-evidence deletion requires checks.evidence.archive.mode=S3_WORM.");
    }
    if (properties.getMode() != EvidenceArchiveProperties.Mode.S3_WORM) {
      return;
    }
    EvidenceArchiveProperties.S3 s3 = properties.getS3();
    if (optional(s3.getExpectedBucketOwner()) == null) {
      throw new IllegalStateException(
          "Production S3 WORM archive requires checks.evidence.archive.s3.expected-bucket-owner.");
    }
    if (optional(s3.getEndpoint()) != null || s3.isPathStyle()) {
      throw new IllegalStateException(
          "Production S3 WORM archive forbids endpoint overrides and path-style addressing.");
    }
    if (optional(s3.getAccessKey()) != null || optional(s3.getSecretKey()) != null) {
      throw new IllegalStateException(
          "Production S3 WORM archive must use an IAM role or workload identity, not static access keys.");
    }
  }

  private boolean isProduction() {
    return environment.acceptsProfiles(Profiles.of("prod", "sqlite-prod-lite"));
  }

  @Bean
  @ConditionalOnProperty(prefix = "checks.evidence.archive", name = "mode", havingValue = "FILESYSTEM")
  EvidenceArchiveStore filesystemEvidenceArchiveStore() {
    String root = properties.getFilesystemRoot();
    if (root == null || root.isBlank()) {
      throw new IllegalStateException(
          "checks.evidence.archive.filesystem-root must be set for local archive rehearsal.");
    }
    return new FilesystemEvidenceArchiveStore(Path.of(root.trim()));
  }

  @Bean("evidenceArchiveS3Client")
  @ConditionalOnProperty(prefix = "checks.evidence.archive", name = "mode", havingValue = "S3_WORM")
  S3Client evidenceArchiveS3Client() {
    EvidenceArchiveProperties.S3 s3 = properties.getS3();
    String accessKey = optional(s3.getAccessKey());
    String secretKey = optional(s3.getSecretKey());
    if ((accessKey == null) != (secretKey == null)) {
      throw new IllegalArgumentException(
          "checks.evidence.archive.s3.access-key and secret-key must be configured together.");
    }
    S3ClientBuilder builder = S3Client.builder()
        .region(Region.of(optional(s3.getRegion()) == null ? "us-east-1" : s3.getRegion().trim()))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(s3.isPathStyle()).build())
        .credentialsProvider(accessKey == null
            ? DefaultCredentialsProvider.create()
            : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
    String endpoint = optional(s3.getEndpoint());
    if (endpoint != null) {
      URI uri = URI.create(endpoint);
      if (!uri.isAbsolute() || uri.getHost() == null
          || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
        throw new IllegalArgumentException("checks.evidence.archive.s3.endpoint must be an absolute http(s) URL.");
      }
      builder.endpointOverride(uri);
    }
    return builder.build();
  }

  @Bean
  @ConditionalOnProperty(prefix = "checks.evidence.archive", name = "mode", havingValue = "S3_WORM")
  EvidenceArchiveStore s3WormEvidenceArchiveStore(
      @Qualifier("evidenceArchiveS3Client") S3Client s3Client) {
    S3WormEvidenceArchiveStore archive = new S3WormEvidenceArchiveStore(s3Client, properties.getS3());
    archive.verifyReadyForRetention();
    return archive;
  }

  private static String optional(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
