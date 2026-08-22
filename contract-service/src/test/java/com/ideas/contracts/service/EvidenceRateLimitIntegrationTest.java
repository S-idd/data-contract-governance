package com.ideas.contracts.service;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
    "app.security.username=rate-runner",
    "app.security.password=secret",
    "app.security.roles=WRITER",
    "app.security.evidence-auth.mode=BASIC",
    "app.security.evidence-auth.allow-basic=true",
    "checks.evidence.rate-limit.enabled=true",
    "checks.evidence.rate-limit.requests-per-window=1",
    "checks.evidence.rate-limit.window=1m",
    "checks.runner.enabled=false"
})
class EvidenceRateLimitIntegrationTest {
  private static Path root;
  private static Path contractsRoot;
  private static Path checksDbPath;

  @Autowired
  private MockMvc mockMvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    initializePaths();
    registry.add("contracts.root", () -> contractsRoot.toString());
    registry.add("checks.db.path", () -> checksDbPath.toString());
  }

  @BeforeAll
  void createContractArtifacts() throws Exception {
    Path contract = contractsRoot.resolve("orders.created");
    Files.createDirectories(contract);
    Files.writeString(contract.resolve("metadata.yaml"),
        "ownerTeam: platform\ndomain: commerce\ncompatibilityMode: BACKWARD\n");
    Files.writeString(contract.resolve("v1.json"), "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}");
    Files.writeString(contract.resolve("v2.json"), "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}");
  }

  @Test
  void returns429WithRetryAfterForSecondIdentityRepositoryImportInOneWindow() throws Exception {
    mockMvc.perform(post("/checks/evidence")
            .header("Authorization", basicAuth())
            .contentType(MediaType.APPLICATION_JSON)
            .content(evidence("rate-limit-first")))
        .andExpect(status().isAccepted());

    mockMvc.perform(post("/checks/evidence")
            .header("Authorization", basicAuth())
            .contentType(MediaType.APPLICATION_JSON)
            .content(evidence("rate-limit-second")))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")))
        .andExpect(content().string(containsString("\"code\":\"EVIDENCE_RATE_LIMITED\"")));
  }

  private String evidence(String idempotencyKey) throws Exception {
    String baseDigest = CompatibilityEngineIdentity.sha256(Files.readAllBytes(contractsRoot.resolve("orders.created/v1.json")));
    String candidateDigest = CompatibilityEngineIdentity.sha256(Files.readAllBytes(contractsRoot.resolve("orders.created/v2.json")));
    return """
        {"evidenceFormatVersion":"1.0","idempotencyKey":"%s","contractId":"orders.created",
        "baseVersion":"v1","candidateVersion":"v2","compatibilityMode":"BACKWARD","commitSha":"abc",
        "baseSchemaSha256":"%s","candidateSchemaSha256":"%s","engineVersion":"1.0.0",
        "engineCompatibilityProtocol":"%s","policyPackName":"baseline","policyPackSha256":"%s",
        "localStatus":"PASS","breakingChanges":[],"warnings":[],"executedAt":"2026-08-20T06:00:00Z",
        "ciIdentity":"claimed","buildUrl":"https://ci.example/build"}
        """.formatted(idempotencyKey, baseDigest, candidateDigest, CompatibilityEngineIdentity.COMPATIBILITY_PROTOCOL,
        CompatibilityEngineIdentity.policyPackSha256(PolicyPackDefaults.baselinePack()));
  }

  private String basicAuth() {
    return "Basic " + Base64.getEncoder().encodeToString("rate-runner:secret".getBytes(StandardCharsets.UTF_8));
  }

  private static synchronized void initializePaths() {
    if (root != null) return;
    try {
      root = Files.createTempDirectory("evidence-rate-limit-it-");
      contractsRoot = root.resolve("contracts");
      checksDbPath = root.resolve("checks.db");
      Files.createDirectories(contractsRoot);
    } catch (Exception error) {
      throw new IllegalStateException("Unable to initialize rate-limit test paths.", error);
    }
  }
}
