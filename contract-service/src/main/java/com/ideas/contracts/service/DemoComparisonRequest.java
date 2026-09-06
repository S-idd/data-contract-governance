package com.ideas.contracts.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Input accepted by the synchronous, presentation-only comparison endpoint. */
public record DemoComparisonRequest(
    @JsonProperty("base_schema") JsonNode baseSchema,
    @JsonProperty("candidate_schema") JsonNode candidateSchema,
    @JsonProperty("policy_pack") String policyPack,
    String mode) {
}
