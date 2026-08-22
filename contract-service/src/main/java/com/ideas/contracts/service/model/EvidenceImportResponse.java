package com.ideas.contracts.service.model;

public record EvidenceImportResponse(
    String evidenceId,
    String importStatus,
    String verificationReason,
    boolean duplicate
) {}
