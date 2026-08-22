package com.ideas.contracts.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Evaluates retention eligibility. Mutation is deliberately unavailable until archive validation exists. */
@Service
public class EvidenceRetentionService {
  private final MetadataStore metadataStore;
  private final EvidenceRetentionProperties properties;
  private final EvidenceArchiveStore archiveStore;

  @Autowired
  public EvidenceRetentionService(
      MetadataStore metadataStore,
      EvidenceRetentionProperties properties,
      ObjectProvider<EvidenceArchiveStore> archiveStore) {
    this.metadataStore = metadataStore;
    this.properties = properties;
    this.archiveStore = archiveStore.getIfAvailable();
  }

  EvidenceRetentionService(MetadataStore metadataStore, EvidenceRetentionProperties properties) {
    this.metadataStore = metadataStore;
    this.properties = properties;
    this.archiveStore = null;
  }

  EvidenceRetentionService(
      MetadataStore metadataStore, EvidenceRetentionProperties properties, EvidenceArchiveStore archiveStore) {
    this.metadataStore = metadataStore;
    this.properties = properties;
    this.archiveStore = archiveStore;
  }

  public EvidenceRetentionReport dryRun() {
    Instant now = Instant.now();
    int limit = Math.max(1, Math.min(properties.getBatchSize(), 1000));
    List<String> longTier = metadataStore.listRetentionCandidates(
        List.of(EvidenceImportStatus.VERIFIED.name(), EvidenceImportStatus.REJECTED.name()),
        now.minus(properties.getVerifiedRejectedRetention()), limit).stream()
        .map(CheckEvidence::evidenceId).toList();
    List<String> shortTier = metadataStore.listRetentionCandidates(
        List.of(EvidenceImportStatus.UNVERIFIED.name(), EvidenceImportStatus.VERSION_SKEW.name()),
        now.minus(properties.getOperationalRetention()), limit).stream()
        .map(CheckEvidence::evidenceId).toList();
    return new EvidenceRetentionReport(properties.getPolicyVersion(), true, now, longTier, shortTier, List.of());
  }

  /**
   * Mutating retention is explicitly opt-in. It only runs with a startup-validated WORM archive;
   * otherwise the default dry-run report is returned or an error is raised before any payload is touched.
   */
  public EvidenceRetentionReport archiveAndPurge(String actor) {
    EvidenceRetentionReport report = dryRun();
    if (!properties.isEnabled() || properties.isDryRun()) {
      return report;
    }
    if (actor == null || actor.isBlank()) {
      throw new IllegalArgumentException("Retention actor must not be blank.");
    }
    if (archiveStore == null || !archiveStore.wormCapable()) {
      throw new IllegalStateException("Raw evidence purge requires a configured WORM archive; filesystem rehearsal is not allowed.");
    }
    archiveStore.verifyReadyForRetention();

    List<String> purged = new ArrayList<>();
    for (CheckEvidence evidence : eligibleEvidence()) {
      EvidenceArchiveReceipt receipt = archiveStore.archive(evidence);
      requireVerifiedArchiveReceipt(evidence, receipt);
      if (metadataStore.recordArchiveAndPurgeRawEvidence(
          evidence.evidenceId(), receipt, properties.getPolicyVersion(), actor.trim())) {
        purged.add(evidence.evidenceId());
      }
    }
    return new EvidenceRetentionReport(
        properties.getPolicyVersion(), false, report.evaluatedAt(), report.verifiedOrRejectedEvidenceIds(),
        report.operationalEvidenceIds(), List.copyOf(purged));
  }

  private List<CheckEvidence> eligibleEvidence() {
    Instant now = Instant.now();
    int limit = Math.max(1, Math.min(properties.getBatchSize(), 1000));
    List<CheckEvidence> evidence = new ArrayList<>();
    evidence.addAll(metadataStore.listRetentionCandidates(
        List.of(EvidenceImportStatus.VERIFIED.name(), EvidenceImportStatus.REJECTED.name()),
        now.minus(properties.getVerifiedRejectedRetention()), limit));
    evidence.addAll(metadataStore.listRetentionCandidates(
        List.of(EvidenceImportStatus.UNVERIFIED.name(), EvidenceImportStatus.VERSION_SKEW.name()),
        now.minus(properties.getOperationalRetention()), limit));
    return evidence;
  }

  /**
   * The S3 WORM store returns a receipt only after an exact-version readback and checksum check.
   * Validate its immutable facts again at this mutation boundary so a faulty alternate store can
   * never cause a payload delete with an unrelated or mismatched archive receipt.
   */
  private static void requireVerifiedArchiveReceipt(CheckEvidence evidence, EvidenceArchiveReceipt receipt) {
    if (receipt == null
        || !evidence.evidenceId().equals(receipt.evidenceId())
        || !evidence.payloadSha256().equals(receipt.sha256())
        || receipt.location() == null
        || receipt.location().isBlank()
        || receipt.archivedAt() == null) {
      throw new IllegalStateException(
          "Archive receipt failed evidence identity or checksum validation; raw evidence remains intact.");
    }
  }
}
