package com.ideas.contracts.core;

public record ContractMetadata(
    String contractId,
    String ownerTeam,
    String domain,
    CompatibilityMode compatibilityMode,
    String policyPack
) {}
