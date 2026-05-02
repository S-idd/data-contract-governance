package com.ideas.contracts.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    "app.security.username=tester",
    "app.security.password=secret",
    "app.security.roles=USER",
    "app.ui.enabled=true"
})
class CheckControllerWriteAuthorizationIntegrationTest {
  private static Path tempRoot;
  private static Path contractsRoot;
  private static Path checksDbPath;

  @Autowired
  private MockMvc mockMvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    ensureTestPaths();
    registry.add("contracts.root", () -> contractsRoot.toString());
    registry.add("checks.db.path", () -> checksDbPath.toString());
  }

  @BeforeAll
  void setUpData() throws Exception {
    Path contractDir = contractsRoot.resolve("orders.created");
    Files.createDirectories(contractDir);
    Files.writeString(
        contractDir.resolve("metadata.yaml"),
        "ownerTeam: platform\ndomain: commerce\ncompatibilityMode: BACKWARD\n");
    Files.writeString(
        contractDir.resolve("v1.json"),
        "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}");
    Files.writeString(
        contractDir.resolve("v2.json"),
        "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}");
  }

  @Test
  void createCheckRunIsForbiddenWithoutWriterRole() throws Exception {
    String payload = """
        {
          "contractId": "orders.created",
          "baseVersion": "v1",
          "candidateVersion": "v2",
          "mode": "BACKWARD",
          "commitSha": "security-it",
          "triggeredBy": "api"
        }
        """;

    mockMvc.perform(post("/checks")
            .header("Authorization", basicAuthHeader("tester", "secret"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isForbidden());
  }

  @Test
  void createContractIsForbiddenWithoutWriterRole() throws Exception {
    String payload = """
        {
          "contractId": "payments.forbidden",
          "ownerTeam": "payments",
          "domain": "finance",
          "compatibilityMode": "BACKWARD",
          "schema": {
            "type": "object",
            "properties": {
              "paymentId": { "type": "string" }
            }
          }
        }
        """;

    mockMvc.perform(post("/contracts")
            .header("Authorization", basicAuthHeader("tester", "secret"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isForbidden());
  }

  @Test
  void createContractVersionIsForbiddenWithoutWriterRole() throws Exception {
    String payload = """
        {
          "version": "v3",
          "schema": {
            "type": "object",
            "properties": {
              "orderId": { "type": "string" },
              "status": { "type": "string" }
            }
          }
        }
        """;

    mockMvc.perform(post("/contracts/orders.created/versions")
            .header("Authorization", basicAuthHeader("tester", "secret"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isForbidden());
  }

  @Test
  void uiRunCheckIsForbiddenWithoutWriterRole() throws Exception {
    mockMvc.perform(post("/ui/contracts/orders.created/checks")
            .header("Authorization", basicAuthHeader("tester", "secret"))
            .param("baseVersion", "v1")
            .param("candidateVersion", "v2"))
        .andExpect(status().isForbidden());
  }

  private String basicAuthHeader(String username, String password) {
    String raw = username + ":" + password;
    return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private static synchronized void ensureTestPaths() {
    if (tempRoot != null) {
      return;
    }
    try {
      tempRoot = Files.createTempDirectory("security-write-it-");
      contractsRoot = tempRoot.resolve("contracts");
      checksDbPath = tempRoot.resolve("checks-security.db");
      Files.createDirectories(contractsRoot);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create integration test paths.", e);
    }
  }
}
