package com.ideas.contracts.service;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ideas.contracts.core.CompatibilityEngineIdentity;
import com.ideas.contracts.core.PolicyPackDefaults;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "app.security.enabled=true",
    "app.security.username=ci-runner",
    "app.security.password=secret",
    "app.security.roles=WRITER",
    "app.security.evidence-auth.mode=BASIC",
    "app.security.evidence-auth.allow-basic=true",
    "checks.evidence.rate-limit.enabled=false",
    "checks.runner.enabled=false"
})
class EvidenceImportIntegrationTest {
  private static Path tempRoot;
  private static Path contractsRoot;
  private static Path checksDbPath;

  @Autowired
  private MockMvc mockMvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    ensurePaths();
    registry.add("contracts.root", () -> contractsRoot.toString());
    registry.add("checks.db.path", () -> checksDbPath.toString());
  }

  @BeforeAll
  void prepareRegisteredSchemas() throws Exception {
    Path contract = contractsRoot.resolve("orders.created");
    Files.createDirectories(contract);
    Files.writeString(contract.resolve("metadata.yaml"),
        "ownerTeam: platform\ndomain: commerce\ncompatibilityMode: BACKWARD\n");
    Files.writeString(contract.resolve("v1.json"),
        "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}");
    Files.writeString(contract.resolve("v2.json"),
        "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"},\"status\":{\"type\":\"string\"}}}");
  }

  @Test
  void importsVerifiesAndReplaysExactEvidenceIdempotently() throws Exception {
    String evidence = evidence("evidence-verified", CompatibilityEngineIdentity.COMPATIBILITY_PROTOCOL, "[]");

    mockMvc.perform(post("/checks/evidence")
            .header("Authorization", basicAuth())
            .contentType(MediaType.APPLICATION_JSON)
            .content(evidence))
        .andExpect(status().isAccepted())
        .andExpect(content().string(containsString("\"importStatus\":\"VERIFIED\"")));

    mockMvc.perform(post("/checks/evidence")
            .header("Authorization", basicAuth())
            .contentType(MediaType.APPLICATION_JSON)
            .content(evidence))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"duplicate\":true")));

    mockMvc.perform(get("/checks/evidence")
            .header("Authorization", basicAuth())
            .param("contractId", "orders.created")
            .param("importStatus", "VERIFIED"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"authenticatedIdentity\":\"ci-runner\"")))
        .andExpect(content().string(containsString("\"authenticationScheme\":\"BASIC\"")))
        .andExpect(content().string(containsString("\"importStatus\":\"VERIFIED\"")));

    String conflicting = evidence("evidence-verified", CompatibilityEngineIdentity.COMPATIBILITY_PROTOCOL,
        "[\"made-up warning\"]");
    mockMvc.perform(post("/checks/evidence")
            .header("Authorization", basicAuth())
            .contentType(MediaType.APPLICATION_JSON)
            .content(conflicting))
        .andExpect(status().isConflict());
  }

  @Test
  void makesVersionSkewVisibleAndRejectsUnauthenticatedImports() throws Exception {
    mockMvc.perform(post("/checks/evidence")
            .contentType(MediaType.APPLICATION_JSON)
            .content(evidence("evidence-no-auth", "999", "[]")))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(post("/checks/evidence")
            .header("Authorization", basicAuth())
            .contentType(MediaType.APPLICATION_JSON)
            .content(evidence("evidence-skew", "999", "[]")))
        .andExpect(status().isAccepted())
        .andExpect(content().string(containsString("\"importStatus\":\"VERSION_SKEW\"")))
        .andExpect(content().string(containsString("ENGINE_PROTOCOL_MISMATCH")));
  }

  @Test
  void returnsStableEvidenceFailureCodesForMalformedPayloadAndIdempotencyConflict() throws Exception {
    mockMvc.perform(post("/checks/evidence")
            .header("Authorization", basicAuth())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("\"code\":\"MALFORMED_DOCUMENT\"")));

    String original = evidence("evidence-taxonomy", CompatibilityEngineIdentity.COMPATIBILITY_PROTOCOL, "[]");
    mockMvc.perform(post("/checks/evidence")
            .header("Authorization", basicAuth())
            .contentType(MediaType.APPLICATION_JSON)
            .content(original))
        .andExpect(status().isAccepted());

    mockMvc.perform(post("/checks/evidence")
            .header("Authorization", basicAuth())
            .contentType(MediaType.APPLICATION_JSON)
            .content(evidence("evidence-taxonomy", CompatibilityEngineIdentity.COMPATIBILITY_PROTOCOL,
                "[\"changed\"]")))
        .andExpect(status().isConflict())
        .andExpect(content().string(containsString("\"code\":\"EVIDENCE_IDEMPOTENCY_CONFLICT\"")));
  }

  private String evidence(String idempotencyKey, String protocol, String warnings) throws Exception {
    String baseDigest = CompatibilityEngineIdentity.sha256(
        Files.readAllBytes(contractsRoot.resolve("orders.created/v1.json")));
    String candidateDigest = CompatibilityEngineIdentity.sha256(
        Files.readAllBytes(contractsRoot.resolve("orders.created/v2.json")));
    return """
        {
          "evidenceFormatVersion": "1.0",
          "idempotencyKey": "%s",
          "contractId": "orders.created",
          "baseVersion": "v1",
          "candidateVersion": "v2",
          "compatibilityMode": "BACKWARD",
          "commitSha": "abc123",
          "baseSchemaSha256": "%s",
          "candidateSchemaSha256": "%s",
          "engineVersion": "0.1.0-SNAPSHOT",
          "engineCompatibilityProtocol": "%s",
          "policyPackName": "baseline",
          "policyPackSha256": "%s",
          "localStatus": "PASS",
          "breakingChanges": [],
          "warnings": %s,
          "executedAt": "2026-08-20T06:00:00Z",
          "ciIdentity": "github-actions",
          "buildUrl": "https://ci.example/build/1"
        }
        """.formatted(idempotencyKey, baseDigest, candidateDigest, protocol,
        CompatibilityEngineIdentity.policyPackSha256(PolicyPackDefaults.baselinePack()), warnings);
  }

  private String basicAuth() {
    return "Basic " + Base64.getEncoder().encodeToString("ci-runner:secret".getBytes(StandardCharsets.UTF_8));
  }

  private static synchronized void ensurePaths() {
    if (tempRoot != null) {
      return;
    }
    try {
      tempRoot = Files.createTempDirectory("evidence-import-it-");
      contractsRoot = tempRoot.resolve("contracts");
      checksDbPath = tempRoot.resolve("checks.db");
      Files.createDirectories(contractsRoot);
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to initialize evidence integration test paths.", exception);
    }
  }
}
