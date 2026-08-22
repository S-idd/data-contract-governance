package com.ideas.contracts.service;

import java.time.Instant;

/** Immutable audit event for an evidence-retention state transition. */
public record EvidenceRetentionEvent(
    String eventId,
    String evidenceId,
    String action,
    String policyVersion,
    String archiveLocation,
    String archiveSha256,
    String actor,
    Instant occurredAt
) {}
