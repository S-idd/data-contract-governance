package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ideas.contracts.service.model.CheckRunCreateRequest;
import com.ideas.contracts.service.model.CheckRunCreateResponse;
import com.ideas.contracts.service.model.CheckRunResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "app.security.enabled=false",
    "checks.runner.enabled=true",
    "checks.runner.max-retries=0",
    "notifications.enabled=true",
    "notifications.sinks=log",
    "spring.task.scheduling.enabled=false"
})
class PolicyPackNotificationIntegrationTest {
  private static Path tempRoot;
  private static Path contractsRoot;
  private static Path checksDbPath;

  @Autowired
  private CheckRunStore checkRunStore;

  @Autowired
  private CheckRunner checkRunner;

  @Autowired
  private MockMvc mockMvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    ensurePaths();
    registry.add("contracts.root", () -> contractsRoot.toString());
    registry.add("checks.db.path", () -> checksDbPath.toString());
  }

  @BeforeAll
  void setUpContracts() throws Exception {
    ensurePaths();
    writeContract("orders.policy");
    writeContract("payments.policy");
  }

  @Test
  void queuedRunPublishesPolicyPackResolutionFailure(CapturedOutput output) {
    CheckRunCreateResponse created = checkRunStore.createQueuedRun(new CheckRunCreateRequest(
        "orders.policy",
        "v1",
        "v2",
        "BACKWARD",
        "policy-failure-test",
        "integration-test"));

    checkRunner.pollQueue();

    CheckRunResponse completed = checkRunStore.findByRunId(created.runId()).orElseThrow();
    assertEquals("FAIL", completed.status());
    assertTrue(output.toString().contains("event=notification_event"));
    assertTrue(output.toString().contains("event_type=POLICY_PACK_RESOLUTION_FAILED"));
    assertTrue(output.toString().contains("contract_id=orders.policy"));
    assertTrue(output.toString().contains("run_id=" + created.runId()));
    assertTrue(output.toString().contains("policy_pack=strict"));
  }

  @Test
  void contractVersionPostPublishesPolicyPackResolutionFailure(CapturedOutput output) throws Exception {
    String payload = """
        {
          "version": "v3",
          "schema": {
            "type": "object",
            "properties": {
              "id": { "type": "string" },
              "region": { "type": "string" }
            }
          }
        }
        """;

    mockMvc.perform(post("/contracts/payments.policy/versions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isInternalServerError());

    assertTrue(output.toString().contains("event=notification_event"));
    assertTrue(output.toString().contains("event_type=POLICY_PACK_RESOLUTION_FAILED"));
    assertTrue(output.toString().contains("contract_id=payments.policy"));
    assertTrue(output.toString().contains("policy_pack=strict"));
  }

  private void writeContract(String contractId) throws Exception {
    Path contractDir = contractsRoot.resolve(contractId);
    Files.createDirectories(contractDir);
    Files.writeString(
        contractDir.resolve("metadata.yaml"),
        "ownerTeam: platform\ndomain: governance\ncompatibilityMode: BACKWARD\npolicyPack: strict\n");
    Files.writeString(
        contractDir.resolve("v1.json"),
        "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}");
    Files.writeString(
        contractDir.resolve("v2.json"),
        "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"status\":{\"type\":\"string\"}}}");
  }

  private static synchronized void ensurePaths() {
    if (tempRoot != null) {
      return;
    }
    try {
      tempRoot = Files.createTempDirectory("policy-pack-notification-it-");
      contractsRoot = tempRoot.resolve("contracts");
      checksDbPath = tempRoot.resolve("checks-policy-pack.db");
      Files.createDirectories(contractsRoot);
      Files.writeString(
          contractsRoot.resolve("policy-packs.json"),
          """
          {
            "defaultPack": "baseline",
            "packs": {
              "strict": {
                "description": "Invalid strict pack for notification coverage",
                "rules": {
                  "UNKNOWN_POLICY_RULE": "BREAKING"
                }
              }
            }
          }
          """);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create policy-pack notification test paths.", e);
    }
  }
}
