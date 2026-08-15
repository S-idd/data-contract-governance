package com.ideas.contracts.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.ideas.contracts.core.ContractMetadata;
import com.ideas.contracts.core.DefaultSchemaLoader;
import com.ideas.contracts.core.ExecutionException;
import com.ideas.contracts.core.SchemaLoader;
import com.ideas.contracts.service.model.CreateContractRequest;
import com.ideas.contracts.service.model.CreateContractVersionRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

@Service
@ConditionalOnProperty(prefix = "contracts.artifact", name = "backend", havingValue = "s3")
public class S3ArtifactStore implements ArtifactStore {
  private static final Logger LOGGER = LoggerFactory.getLogger(S3ArtifactStore.class);
  private static final Comparator<String> VERSION_COMPARATOR =
      Comparator.comparingInt(S3ArtifactStore::versionNumber);
  private static final Set<String> SUPPORTED_SERVER_SIDE_ENCRYPTION =
      Set.of("AES256", "aws:kms", "aws:kms:dsse");

  private final S3Client s3Client;
  private final String bucket;
  private final ArtifactKeyStrategy keyStrategy;
  private final FilesystemArtifactStore fallbackStore;
  private final boolean fallbackEnabled;
  private final boolean s3Enabled;
  private final ObjectMapper jsonMapper;
  private final SchemaLoader schemaLoader;
  private final String serverSideEncryption;
  private final String kmsKeyId;

  @Autowired
  public S3ArtifactStore(
      S3Client s3Client,
      @Value("${contracts.artifact.s3.bucket:}") String bucket,
      @Value("${contracts.artifact.s3.prefix:contracts}") String prefix,
      @Value("${contracts.artifact.s3.local-cache-root:${contracts.root:contracts}}") String localCacheRoot,
      @Value("${contracts.artifact.s3.fallback-enabled:true}") boolean fallbackEnabled,
      @Value("${contracts.artifact.s3.server-side-encryption:AES256}") String serverSideEncryption,
      @Value("${contracts.artifact.s3.kms-key-id:}") String kmsKeyId) {
    this(
        s3Client,
        bucket,
        new ArtifactKeyStrategy(prefix),
        new FilesystemArtifactStore(
            Paths.get(localCacheRoot),
            new DefaultSchemaLoader(),
            new ObjectMapper(),
            new YAMLMapper()),
        fallbackEnabled,
        serverSideEncryption,
        kmsKeyId,
        new ObjectMapper(),
        new DefaultSchemaLoader());
  }

  S3ArtifactStore(
      S3Client s3Client,
      String bucket,
      ArtifactKeyStrategy keyStrategy,
      FilesystemArtifactStore fallbackStore,
      boolean fallbackEnabled,
      String serverSideEncryption,
      String kmsKeyId,
      ObjectMapper jsonMapper,
      SchemaLoader schemaLoader) {
    this.s3Client = s3Client;
    this.bucket = bucket == null ? "" : bucket.trim();
    this.keyStrategy = keyStrategy;
    this.fallbackStore = fallbackStore;
    this.fallbackEnabled = fallbackEnabled;
    this.serverSideEncryption = normalizeOptional(serverSideEncryption);
    this.kmsKeyId = normalizeOptional(kmsKeyId);
    this.jsonMapper = jsonMapper;
    this.schemaLoader = schemaLoader;
    this.s3Enabled = !this.bucket.isBlank();
    if (!this.s3Enabled) {
      if (!fallbackEnabled) {
        throw new IllegalStateException(
            "contracts.artifact.s3.bucket must be set when S3 artifact backend fallback is disabled.");
      }
      LOGGER.warn(
          "event=artifact_store_s3_disabled component=s3_artifact_store message=S3 bucket is blank, using filesystem fallback");
    }
    if (!this.serverSideEncryption.isBlank()
        && !SUPPORTED_SERVER_SIDE_ENCRYPTION.contains(this.serverSideEncryption)) {
      throw new IllegalArgumentException(
          "contracts.artifact.s3.server-side-encryption must be one of: AES256, aws:kms, aws:kms:dsse.");
    }
    if (!this.kmsKeyId.isBlank() && !this.serverSideEncryption.startsWith("aws:kms")) {
      throw new IllegalArgumentException(
          "contracts.artifact.s3.kms-key-id requires contracts.artifact.s3.server-side-encryption=aws:kms or aws:kms:dsse.");
    }
  }

  @Override
  public List<String> listContracts() {
    if (!s3Enabled) {
      return fallbackStore.listContracts();
    }
    try {
      return listContractsFromS3();
    } catch (RuntimeException ex) {
      return fallbackOrThrow("list_contracts", "-", ex, fallbackStore::listContracts);
    }
  }

  @Override
  public Optional<ContractMetadata> readMetadata(String contractId) {
    String normalizedContractId = normalizeContractId(contractId);
    if (!s3Enabled) {
      return fallbackStore.readMetadata(normalizedContractId);
    }

    try {
      Path metadataPath = localMetadataPath(normalizedContractId);
      byte[] payload = getObjectBytes(keyStrategy.metadataKey(normalizedContractId));
      writeLocalFile(metadataPath, payload);
      return Optional.of(schemaLoader.loadMetadata(metadataPath, normalizedContractId));
    } catch (RuntimeException ex) {
      if (isNotFound(ex)) {
        return fallbackEnabled
            ? fallbackStore.readMetadata(normalizedContractId)
            : Optional.empty();
      }
      return fallbackOrThrow(
          "read_metadata",
          keyStrategy.metadataKey(normalizedContractId),
          ex,
          () -> fallbackStore.readMetadata(normalizedContractId));
    }
  }

  @Override
  public List<String> listVersions(String contractId) {
    String normalizedContractId = normalizeContractId(contractId);
    if (!s3Enabled) {
      return fallbackStore.listVersions(normalizedContractId);
    }
    try {
      return listVersionsFromS3(normalizedContractId);
    } catch (RuntimeException ex) {
      return fallbackOrThrow(
          "list_versions",
          normalizedContractId,
          ex,
          () -> fallbackStore.listVersions(normalizedContractId));
    }
  }

  @Override
  public Optional<JsonNode> readSchema(String contractId, String version) {
    String normalizedContractId = normalizeContractId(contractId);
    String normalizedVersion = normalizeVersion(normalizedContractId, version);
    if (!s3Enabled) {
      return fallbackStore.readSchema(normalizedContractId, normalizedVersion);
    }

    try {
      Path schemaPath = localSchemaPath(normalizedContractId, normalizedVersion);
      byte[] payload = getObjectBytes(keyStrategy.schemaKey(normalizedContractId, normalizedVersion));
      byte[] expectedChecksum = getSchemaChecksumBytes(normalizedContractId, normalizedVersion);
      verifySchemaChecksum(normalizedContractId, normalizedVersion, payload, expectedChecksum);
      writeLocalFile(schemaPath, payload);
      return Optional.of(jsonMapper.readTree(payload));
    } catch (RuntimeException ex) {
      if (isNotFound(ex)) {
        return fallbackEnabled
            ? fallbackStore.readSchema(normalizedContractId, normalizedVersion)
            : Optional.empty();
      }
      return fallbackOrThrow(
          "read_schema",
          keyStrategy.schemaKey(normalizedContractId, normalizedVersion),
          ex,
          () -> fallbackStore.readSchema(normalizedContractId, normalizedVersion));
    } catch (IOException ex) {
      throw new ExecutionException("Unable to parse schema JSON for " + normalizedContractId, ex);
    }
  }

  @Override
  public boolean contractExists(String contractId) {
    String normalizedContractId = normalizeContractId(contractId);
    if (!s3Enabled) {
      return fallbackStore.contractExists(normalizedContractId);
    }
    try {
      s3Client.headObject(
          HeadObjectRequest.builder()
              .bucket(bucket)
              .key(keyStrategy.metadataKey(normalizedContractId))
              .build());
      return true;
    } catch (RuntimeException ex) {
      if (isNotFound(ex)) {
        return fallbackEnabled && fallbackStore.contractExists(normalizedContractId);
      }
      return fallbackOrThrow(
          "contract_exists",
          keyStrategy.metadataKey(normalizedContractId),
          ex,
          () -> fallbackStore.contractExists(normalizedContractId));
    }
  }

  @Override
  public Path contractDirectory(String contractId) {
    return fallbackStore.contractDirectory(contractId);
  }

  @Override
  public Path schemaPath(String contractId, String version) {
    return fallbackStore.schemaPath(contractId, version);
  }

  @Override
  public long contractLastModified(String contractId) {
    String normalizedContractId = normalizeContractId(contractId);
    if (!s3Enabled) {
      return fallbackStore.contractLastModified(normalizedContractId);
    }
    try {
      String prefix = keyStrategy.contractPrefix(normalizedContractId) + "/";
      long max = 0L;
      String continuationToken = null;
      do {
        ListObjectsV2Response response = s3Client.listObjectsV2(
            ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .continuationToken(continuationToken)
                .build());
        for (S3Object object : response.contents()) {
          if (object.lastModified() != null) {
            max = Math.max(max, object.lastModified().toEpochMilli());
          }
        }
        continuationToken = response.nextContinuationToken();
      } while (continuationToken != null);
      return max == 0L && fallbackEnabled
          ? fallbackStore.contractLastModified(normalizedContractId)
          : max;
    } catch (RuntimeException ex) {
      return fallbackOrThrow(
          "contract_last_modified",
          normalizedContractId,
          ex,
          () -> fallbackStore.contractLastModified(normalizedContractId));
    }
  }

  @Override
  public void createContract(CreateContractRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null.");
    }
    fallbackStore.createContract(request);
    if (!s3Enabled) {
      return;
    }

    String normalizedContractId = normalizeContractId(request.contractId());
    String normalizedVersion = normalizeVersion(normalizedContractId, request.initialVersion());
    try {
      replicateContractMetadataToS3(normalizedContractId);
      replicateSchemaToS3(normalizedContractId, normalizedVersion);
    } catch (RuntimeException ex) {
      deleteS3ContractArtifactsQuietly(normalizedContractId);
      if (!fallbackEnabled) {
        fallbackStore.deleteContractIfExists(normalizedContractId);
        throw s3OperationFailure("create contract artifacts", ex);
      }
      logFallback("create_contract", normalizedContractId, ex);
    }
  }

  @Override
  public void createVersion(String contractId, CreateContractVersionRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null.");
    }
    String normalizedContractId = normalizeContractId(contractId);
    String normalizedVersion = normalizeVersion(normalizedContractId, request.version());

    fallbackStore.createVersion(normalizedContractId, request);
    if (!s3Enabled) {
      return;
    }
    try {
      replicateSchemaToS3(normalizedContractId, normalizedVersion);
    } catch (RuntimeException ex) {
      deleteS3VersionArtifactsQuietly(normalizedContractId, normalizedVersion);
      if (!fallbackEnabled) {
        fallbackStore.deleteVersionIfExists(normalizedContractId, normalizedVersion);
        throw s3OperationFailure("create version artifacts", ex);
      }
      logFallback("create_version", normalizedContractId + "/" + normalizedVersion, ex);
    }
  }

  @Override
  public void deleteContractIfExists(String contractId) {
    fallbackStore.deleteContractIfExists(contractId);
    if (!s3Enabled) {
      return;
    }
    try {
      deleteS3ContractArtifactsQuietly(normalizeContractId(contractId));
    } catch (RuntimeException ex) {
      LOGGER.debug(
          "event=artifact_store_s3_cleanup_failed component=s3_artifact_store operation=delete_contract key={} error_type={} error_message={}",
          contractId,
          ex.getClass().getSimpleName(),
          ex.getMessage());
    }
  }

  @Override
  public void deleteVersionIfExists(String contractId, String version) {
    fallbackStore.deleteVersionIfExists(contractId, version);
    if (!s3Enabled) {
      return;
    }
    try {
      String normalizedContractId = normalizeContractId(contractId);
      String normalizedVersion = normalizeVersion(normalizedContractId, version);
      deleteS3VersionArtifactsQuietly(normalizedContractId, normalizedVersion);
    } catch (RuntimeException ex) {
      LOGGER.debug(
          "event=artifact_store_s3_cleanup_failed component=s3_artifact_store operation=delete_version key={}/{} error_type={} error_message={}",
          contractId,
          version,
          ex.getClass().getSimpleName(),
          ex.getMessage());
    }
  }

  @Override
  public ArtifactReference metadataReference(String contractId) {
    String normalizedContractId = normalizeContractId(contractId);
    if (s3Enabled) {
      return new ArtifactReference("s3", keyStrategy.metadataKey(normalizedContractId));
    }
    return ArtifactStore.super.metadataReference(normalizedContractId);
  }

  @Override
  public ArtifactReference schemaReference(String contractId, String version) {
    String normalizedContractId = normalizeContractId(contractId);
    String normalizedVersion = normalizeVersion(normalizedContractId, version);
    if (s3Enabled) {
      return new ArtifactReference("s3", keyStrategy.schemaKey(normalizedContractId, normalizedVersion));
    }
    return ArtifactStore.super.schemaReference(normalizedContractId, normalizedVersion);
  }

  @Override
  public HealthSnapshot healthSnapshot() {
    if (!s3Enabled) {
      return fallbackHealthSnapshot(
          "S3 is selected but no bucket is configured; filesystem fallback is active.");
    }

    try {
      listContractsFromS3();
      return HealthSnapshot.healthy(backend());
    } catch (RuntimeException ex) {
      if (!fallbackEnabled) {
        return HealthSnapshot.unavailable(backend(), s3FailureDetail(ex));
      }
      return fallbackHealthSnapshot(
          "S3 is unavailable; filesystem fallback is active (" + ex.getClass().getSimpleName() + ").");
    }
  }

  @Override
  public String backend() {
    return "s3";
  }

  private List<String> listContractsFromS3() {
    String prefix = keyStrategy.rootPrefix() + "/";
    Set<String> contractIds = new LinkedHashSet<>();
    String continuationToken = null;
    do {
      ListObjectsV2Response response = s3Client.listObjectsV2(
          ListObjectsV2Request.builder()
              .bucket(bucket)
              .prefix(prefix)
              .delimiter("/")
              .continuationToken(continuationToken)
              .build());
      for (CommonPrefix commonPrefix : response.commonPrefixes()) {
        String value = commonPrefix.prefix();
        if (value == null || value.isBlank()) {
          continue;
        }
        String withoutRoot = value.substring(prefix.length());
        if (withoutRoot.endsWith("/")) {
          withoutRoot = withoutRoot.substring(0, withoutRoot.length() - 1);
        }
        if (!withoutRoot.isBlank()) {
          contractIds.add(withoutRoot);
        }
      }
      continuationToken = response.nextContinuationToken();
    } while (continuationToken != null);
    return contractIds.stream().sorted().toList();
  }

  private HealthSnapshot fallbackHealthSnapshot(String detail) {
    HealthSnapshot fallbackStatus = fallbackStore.healthSnapshot();
    if (fallbackStatus.status() == HealthStatus.HEALTHY) {
      return HealthSnapshot.degraded(backend(), detail);
    }
    return HealthSnapshot.unavailable(
        backend(),
        "S3 is unavailable and filesystem fallback is unavailable ("
            + fallbackStatus.detail() + ").");
  }

  private List<String> listVersionsFromS3(String contractId) {
    String prefix = keyStrategy.contractPrefix(contractId) + "/versions/";
    List<String> versions = new ArrayList<>();
    String continuationToken = null;
    do {
      ListObjectsV2Response response = s3Client.listObjectsV2(
          ListObjectsV2Request.builder()
              .bucket(bucket)
              .prefix(prefix)
              .continuationToken(continuationToken)
              .build());
      for (S3Object object : response.contents()) {
        String key = object.key();
        if (key == null || !key.endsWith("/schema.json")) {
          continue;
        }
        int versionStart = key.indexOf("/versions/");
        int versionEnd = key.lastIndexOf("/schema.json");
        if (versionStart < 0 || versionEnd <= versionStart) {
          continue;
        }
        String candidate = key.substring(versionStart + "/versions/".length(), versionEnd);
        if (candidate.matches("^v[1-9][0-9]*$")) {
          versions.add(candidate);
        }
      }
      continuationToken = response.nextContinuationToken();
    } while (continuationToken != null);

    if (versions.isEmpty() && fallbackEnabled) {
      return fallbackStore.listVersions(contractId);
    }
    return versions.stream().distinct().sorted(VERSION_COMPARATOR).toList();
  }

  private void replicateContractMetadataToS3(String contractId) {
    Path metadataPath = localMetadataPath(contractId);
    putObjectFromFile(keyStrategy.metadataKey(contractId), metadataPath);
  }

  private void replicateSchemaToS3(String contractId, String version) {
    Path schemaPath = localSchemaPath(contractId, version);
    putObjectFromFile(keyStrategy.schemaKey(contractId, version), schemaPath);
    putObject(
        keyStrategy.checksumKey(contractId, version),
        RequestBody.fromBytes((sha256Hex(schemaPath) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  private void putObjectFromFile(String key, Path filePath) {
    putObject(key, RequestBody.fromFile(filePath));
  }

  private void putObject(String key, RequestBody body) {
    PutObjectRequest.Builder request = PutObjectRequest.builder()
        .bucket(bucket)
        .key(key)
        .contentType(contentTypeFor(key));
    if (!serverSideEncryption.isBlank()) {
      request.serverSideEncryption(serverSideEncryption);
    }
    if (!kmsKeyId.isBlank()) {
      request.ssekmsKeyId(kmsKeyId);
    }
    s3Client.putObject(request.build(), body);
  }

  private byte[] getObjectBytes(String key) {
    return s3Client.getObjectAsBytes(
        GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
  }

  private byte[] getSchemaChecksumBytes(String contractId, String version) {
    try {
      return getObjectBytes(keyStrategy.checksumKey(contractId, version));
    } catch (RuntimeException ex) {
      if (isNotFound(ex)) {
        throw new ExecutionException(
            "S3 schema checksum is missing for " + contractId + "/" + version + ".");
      }
      throw ex;
    }
  }

  private void verifySchemaChecksum(
      String contractId, String version, byte[] schemaPayload, byte[] checksumPayload) {
    String expectedChecksum = new String(checksumPayload, StandardCharsets.UTF_8).trim();
    if (!expectedChecksum.matches("^[0-9a-fA-F]{64}$")) {
      throw new ExecutionException(
          "S3 schema checksum is invalid for " + contractId + "/" + version + ".");
    }
    String actualChecksum = sha256Hex(schemaPayload);
    if (!actualChecksum.equalsIgnoreCase(expectedChecksum)) {
      throw new ExecutionException(
          "S3 schema checksum does not match the artifact for " + contractId + "/" + version + ".");
    }
  }

  private void deleteS3ContractArtifactsQuietly(String contractId) {
    try {
      String prefix = keyStrategy.contractPrefix(contractId) + "/";
      String continuationToken = null;
      do {
        ListObjectsV2Response response = s3Client.listObjectsV2(
            ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .continuationToken(continuationToken)
                .build());
        if (response == null) {
          return;
        }
        for (S3Object object : response.contents()) {
          if (object.key() == null || object.key().isBlank()) {
            continue;
          }
          s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(object.key()).build());
        }
        continuationToken = response.nextContinuationToken();
      } while (continuationToken != null);
    } catch (RuntimeException ex) {
      LOGGER.debug(
          "event=artifact_store_s3_cleanup_failed component=s3_artifact_store operation=delete_contract key={} error_type={} error_message={}",
          contractId,
          ex.getClass().getSimpleName(),
          ex.getMessage());
    }
  }

  private void deleteS3VersionArtifactsQuietly(String contractId, String version) {
    deleteObjectQuietly(keyStrategy.schemaKey(contractId, version));
    deleteObjectQuietly(keyStrategy.checksumKey(contractId, version));
  }

  private void deleteObjectQuietly(String key) {
    try {
      s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (RuntimeException ignored) {
      // Best effort cleanup only.
    }
  }

  private String normalizeContractId(String contractId) {
    return fallbackStore.contractDirectory(contractId).getFileName().toString();
  }

  private String normalizeOptional(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return value.trim();
  }

  private String contentTypeFor(String key) {
    if (key.endsWith(".json")) {
      return "application/json";
    }
    if (key.endsWith(".yaml") || key.endsWith(".yml")) {
      return "application/yaml";
    }
    if (key.endsWith(".sha256")) {
      return "text/plain";
    }
    return "application/octet-stream";
  }

  private String normalizeVersion(String contractId, String version) {
    Path schemaPath = fallbackStore.schemaPath(contractId, version);
    String fileName = schemaPath.getFileName().toString();
    return fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
  }

  private Path localMetadataPath(String contractId) {
    return fallbackStore.contractDirectory(contractId).resolve("metadata.yaml");
  }

  private Path localSchemaPath(String contractId, String version) {
    return fallbackStore.schemaPath(contractId, version);
  }

  private void writeLocalFile(Path path, byte[] bytes) {
    try {
      Path parent = path.toAbsolutePath().normalize().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.write(path, bytes);
    } catch (IOException ex) {
      throw new ExecutionException("Unable to write local fallback artifact path: " + path, ex);
    }
  }

  private String sha256Hex(Path path) {
    try {
      return sha256Hex(Files.readAllBytes(path));
    } catch (Exception ex) {
      throw new ExecutionException("Unable to compute schema checksum: " + path, ex);
    }
  }

  private String sha256Hex(byte[] payload) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(payload));
    } catch (Exception ex) {
      throw new ExecutionException("Unable to compute schema checksum.", ex);
    }
  }

  private boolean isNotFound(RuntimeException ex) {
    if (ex instanceof NoSuchKeyException) {
      return true;
    }
    if (ex instanceof S3Exception s3Exception) {
      return s3Exception.statusCode() == 404 && !isBucketFailure(s3Exception);
    }
    return false;
  }

  private boolean isBucketFailure(S3Exception exception) {
    return "NoSuchBucket".equalsIgnoreCase(s3ErrorCode(exception));
  }

  private <T> T fallbackOrThrow(
      String operation,
      String key,
      RuntimeException ex,
    Supplier<T> fallbackSupplier) {
    if (!fallbackEnabled) {
      throw s3OperationFailure(operation, ex);
    }
    logFallback(operation, key, ex);
    return fallbackSupplier.get();
  }

  private void logFallback(String operation, String key, RuntimeException ex) {
    LOGGER.warn(
        "event=artifact_store_s3_fallback component=s3_artifact_store operation={} bucket={} key={} error_type={} error_message={}",
        operation,
        bucket,
        key,
        ex.getClass().getSimpleName(),
        ex.getMessage());
  }

  private ExecutionException s3OperationFailure(String operation, RuntimeException ex) {
    return new ExecutionException(
        "S3 artifact operation '" + operation + "' failed: " + s3FailureDetail(ex), ex);
  }

  private String s3FailureDetail(RuntimeException ex) {
    if (ex instanceof ExecutionException) {
      return ex.getMessage();
    }
    if (ex instanceof S3Exception s3Exception) {
      String errorCode = s3ErrorCode(s3Exception);
      if ("NoSuchBucket".equalsIgnoreCase(errorCode)) {
        return "The configured S3 bucket was not found. Verify the bucket name and AWS Region.";
      }
      if ("InvalidAccessKeyId".equalsIgnoreCase(errorCode)
          || "SignatureDoesNotMatch".equalsIgnoreCase(errorCode)
          || "ExpiredToken".equalsIgnoreCase(errorCode)
          || "InvalidToken".equalsIgnoreCase(errorCode)) {
        return "AWS credentials were rejected. Refresh the workload credentials and verify the configured credential source.";
      }
      if ("AccessDenied".equalsIgnoreCase(errorCode) || s3Exception.statusCode() == 403) {
        return "Access to the configured S3 bucket was denied. Verify the workload IAM policy and bucket policy.";
      }
      if ("AuthorizationHeaderMalformed".equalsIgnoreCase(errorCode)
          || "PermanentRedirect".equalsIgnoreCase(errorCode)
          || s3Exception.statusCode() == 301) {
        return "The configured AWS Region does not match the S3 bucket. Verify contracts.artifact.s3.region.";
      }
      if (s3Exception.statusCode() == 404) {
        return "An S3 resource was not found. Verify the bucket, prefix, and artifact key.";
      }
    }
    if (ex instanceof SdkClientException) {
      return "AWS credentials or network access are unavailable. Verify the workload credential chain and S3 endpoint.";
    }
    return "The S3 request failed. Verify the bucket, Region, credentials, and network access.";
  }

  private String s3ErrorCode(S3Exception exception) {
    if (exception.awsErrorDetails() == null || exception.awsErrorDetails().errorCode() == null) {
      return "";
    }
    return exception.awsErrorDetails().errorCode().trim();
  }

  private static int versionNumber(String version) {
    return Integer.parseInt(version.substring(1));
  }
}
