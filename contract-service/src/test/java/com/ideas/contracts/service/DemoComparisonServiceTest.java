package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideas.contracts.core.DefaultContractEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DemoComparisonServiceTest {
  private static final Path PROJECT_ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final Path KUBERNETES_FIXTURE = PROJECT_ROOT.resolve(
      "../data/external/kubernetes-openapi-v1/scored-accepted-v1/"
          + "kubernetes-accepted-external-manifest-v3.json").normalize();
  private static final Path POLICY_PACKS = PROJECT_ROOT.resolve(
      "contracts/policy-packs-v5-compositional.json");

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void returnsTheAuthoritativeAndAllThreeModelVerdictsForHttpIngressPath() throws IOException {
    JsonNode fixture = objectMapper.readTree(KUBERNETES_FIXTURE.toFile());
    final JsonNode ingressPath = findTransition(fixture,
        "kubernetes.prescreen.v1.21.0-to-v1.22.0.io.k8s.api.networking.v1.HTTPIngressPath");
    assertNotNull(ingressPath);

    ShadowInferenceGateway gateway = request -> {
      assertEquals(ingressPath.path("base_schema"), request.baseSchema());
      assertEquals(ingressPath.path("candidate_schema"), request.candidateSchema());
      assertEquals("baseline", request.policyPack());
      return breakingPredictions();
    };
    DemoComparisonService service = new DemoComparisonService(
        new DefaultContractEngine(),
        new PolicyPackRegistry(POLICY_PACKS.toString(), "unused"),
        gateway,
        objectMapper);

    DemoComparisonResponse response = service.compare(new DemoComparisonRequest(
        ingressPath.path("base_schema"),
        ingressPath.path("candidate_schema"),
        ingressPath.path("policy_pack").asText(),
        "BACKWARD"));

    assertEquals("BREAKING", response.authoritative().label());
    assertEquals("FAIL", response.authoritative().status());
    assertTrue(response.authoritative().breakingChanges().stream()
        .anyMatch(change -> change.contains("Required field added: pathType")));
    assertEquals("RAW_SEEDS_ONLY", response.model().aggregation());
    assertEquals(List.of("20260826", "20260827", "20260828"), response.model().predictions().stream()
        .map(ShadowInferenceResponse.SeedPrediction::seed).toList());
    assertTrue(response.model().predictions().stream()
        .allMatch(prediction -> "BREAKING".equals(prediction.label())));
    assertEquals("ALL_THREE", response.agreement().basis());
    assertTrue(response.agreement().agrees());
  }

  private ShadowInferenceResponse breakingPredictions() {
    return new ShadowInferenceResponse(List.of(
        prediction("20260826"), prediction("20260827"), prediction("20260828")));
  }

  private ShadowInferenceResponse.SeedPrediction prediction(String seed) {
    return new ShadowInferenceResponse.SeedPrediction(
        seed,
        "BREAKING",
        new ShadowInferenceResponse.Probabilities(0.01, 0.01, 0.98));
  }

  private JsonNode findTransition(JsonNode fixture, String recordId) {
    for (JsonNode candidate : fixture.path("transitions")) {
      if (recordId.equals(candidate.path("record_id").asText())) {
        return candidate;
      }
    }
    return null;
  }
}
