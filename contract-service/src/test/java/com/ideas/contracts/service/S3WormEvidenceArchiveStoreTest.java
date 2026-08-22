package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ideas.contracts.core.CompatibilityEngineIdentity;
import com.ideas.contracts.service.model.EvidenceImportRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationResponse;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockConfiguration;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3WormEvidenceArchiveStoreTest {
  private static final String DIGEST = "a".repeat(64);

  @Test
  void archivesInComplianceModeThenReadsExactVersionBackForChecksumVerification() {
    S3Client client = mock(S3Client.class);
    CheckEvidence evidence = evidence("evidence-1", "{\"result\":\"pass\"}");
    when(client.headObject(any(HeadObjectRequest.class))).thenThrow(s3(404));
    when(client.putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().versionId("version-1").build());
    when(client.getObjectAsBytes(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
        .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(),
            evidence.rawEvidence().getBytes(StandardCharsets.UTF_8)));

    EvidenceArchiveReceipt receipt = new S3WormEvidenceArchiveStore(client, properties()).archive(evidence);

    ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(client).putObject(put.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
    assertEquals(ObjectLockMode.COMPLIANCE, put.getValue().objectLockMode());
    assertEquals("evidence/evidence-1.json", put.getValue().key());
    assertEquals(evidence.payloadSha256(), put.getValue().metadata().get("payload-sha256"));
    assertTrue(put.getValue().objectLockRetainUntilDate().isAfter(Instant.now().plusSeconds(60)));
    assertEquals("s3://dcg-evidence-archive/evidence/evidence-1.json?versionId=version-1", receipt.location());
    assertEquals(evidence.payloadSha256(), receipt.sha256());
  }

  @Test
  void existingVersionIsValidatedAndReturnedWithoutCreatingAnotherObject() {
    S3Client client = mock(S3Client.class);
    CheckEvidence evidence = evidence("evidence-2", "{\"result\":\"pass\"}");
    when(client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().versionId("existing-version").build());
    when(client.getObjectAsBytes(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
        .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(),
            evidence.rawEvidence().getBytes(StandardCharsets.UTF_8)));

    EvidenceArchiveReceipt receipt = new S3WormEvidenceArchiveStore(client, properties()).archive(evidence);

    verify(client, never()).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    assertTrue(receipt.location().endsWith("versionId=existing-version"));
  }

  @Test
  void checksumMismatchFailsClosed() {
    S3Client client = mock(S3Client.class);
    CheckEvidence evidence = evidence("evidence-3", "{\"result\":\"pass\"}");
    when(client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().versionId("existing-version").build());
    when(client.getObjectAsBytes(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
        .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), "tampered".getBytes(StandardCharsets.UTF_8)));

    IllegalStateException error = assertThrows(
        IllegalStateException.class, () -> new S3WormEvidenceArchiveStore(client, properties()).archive(evidence));
    assertTrue(error.getMessage().contains("raw evidence remains intact"));
  }

  @Test
  void iamDenialAndWrongRegionFailReadinessInsteadOfFallingBack() {
    for (int status : List.of(403, 301)) {
      S3Client client = mock(S3Client.class);
      when(client.getObjectLockConfiguration(any(GetObjectLockConfigurationRequest.class))).thenThrow(s3(status));

      IllegalStateException error = assertThrows(
          IllegalStateException.class, () -> new S3WormEvidenceArchiveStore(client, properties()).verifyReadyForRetention());
      assertTrue(error.getMessage().contains("Unable to validate"));
    }
  }

  @Test
  void iamDenialDuringArchiveWriteOrChecksumReadbackFailsClosed() {
    CheckEvidence evidence = evidence("evidence-iam", "{\"result\":\"pass\"}");
    S3Client writeDenied = mock(S3Client.class);
    when(writeDenied.headObject(any(HeadObjectRequest.class))).thenThrow(s3(404));
    when(writeDenied.putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
        .thenThrow(s3(403));
    IllegalStateException writeError = assertThrows(IllegalStateException.class,
        () -> new S3WormEvidenceArchiveStore(writeDenied, properties()).archive(evidence));
    assertTrue(writeError.getMessage().contains("raw evidence remains intact"));

    S3Client readDenied = mock(S3Client.class);
    when(readDenied.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().versionId("existing-version").build());
    when(readDenied.getObjectAsBytes(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
        .thenThrow(s3(403));
    IllegalStateException readError = assertThrows(IllegalStateException.class,
        () -> new S3WormEvidenceArchiveStore(readDenied, properties()).archive(evidence));
    assertTrue(readError.getMessage().contains("raw evidence remains intact"));
  }

  @Test
  void objectLockMustBeEnabledAndRetentionCannotBeShortened() {
    S3Client client = mock(S3Client.class);
    when(client.getObjectLockConfiguration(any(GetObjectLockConfigurationRequest.class))).thenReturn(
        GetObjectLockConfigurationResponse.builder().objectLockConfiguration(
            ObjectLockConfiguration.builder().build()).build());
    S3WormEvidenceArchiveStore archive = new S3WormEvidenceArchiveStore(client, properties());

    assertThrows(IllegalStateException.class, archive::verifyReadyForRetention);
    EvidenceArchiveProperties.S3 shortRetention = properties();
    shortRetention.setRetentionDays(30);
    assertThrows(IllegalArgumentException.class, () -> new S3WormEvidenceArchiveStore(client, shortRetention));
  }

  private EvidenceArchiveProperties.S3 properties() {
    EvidenceArchiveProperties.S3 properties = new EvidenceArchiveProperties.S3();
    properties.setBucket("dcg-evidence-archive");
    properties.setPrefix("evidence");
    properties.setRetentionDays(2555);
    return properties;
  }

  private CheckEvidence evidence(String evidenceId, String raw) {
    return new CheckEvidence(
        evidenceId, new EvidenceImportRequest(
            "1.0", evidenceId, "orders.created", "v1", "v2", "BACKWARD", "commit-123",
            DIGEST, DIGEST, "1.0.0", "dcg-compatibility/v1", "default", DIGEST, "PASS",
            List.of(), List.of(), Instant.now(), "claimed-ci", null),
        CompatibilityEngineIdentity.sha256(raw.getBytes(StandardCharsets.UTF_8)), raw,
        new EvidenceProvenance("OIDC", "ci-workload", "https://issuer.example.test", "subject", "dcg",
            "acme/orders", "refs/heads/main"), EvidenceImportStatus.VERIFIED, "test", null, Instant.now());
  }

  private S3Exception s3(int statusCode) {
    S3Exception.Builder builder = S3Exception.builder();
    builder.statusCode(statusCode);
    return (S3Exception) builder.build();
  }
}
