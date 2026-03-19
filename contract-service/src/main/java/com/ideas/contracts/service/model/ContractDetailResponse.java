package com.ideas.contracts.service.model;

import java.util.List;

public record ContractDetailResponse(
    String contractId,
    String ownerTeam,
    String domain,
    String compatibilityMode,
    String policyPack,
    List<String> versions,
    String status
) {}
