package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.ideas.contracts.core.DefaultSchemaLoader;
import com.ideas.contracts.core.ExecutionException;
import com.ideas.contracts.service.model.CreateContractRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

class S3ArtifactStoreTest {
  private final ObjectMapper jsonMapper = new ObjectMapper();

  @TempDir
  Path tempDir;

  @Test
  void createContractReplicatesMetadataSchemaAndChecksumKeys() throws Exception {
    S3Client s3Client = Mockito.mock(S3Client.class);
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().eTag("ok").build());

    S3ArtifactStore store = newStore(s3Client, tempDir.resolve("contracts-fallback"), true);
    store.createContract(createRequest("orders.created", "v1"));

    Path localSchema = tempDir.resolve("contracts-fallback").resolve("orders.created").resolve("v1.json");
    assertTrue(Files.exists(localSchema));

    ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3Client, times(3)).putObject(captor.capture(), any(RequestBody.class));
    List<String> keys = captor.getAllValues().stream().map(PutObjectRequest::key).toList();
    assertTrue(keys.contains("contracts/orders.created/metadata.yaml"));
    assertTrue(keys.contains("contracts/orders.created/versions/v1/schema.json"));
    assertTrue(keys.contains("contracts/orders.created/versions/v1/schema.sha256"));
  }

  @Test
  void createContractAddsProductionS3ObjectHeaders() throws Exception {
    S3Client s3Client = Mockito.mock(S3Client.class);
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().eTag("ok").build());

    S3ArtifactStore store = new S3ArtifactStore(
        s3Client,
        "dcg-artifacts-test",
        new ArtifactKeyStrategy("contracts"),
        newFallbackStore(tempDir.resolve("contracts-cache")),
        false,
        "AES256",
        "",
        new ObjectMapper(),
        new DefaultSchemaLoader());

    store.createContract(createRequest("orders.created", "v1"));

    ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3Client, times(3)).putObject(captor.capture(), any(RequestBody.class));
    List<PutObjectRequest> requests = captor.getAllValues();
    assertTrue(requests.stream().allMatch(request -> "AES256".equals(request.serverSideEncryptionAsString())));
    assertTrue(requests.stream()
        .anyMatch(request -> request.key().endsWith("/schema.json")
            && "application/json".equals(request.contentType())));
    assertTrue(requests.stream()
        .anyMatch(request -> request.key().endsWith("/metadata.yaml")
            && "application/yaml".equals(request.contentType())));
    assertTrue(requests.stream()
        .anyMatch(request -> request.key().endsWith("/schema.sha256")
            && "text/plain".equals(request.contentType())));
  }

  @Test
  void blankBucketFailsFastWhenFallbackIsDisabled() {
    S3Client s3Client = Mockito.mock(S3Client.class);

    IllegalStateException error = assertThrows(IllegalStateException.class, () -> new S3ArtifactStore(
        s3Client,
        "",
        new ArtifactKeyStrategy("contracts"),
        newFallbackStore(tempDir.resolve("contracts-cache")),
        false,
        "AES256",
        "",
        new ObjectMapper(),
        new DefaultSchemaLoader()));

    assertTrue(error.getMessage().contains("bucket must be set"));
  }

  @Test
  void unsupportedS3EncryptionConfigurationFailsFast() {
    S3Client s3Client = Mockito.mock(S3Client.class);

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new S3ArtifactStore(
        s3Client,
        "dcg-artifacts-test",
        new ArtifactKeyStrategy("contracts"),
        newFallbackStore(tempDir.resolve("contracts-cache")),
        false,
        "AES128",
        "",
        new ObjectMapper(),
        new DefaultSchemaLoader()));

    assertTrue(error.getMessage().contains("server-side-encryption must be one of"));
  }

  @Test
  void createContractRollsBackLocalCacheWhenS3WriteFailsAndFallbackIsDisabled() {
    S3Client s3Client = Mockito.mock(S3Client.class);
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(S3Exception.builder().statusCode(503).message("S3 temporarily unavailable").build());

    Path cacheRoot = tempDir.resolve("contracts-cache");
    S3ArtifactStore store = new S3ArtifactStore(
        s3Client,
        "dcg-artifacts-test",
        new ArtifactKeyStrategy("contracts"),
        newFallbackStore(cacheRoot),
        false,
        "AES256",
        "",
        new ObjectMapper(),
        new DefaultSchemaLoader());

    ExecutionException error = assertThrows(
        ExecutionException.class,
        () -> store.createContract(createRequest("orders.created", "v1")));
    assertTrue(error.getMessage().contains("S3 request failed"));
    assertTrue(Files.notExists(cacheRoot.resolve("orders.created")));
  }

  @Test
  void readSchemaFallsBackToLocalFilesystemWhenS3IsUnavailable() throws Exception {
    S3Client s3Client = Mockito.mock(S3Client.class);
    when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(503).message("S3 temporarily unavailable").build());

    Path fallbackRoot = tempDir.resolve("contracts-fallback");
    S3ArtifactStore store = newStore(s3Client, fallbackRoot, true);
    FilesystemArtifactStore fallbackStore = new FilesystemArtifactStore(
        fallbackRoot,
        new DefaultSchemaLoader(),
        new ObjectMapper(),
        new YAMLMapper());
    fallbackStore.createContract(createRequest("orders.created", "v1"));

    Optional<JsonNode> schema = store.readSchema("orders.created", "v1");
    assertTrue(schema.isPresent());
    assertEquals("object", schema.orElseThrow().path("type").asText());
  }

  @Test
  void readSchemaDoesNotUseLocalCacheForMissingS3ObjectWhenFallbackIsDisabled() throws Exception {
    S3Client s3Client = Mockito.mock(S3Client.class);
    when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(404).message("S3 object missing").build());

    Path fallbackRoot = tempDir.resolve("contracts-fallback");
    newFallbackStore(fallbackRoot).createContract(createRequest("orders.created", "v1"));
    S3ArtifactStore store = newStore(s3Client, fallbackRoot, false);

    assertTrue(store.readSchema("orders.created", "v1").isEmpty());
  }

  @Test
  void readSchemaDoesNotTreatS3AccessDeniedAsMissingWhenFallbackIsDisabled() throws Exception {
    S3Client s3Client = Mockito.mock(S3Client.class);
    when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(403).message("S3 access denied").build());

    Path fallbackRoot = tempDir.resolve("contracts-fallback");
    newFallbackStore(fallbackRoot).createContract(createRequest("orders.created", "v1"));
    S3ArtifactStore store = newStore(s3Client, fallbackRoot, false);

    ExecutionException error = assertThrows(
        ExecutionException.class,
        () -> store.readSchema("orders.created", "v1"));
    assertTrue(error.getMessage().contains("Access to the configured S3 bucket was denied"));
  }

  @Test
  void healthSnapshotExplainsMissingBucketWrongRegionAndRejectedCredentials() {
    assertS3HealthDetail(
        s3Exception(404, "NoSuchBucket"),
        "configured S3 bucket was not found");
    assertS3HealthDetail(
        s3Exception(301, "PermanentRedirect"),
        "configured AWS Region does not match");
    assertS3HealthDetail(
        s3Exception(403, "InvalidAccessKeyId"),
        "AWS credentials were rejected");
  }

  @Test
  void listVersionsParsesS3VersionPrefixes() {
    S3Client s3Client = Mockito.mock(S3Client.class);
    ListObjectsV2Response response = ListObjectsV2Response.builder()
        .contents(List.of(
            S3Object.builder().key("contracts/orders.created/versions/v10/schema.json").build(),
            S3Object.builder().key("contracts/orders.created/versions/v2/schema.json").build(),
            S3Object.builder().key("contracts/orders.created/versions/v2/schema.sha256").build()))
        .isTruncated(false)
        .build();
    when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(response);

    S3ArtifactStore store = newStore(s3Client, tempDir.resolve("contracts-fallback"), true);
    List<String> versions = store.listVersions("orders.created");
    assertEquals(List.of("v2", "v10"), versions);
  }

  @Test
  void readSchemaUsesS3PayloadWhenAvailable() throws Exception {
    S3Client s3Client = Mockito.mock(S3Client.class);
    byte[] payload = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}"
        .getBytes(StandardCharsets.UTF_8);
    ResponseBytes<GetObjectResponse> schemaBytes = responseBytes(payload);
    ResponseBytes<GetObjectResponse> checksumBytes = responseBytes(
        (sha256Hex(payload) + "\n").getBytes(StandardCharsets.UTF_8));
    when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(schemaBytes, checksumBytes);

    Path fallbackRoot = tempDir.resolve("contracts-fallback");
    S3ArtifactStore store = newStore(s3Client, fallbackRoot, true);
    Optional<JsonNode> schema = store.readSchema("orders.created", "v3");
    assertTrue(schema.isPresent());
    assertEquals("object", schema.orElseThrow().path("type").asText());
    assertTrue(Files.exists(fallbackRoot.resolve("orders.created").resolve("v3.json")));
  }

  @Test
  void readSchemaRejectsCorruptedS3ArtifactBeforeCachingIt() {
    S3Client s3Client = Mockito.mock(S3Client.class);
    byte[] payload = "{\"type\":\"object\"}".getBytes(StandardCharsets.UTF_8);
    when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
        responseBytes(payload),
        responseBytes("0".repeat(64).getBytes(StandardCharsets.UTF_8)));

    Path fallbackRoot = tempDir.resolve("contracts-fallback");
    S3ArtifactStore store = newStore(s3Client, fallbackRoot, false);

    ExecutionException error = assertThrows(
        ExecutionException.class,
        () -> store.readSchema("orders.created", "v1"));
    assertTrue(error.getMessage().contains("schema checksum does not match"));
    assertTrue(Files.notExists(fallbackRoot.resolve("orders.created").resolve("v1.json")));
  }

  private S3ArtifactStore newStore(S3Client s3Client, Path fallbackRoot, boolean fallbackEnabled) {
    return new S3ArtifactStore(
        s3Client,
        "dcg-artifacts-test",
        new ArtifactKeyStrategy("contracts"),
        newFallbackStore(fallbackRoot),
        fallbackEnabled,
        "AES256",
        "",
        new ObjectMapper(),
        new DefaultSchemaLoader());
  }

  private void assertS3HealthDetail(S3Exception exception, String expectedDetail) {
    S3Client s3Client = Mockito.mock(S3Client.class);
    when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenThrow(exception);
    S3ArtifactStore store = newStore(s3Client, tempDir.resolve("contracts-health"), false);

    ArtifactStore.HealthSnapshot snapshot = store.healthSnapshot();
    assertEquals(ArtifactStore.HealthStatus.UNAVAILABLE, snapshot.status());
    assertTrue(snapshot.detail().contains(expectedDetail));
  }

  private S3Exception s3Exception(int statusCode, String errorCode) {
    S3Exception.Builder builder = S3Exception.builder();
    builder.statusCode(statusCode);
    builder.awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).build());
    return (S3Exception) builder.build();
  }

  private ResponseBytes<GetObjectResponse> responseBytes(byte[] payload) {
    return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), payload);
  }

  private String sha256Hex(byte[] payload) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (Exception ex) {
      throw new AssertionError("Unable to calculate test checksum", ex);
    }
  }

  private FilesystemArtifactStore newFallbackStore(Path fallbackRoot) {
    return new FilesystemArtifactStore(
        fallbackRoot,
        new DefaultSchemaLoader(),
        new ObjectMapper(),
        new YAMLMapper());
  }

  private CreateContractRequest createRequest(String contractId, String version) {
    JsonNode schema = jsonMapper.createObjectNode()
        .put("type", "object")
        .set("properties", jsonMapper.createObjectNode()
            .set("id", jsonMapper.createObjectNode().put("type", "string")));
    return new CreateContractRequest(
        contractId,
        "platform",
        "commerce",
        "BACKWARD",
        "baseline",
        version,
        schema);
  }
}
