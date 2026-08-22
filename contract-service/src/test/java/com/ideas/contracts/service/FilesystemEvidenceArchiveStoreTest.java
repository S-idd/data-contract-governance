package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ideas.contracts.core.CompatibilityEngineIdentity;
import com.ideas.contracts.service.model.EvidenceImportRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemEvidenceArchiveStoreTest {
  private static final String DIGEST = "a".repeat(64);

  @TempDir
  Path tempDir;

  @Test
  void archivesWithChecksumValidationAndIsIdempotentWithoutOverwrite() throws Exception {
    FilesystemEvidenceArchiveStore archive = new FilesystemEvidenceArchiveStore(tempDir.resolve("archive"));
    CheckEvidence evidence = evidence("evidence-1", "{\"result\":\"pass\"}");

    archive.verifyReadyForRetention();
    EvidenceArchiveReceipt first = archive.archive(evidence);
    EvidenceArchiveReceipt repeated = archive.archive(evidence);

    Path archivedPath = Path.of(java.net.URI.create(first.location()));
    assertEquals(evidence.payloadSha256(), first.sha256());
    assertEquals(first.location(), repeated.location());
    assertEquals(evidence.rawEvidence(), Files.readString(archivedPath));
    assertFalse(archive.wormCapable());
    assertEquals("filesystem-rehearsal", archive.backend());
  }

  @Test
  void refusesToTreatDifferentExistingContentAsArchivedEvidence() throws Exception {
    FilesystemEvidenceArchiveStore archive = new FilesystemEvidenceArchiveStore(tempDir.resolve("archive"));
    CheckEvidence evidence = evidence("evidence-2", "{\"result\":\"pass\"}");
    Path collision = tempDir.resolve("archive/evidence/evidence-2.json");
    Files.createDirectories(collision.getParent());
    Files.writeString(collision, "{\"result\":\"tampered\"}");

    IllegalStateException error = assertThrows(IllegalStateException.class, () -> archive.archive(evidence));
    assertTrue(error.getMessage().contains("checksum"));
  }

  @Test
  void refusesEvidenceWhoseRawPayloadDoesNotMatchImportedDigest() {
    FilesystemEvidenceArchiveStore archive = new FilesystemEvidenceArchiveStore(tempDir.resolve("archive"));
    CheckEvidence evidence = new CheckEvidence(
        "evidence-3", request("mismatched"), CompatibilityEngineIdentity.sha256("original".getBytes(StandardCharsets.UTF_8)),
        "changed", provenance(), EvidenceImportStatus.VERIFIED, "test", null, Instant.now());

    assertThrows(IllegalStateException.class, () -> archive.archive(evidence));
  }

  private CheckEvidence evidence(String evidenceId, String raw) {
    return new CheckEvidence(
        evidenceId, request(evidenceId), CompatibilityEngineIdentity.sha256(raw.getBytes(StandardCharsets.UTF_8)), raw,
        provenance(), EvidenceImportStatus.VERIFIED, "test", null, Instant.now());
  }

  private EvidenceImportRequest request(String idempotencyKey) {
    return new EvidenceImportRequest(
        "1.0", idempotencyKey, "orders.created", "v1", "v2", "BACKWARD", "commit-123",
        DIGEST, DIGEST, "1.0.0", "dcg-compatibility/v1", "default", DIGEST, "PASS",
        List.of(), List.of(), Instant.now(), "claimed-ci", null);
  }

  private EvidenceProvenance provenance() {
    return new EvidenceProvenance("OIDC", "ci-workload", "https://issuer.example.test", "subject", "dcg",
        "acme/orders", "refs/heads/main");
  }
}
