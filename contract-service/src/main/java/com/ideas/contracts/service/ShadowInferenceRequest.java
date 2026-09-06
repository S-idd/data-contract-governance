package com.ideas.contracts.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

record ShadowInferenceRequest(
    @JsonProperty("base_schema") JsonNode baseSchema,
    @JsonProperty("candidate_schema") JsonNode candidateSchema,
    @JsonProperty("policy_pack") String policyPack) {}
