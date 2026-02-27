package com.ideas.contracts.service.model;

import com.fasterxml.jackson.databind.JsonNode;

public record ContractVersionResponse(
    String contractId,
    String version,
    JsonNode schema
) {}
