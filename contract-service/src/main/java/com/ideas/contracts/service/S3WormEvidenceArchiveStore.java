package com.ideas.contracts.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/** Dedicated S3 Object-Lock archive. It must use a bucket created with Object Lock enabled. */
public final class S3WormEvidenceArchiveStore implements EvidenceArchiveStore {
  private static final int MINIMUM_RETENTION_DAYS = 2555;

  private final S3Client s3;
  private final String bucket;
  private final String prefix;
  private final String expectedBucketOwner;
  private final int retentionDays;

  public S3WormEvidenceArchiveStore(S3Client s3, EvidenceArchiveProperties.S3 properties) {
    if (s3 == null || properties == null) {
      throw new IllegalArgumentException("S3 archive client and configuration must not be null.");
    }
    this.s3 = s3;
    this.bucket = required("checks.evidence.archive.s3.bucket", properties.getBucket());
    this.prefix = normalizePrefix(properties.getPrefix());
    this.expectedBucketOwner = optional(properties.getExpectedBucketOwner());
    this.retentionDays = properties.getRetentionDays();
    if (retentionDays < MINIMUM_RETENTION_DAYS) {
      throw new IllegalArgumentException(
          "checks.evidence.archive.s3.retention-days must be at least " + MINIMUM_RETENTION_DAYS + ".");
    }
  }

  @Override
  public EvidenceArchiveReceipt archive(CheckEvidence evidence) {
    if (evidence == null) {
      throw new IllegalArgumentException("evidence must not be null.");
    }
    byte[] payload = evidence.rawEvidence().getBytes(StandardCharsets.UTF_8);
    String digest = sha256(payload);
    if (!digest.equals(evidence.payloadSha256())) {
      throw new IllegalStateException("Evidence payload checksum does not match its imported digest.");
    }
    String key = keyFor(evidence.evidenceId());
    try {
      try {
        var existing = s3.headObject(HeadObjectRequest.builder()
            .bucket(bucket).key(key).expectedBucketOwner(expectedBucketOwner).build());
        return verifiedReceipt(evidence.evidenceId(), key, existing.versionId(), digest);
      } catch (NoSuchKeyException ignored) {
        // The object does not exist yet.
      } catch (S3Exception error) {
        if (error.statusCode() != 404) {
          throw error;
        }
      }

      Instant retainUntil = Instant.now().plus(retentionDays, ChronoUnit.DAYS);
      PutObjectResponse stored = s3.putObject(PutObjectRequest.builder()
              .bucket(bucket)
              .key(key)
              .contentType("application/json")
              .checksumSHA256(Base64.getEncoder().encodeToString(hexToBytes(digest)))
              .metadata(Map.of("payload-sha256", digest, "evidence-id", evidence.evidenceId()))
              .objectLockMode(ObjectLockMode.COMPLIANCE)
              .objectLockRetainUntilDate(retainUntil)
              .expectedBucketOwner(expectedBucketOwner)
              .build(),
          RequestBody.fromBytes(payload));
      return verifiedReceipt(evidence.evidenceId(), key, stored.versionId(), digest);
    } catch (RuntimeException error) {
      throw new IllegalStateException("WORM evidence archive operation failed; raw evidence remains intact.", error);
    }
  }

  @Override
  public void verifyReadyForRetention() {
    try {
      var response = s3.getObjectLockConfiguration(GetObjectLockConfigurationRequest.builder()
          .bucket(bucket).expectedBucketOwner(expectedBucketOwner).build());
      if (response.objectLockConfiguration() == null
          || response.objectLockConfiguration().objectLockEnabled() != ObjectLockEnabled.ENABLED) {
        throw new IllegalStateException("Evidence archive bucket does not have S3 Object Lock enabled.");
      }
    } catch (RuntimeException error) {
      if (error instanceof IllegalStateException) {
        throw error;
      }
      throw new IllegalStateException("Unable to validate WORM evidence archive readiness.", error);
    }
  }

  @Override
  public boolean wormCapable() {
    return true;
  }

  @Override
  public String backend() {
    return "s3-worm";
  }

  private EvidenceArchiveReceipt verifiedReceipt(
      String evidenceId, String key, String versionId, String expectedDigest) {
    if (versionId == null || versionId.isBlank()) {
      throw new IllegalStateException("S3 Object Lock archive requires bucket versioning and a returned version ID.");
    }
    ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(GetObjectRequest.builder()
        .bucket(bucket).key(key).versionId(versionId).expectedBucketOwner(expectedBucketOwner).build());
    String actualDigest = sha256(object.asByteArray());
    if (!expectedDigest.equals(actualDigest)) {
      throw new IllegalStateException("S3 archive checksum verification failed.");
    }
    return new EvidenceArchiveReceipt(
        evidenceId, "s3://" + bucket + "/" + key + "?versionId=" + versionId, actualDigest, Instant.now());
  }

  private String keyFor(String evidenceId) {
    if (evidenceId == null || !evidenceId.matches("^[a-zA-Z0-9._-]+$")) {
      throw new IllegalArgumentException("evidenceId contains unsupported archive-key characters.");
    }
    return prefix + evidenceId + ".json";
  }

  private static String required(String name, String value) {
    String normalized = optional(value);
    if (normalized == null) {
      throw new IllegalArgumentException(name + " must not be blank.");
    }
    return normalized;
  }

  private static String optional(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String normalizePrefix(String prefix) {
    String normalized = optional(prefix);
    if (normalized == null) {
      return "evidence/";
    }
    if (!normalized.matches("^[a-zA-Z0-9._/-]+$") || normalized.contains("..")) {
      throw new IllegalArgumentException("checks.evidence.archive.s3.prefix contains unsupported characters.");
    }
    return normalized.endsWith("/") ? normalized : normalized + "/";
  }

  private static byte[] hexToBytes(String hex) {
    return HexFormat.of().parseHex(hex);
  }

  private static String sha256(byte[] payload) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is not available in this Java runtime.", error);
    }
  }
}
