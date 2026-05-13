package com.ideas.contracts.service;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
@ConditionalOnProperty(prefix = "contracts.artifact", name = "backend", havingValue = "s3")
public class S3ArtifactStoreConfig {

  @Bean
  public S3Client s3Client(
      @Value("${contracts.artifact.s3.region:us-east-1}") String region,
      @Value("${contracts.artifact.s3.endpoint:}") String endpoint,
      @Value("${contracts.artifact.s3.path-style:false}") boolean pathStyle,
      @Value("${contracts.artifact.s3.access-key:}") String accessKey,
      @Value("${contracts.artifact.s3.secret-key:}") String secretKey) {
    S3ClientBuilder builder = S3Client.builder()
        .region(Region.of(region == null || region.isBlank() ? "us-east-1" : region.trim()))
        .serviceConfiguration(
            S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());

    String normalizedAccessKey = accessKey == null ? "" : accessKey.trim();
    String normalizedSecretKey = secretKey == null ? "" : secretKey.trim();
    if (!normalizedAccessKey.isBlank() && !normalizedSecretKey.isBlank()) {
      builder.credentialsProvider(
          StaticCredentialsProvider.create(
              AwsBasicCredentials.create(normalizedAccessKey, normalizedSecretKey)));
    } else {
      builder.credentialsProvider(DefaultCredentialsProvider.create());
    }

    if (endpoint != null && !endpoint.isBlank()) {
      builder.endpointOverride(URI.create(endpoint.trim()));
    }
    return builder.build();
  }
}
