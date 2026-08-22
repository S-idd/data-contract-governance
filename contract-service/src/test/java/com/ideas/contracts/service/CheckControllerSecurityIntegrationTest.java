package com.ideas.contracts.service;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
    "app.security.roles=USER,WRITER",
    "app.ui.enabled=true"
})
class CheckControllerSecurityIntegrationTest {
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
  }

  @Test
  void checksEndpointRequiresBasicAuthWhenEnabled() throws Exception {
    mockMvc.perform(get("/checks"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void checksEndpointAllowsConfiguredCredentials() throws Exception {
    mockMvc.perform(get("/checks")
            .header("Authorization", basicAuthHeader("tester", "secret")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("[")));
  }

  @Test
  void notificationDeliveryHistoryRequiresBasicAuthWhenEnabled() throws Exception {
    mockMvc.perform(get("/api/notification-deliveries"))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(get("/api/notification-deliveries")
            .header("Authorization", basicAuthHeader("tester", "secret")))
        .andExpect(status().isOk());
  }

  @Test
  void notificationDeliveryUiRequiresBasicAuthWhenEnabled() throws Exception {
    mockMvc.perform(get("/ui/notifications"))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(get("/ui/notifications")
            .header("Authorization", basicAuthHeader("tester", "secret")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Notification Deliveries")));
  }

  @Test
  void notificationDeliveryRetryRequiresBasicAuthWhenEnabled() throws Exception {
    mockMvc.perform(post("/api/notification-deliveries/missing/retry"))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(post("/api/notification-deliveries/missing/retry")
            .header("Authorization", basicAuthHeader("tester", "secret")))
        .andExpect(status().isNotFound());
  }

  @Test
  void retentionExecutionRequiresDedicatedOperatorRole() throws Exception {
    mockMvc.perform(post("/checks/evidence/retention/run")
            .header("Authorization", basicAuthHeader("tester", "secret")))
        .andExpect(status().isForbidden());
  }

  @Test
  void createCheckRunWritesAuditLogWhenAuthorized() throws Exception {
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
        .andExpect(status().isAccepted());

    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + checksDbPath);
         PreparedStatement statement = connection.prepareStatement("""
             SELECT action, status, resource_id
             FROM audit_logs
             ORDER BY created_at DESC
             LIMIT 1
             """);
         ResultSet rs = statement.executeQuery()) {
      assertTrue(rs.next());
      assertEquals("CHECK_RUN_CREATE", rs.getString("action"));
      assertEquals("SUCCESS", rs.getString("status"));
      assertTrue(rs.getString("resource_id") != null && !rs.getString("resource_id").isBlank());
    }
  }

  @Test
  void contractWritesCreateSuccessfulAuditEntriesWhenAuthorized() throws Exception {
    String contractId = "payments.audit";
    String createPayload = """
        {
          "contractId": "payments.audit",
          "ownerTeam": "payments",
          "domain": "finance",
          "compatibilityMode": "BACKWARD",
          "initialVersion": "v1",
          "schema": {
            "type": "object",
            "properties": { "paymentId": { "type": "string" } },
            "required": ["paymentId"]
          }
        }
        """;
    String versionPayload = """
        {
          "version": "v2",
          "schema": {
            "type": "object",
            "properties": {
              "paymentId": { "type": "string" },
              "currency": { "type": "string" }
            },
            "required": ["paymentId"]
          }
        }
        """;

    mockMvc.perform(post("/contracts")
            .header("Authorization", basicAuthHeader("tester", "secret"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createPayload))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/contracts/" + contractId + "/versions")
            .header("Authorization", basicAuthHeader("tester", "secret"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(versionPayload))
        .andExpect(status().isCreated());

    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + checksDbPath);
         PreparedStatement statement = connection.prepareStatement("""
             SELECT action, status
             FROM audit_logs
             WHERE resource_id = ?
             ORDER BY created_at ASC
             """)) {
      statement.setString(1, contractId);
      try (ResultSet rs = statement.executeQuery()) {
        assertTrue(rs.next());
        assertEquals("CONTRACT_CREATE", rs.getString("action"));
        assertEquals("SUCCESS", rs.getString("status"));
        assertTrue(rs.next());
        assertEquals("CONTRACT_VERSION_CREATE", rs.getString("action"));
        assertEquals("SUCCESS", rs.getString("status"));
      }
    }
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
      tempRoot = Files.createTempDirectory("security-controller-it-");
      contractsRoot = tempRoot.resolve("contracts");
      checksDbPath = tempRoot.resolve("checks-security.db");
      Files.createDirectories(contractsRoot);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create integration test paths.", e);
    }
  }
}
