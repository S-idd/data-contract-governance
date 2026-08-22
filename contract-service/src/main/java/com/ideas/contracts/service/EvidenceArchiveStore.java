package com.ideas.contracts.service;

/**
 * Immutable evidence archive port. Retention code must only purge a raw payload after this port
 * has returned a checksum-verified receipt and that receipt has been durably recorded.
 */
public interface EvidenceArchiveStore {
  EvidenceArchiveReceipt archive(CheckEvidence evidence);

  /** Verifies that the target can be used for retention before an operator enables mutation. */
  void verifyReadyForRetention();

  /** Filesystem rehearsal storage is intentionally not WORM-capable. */
  boolean wormCapable();

  String backend();
}
