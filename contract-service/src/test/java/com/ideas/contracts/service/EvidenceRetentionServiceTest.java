package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ideas.contracts.service.model.EvidenceImportRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class EvidenceRetentionServiceTest {
  private static final String DIGEST = "a".repeat(64);

  @TempDir
  Path tempDir;

  @Test
  void activeEvidenceHoldExcludesCandidateUntilItIsExplicitlyReleased() {
    CheckRunStore store = newStore();
    try {
      CheckEvidence evidence = importEvidence(store, "held-evidence", EvidenceImportStatus.VERIFIED, "acme/orders");
      EvidenceRetentionService retention = new EvidenceRetentionService(store, expiredPolicy());

      assertTrue(retention.dryRun().verifiedOrRejectedEvidenceIds().contains(evidence.evidenceId()));

      EvidenceLegalHold hold = store.placeEvidenceLegalHold(EvidenceLegalHold.active(
          evidence.evidenceId(), null, null, "approval investigation", "compliance@example.test"));
      assertFalse(retention.dryRun().verifiedOrRejectedEvidenceIds().contains(evidence.evidenceId()));

      assertTrue(store.releaseEvidenceLegalHold(
          hold.holdId(), "compliance@example.test", "investigation completed"));
      assertFalse(store.releaseEvidenceLegalHold(
          hold.holdId(), "compliance@example.test", "duplicate release"));
      assertTrue(retention.dryRun().verifiedOrRejectedEvidenceIds().contains(evidence.evidenceId()));
    } finally {
      store.shutdown();
    }
  }

  @Test
  void contractRepositoryHoldDoesNotOverBlockAnotherRepository() {
    CheckRunStore store = newStore();
    try {
      CheckEvidence protectedEvidence = importEvidence(
          store, "protected", EvidenceImportStatus.REJECTED, "acme/orders");
      CheckEvidence otherRepository = importEvidence(
          store, "other", EvidenceImportStatus.REJECTED, "acme/catalog");
      store.placeEvidenceLegalHold(EvidenceLegalHold.active(
          null, "orders.created", "acme/orders", "regulatory review", "compliance@example.test"));

      List<String> candidates = new EvidenceRetentionService(store, expiredPolicy())
          .dryRun().verifiedOrRejectedEvidenceIds();
      assertFalse(candidates.contains(protectedEvidence.evidenceId()));
      assertTrue(candidates.contains(otherRepository.evidenceId()));
    } finally {
      store.shutdown();
    }
  }

  @Test
  void wormArchiveReceiptIsRecordedBeforeRawPayloadIsPurgedAndTheRunIsIdempotent() {
    CheckRunStore store = newStore();
    try {
      CheckEvidence evidence = importEvidence(store, "purgeable", EvidenceImportStatus.VERIFIED, "acme/orders");
      EvidenceRetentionProperties properties = expiredPolicy();
      properties.setEnabled(true);
      properties.setDryRun(false);
      RecordingWormArchive archive = new RecordingWormArchive();
      EvidenceRetentionService retention = new EvidenceRetentionService(store, properties, archive);

      EvidenceRetentionReport first = retention.archiveAndPurge("retention-job");
      CheckEvidence purged = store.findEvidenceByIdempotencyKey("purgeable").orElseThrow();
      List<EvidenceRetentionEvent> events = store.listEvidenceRetentionEvents(evidence.evidenceId(), 10);

      assertEquals(List.of(evidence.evidenceId()), first.purgedEvidenceIds());
      assertFalse(purged.hasRawEvidence());
      assertEquals(1, events.size());
      assertEquals("RAW_PAYLOAD_PURGED", events.get(0).action());
      assertEquals(evidence.payloadSha256(), events.get(0).archiveSha256());
      assertEquals("retention-job", events.get(0).actor());
      assertTrue(retention.archiveAndPurge("retention-job").purgedEvidenceIds().isEmpty());
      assertEquals(1, archive.archivedIds.size());
    } finally {
      store.shutdown();
    }
  }

  @Test
  void filesystemArchiveCanNeverEnableRawPayloadPurge() {
    CheckRunStore store = newStore();
    try {
      importEvidence(store, "filesystem-not-allowed", EvidenceImportStatus.VERIFIED, "acme/orders");
      EvidenceRetentionProperties properties = expiredPolicy();
      properties.setEnabled(true);
      properties.setDryRun(false);
      EvidenceRetentionService retention = new EvidenceRetentionService(
          store, properties, new FilesystemEvidenceArchiveStore(tempDir.resolve("rehearsal")));

      assertThrows(IllegalStateException.class, () -> retention.archiveAndPurge("retention-job"));
      assertTrue(store.findEvidenceByIdempotencyKey("filesystem-not-allowed").orElseThrow().hasRawEvidence());
    } finally {
      store.shutdown();
    }
  }

  @Test
  void archiveReadbackFailureLeavesRawPayloadAndNoPurgeEvent() {
    CheckRunStore store = newStore();
    try {
      CheckEvidence evidence = importEvidence(store, "readback-failed", EvidenceImportStatus.VERIFIED, "acme/orders");
      EvidenceRetentionProperties properties = expiredPolicy();
      properties.setEnabled(true);
      properties.setDryRun(false);
      EvidenceRetentionService retention = new EvidenceRetentionService(
          store, properties, new FailingWormArchive());

      assertThrows(IllegalStateException.class, () -> retention.archiveAndPurge("retention-job"));
      assertTrue(store.findEvidenceByIdempotencyKey("readback-failed").orElseThrow().hasRawEvidence());
      assertTrue(store.listEvidenceRetentionEvents(evidence.evidenceId(), 10).isEmpty());
    } finally {
      store.shutdown();
    }
  }

  @Test
  void badArchiveReceiptCannotCauseRawPayloadPurge() {
    CheckRunStore store = newStore();
    try {
      CheckEvidence evidence = importEvidence(store, "bad-receipt", EvidenceImportStatus.VERIFIED, "acme/orders");
      EvidenceRetentionProperties properties = expiredPolicy();
      properties.setEnabled(true);
      properties.setDryRun(false);
      EvidenceRetentionService retention = new EvidenceRetentionService(
          store, properties, new MismatchedReceiptWormArchive());

      assertThrows(IllegalStateException.class, () -> retention.archiveAndPurge("retention-job"));
      assertTrue(store.findEvidenceByIdempotencyKey("bad-receipt").orElseThrow().hasRawEvidence());
      assertTrue(store.listEvidenceRetentionEvents(evidence.evidenceId(), 10).isEmpty());
    } finally {
      store.shutdown();
    }
  }

  @Test
  void transactionRechecksLegalHoldAndDoesNotWriteADeletionEvent() {
    CheckRunStore store = newStore();
    try {
      CheckEvidence evidence = importEvidence(store, "held-at-purge", EvidenceImportStatus.VERIFIED, "acme/orders");
      store.placeEvidenceLegalHold(EvidenceLegalHold.active(
          evidence.evidenceId(), null, null, "late legal hold", "compliance@example.test"));
      EvidenceArchiveReceipt receipt = new EvidenceArchiveReceipt(
          evidence.evidenceId(), "s3://dedicated-evidence-bucket/held-at-purge", evidence.payloadSha256(), Instant.now());

      assertFalse(store.recordArchiveAndPurgeRawEvidence(
          evidence.evidenceId(), receipt, "evidence-retention-v1", "retention-job"));
      assertTrue(store.findEvidenceByIdempotencyKey("held-at-purge").orElseThrow().hasRawEvidence());
      assertTrue(store.listEvidenceRetentionEvents(evidence.evidenceId(), 10).isEmpty());
    } finally {
      store.shutdown();
    }
  }

  private CheckRunStore newStore() {
    CheckStoreProperties properties = new CheckStoreProperties();
    properties.setPath(tempDir.resolve("retention.db").toString());
    properties.getPool().setConnectionTimeout(Duration.ofMillis(250));
    CheckRunStore store = new CheckRunStore(properties);
    store.initialize();
    return store;
  }

  private EvidenceRetentionProperties expiredPolicy() {
    EvidenceRetentionProperties properties = new EvidenceRetentionProperties();
    properties.setBatchSize(100);
    properties.setVerifiedRejectedRetention(Duration.ofDays(1));
    properties.setOperationalRetention(Duration.ofDays(1));
    return properties;
  }

  private CheckEvidence importEvidence(
      CheckRunStore store, String idempotencyKey, EvidenceImportStatus status, String repository) {
    EvidenceImportRequest request = new EvidenceImportRequest(
        "1.0", idempotencyKey, "orders.created", "v1", "v2", "BACKWARD", "commit-123",
        DIGEST, DIGEST, "1.0.0", "dcg-compatibility/v1", "default", DIGEST, "PASS",
        List.of(), List.of(), Instant.now().minus(Duration.ofDays(2)), "claimed-ci", null);
    CheckEvidence evidence = new CheckEvidence(
        java.util.UUID.randomUUID().toString(), request, DIGEST, "{\"id\":\"" + idempotencyKey + "\"}",
        new EvidenceProvenance("OIDC", "ci-workload", "https://issuer.example.test", "subject", "dcg",
            repository, "refs/heads/main"),
        status, "test", null, Instant.now().minus(Duration.ofDays(2)));
    return store.importEvidence(evidence).evidence();
  }

  private static final class RecordingWormArchive implements EvidenceArchiveStore {
    private final List<String> archivedIds = new java.util.ArrayList<>();

    @Override
    public EvidenceArchiveReceipt archive(CheckEvidence evidence) {
      archivedIds.add(evidence.evidenceId());
      return new EvidenceArchiveReceipt(
          evidence.evidenceId(), "s3://dedicated-evidence-bucket/" + evidence.evidenceId(),
          evidence.payloadSha256(), Instant.now());
    }

    @Override
    public void verifyReadyForRetention() {}

    @Override
    public boolean wormCapable() { return true; }

    @Override
    public String backend() { return "test-worm"; }
  }

  private static final class FailingWormArchive implements EvidenceArchiveStore {
    @Override
    public EvidenceArchiveReceipt archive(CheckEvidence evidence) {
      throw new IllegalStateException("Exact-version S3 readback failed");
    }

    @Override
    public void verifyReadyForRetention() {}

    @Override
    public boolean wormCapable() { return true; }

    @Override
    public String backend() { return "failing-test-worm"; }
  }

  private static final class MismatchedReceiptWormArchive implements EvidenceArchiveStore {
    @Override
    public EvidenceArchiveReceipt archive(CheckEvidence evidence) {
      return new EvidenceArchiveReceipt(
          evidence.evidenceId(), "s3://dedicated-evidence-bucket/wrong", "0".repeat(64), Instant.now());
    }

    @Override
    public void verifyReadyForRetention() {}

    @Override
    public boolean wormCapable() { return true; }

    @Override
    public String backend() { return "bad-receipt-test-worm"; }
  }
}
