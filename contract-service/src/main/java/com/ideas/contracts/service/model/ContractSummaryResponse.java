package com.ideas.contracts.service.model;

public record ContractSummaryResponse(
    String contractId,
    String ownerTeam,
    String domain,
    String compatibilityMode,
    String latestVersion,
    String status
) {}
