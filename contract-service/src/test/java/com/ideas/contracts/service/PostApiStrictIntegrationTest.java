package com.ideas.contracts.service;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "app.security.enabled=false",
    "app.ui.enabled=true",
    "contracts.validation.strict-mode=true"
})
class PostApiStrictIntegrationTest {
  private static Path tempRoot;
  private static Path contractsRoot;
  private static Path checksDbPath;
  private static final AtomicInteger CONTRACT_SEQUENCE = new AtomicInteger(0);

  @Autowired
  private MockMvc mockMvc;

  private final ObjectMapper objectMapper = new ObjectMapper();

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
        "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"},\"status\":{\"type\":\"string\"}}}");
  }

  @Test
  void checksCreateRejectsSameBaseAndCandidateVersion() throws Exception {
    String payload = """
        {
          "contractId": "orders.created",
          "baseVersion": "v2",
          "candidateVersion": "v2",
          "mode": "BACKWARD",
          "triggeredBy": "strict-suite"
        }
        """;

    MvcResult response = mockMvc.perform(post("/checks")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andReturn();

    JsonNode body = readJson(response);
    assertEquals("INVALID_REQUEST", body.get("code").asText());
    assertEquals("baseVersion must differ from candidateVersion.", body.get("message").asText());
    assertEquals("/checks", body.get("path").asText());
  }

  @Test
  void checksCreateRejectsMalformedJsonPayload() throws Exception {
    String payload = """
        {
          "contractId": "orders.created",
          "baseVersion": "v1"
        """;

    MvcResult response = mockMvc.perform(post("/checks")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andReturn();

    JsonNode body = readJson(response);
    assertEquals("INVALID_REQUEST", body.get("code").asText());
    assertEquals("Malformed JSON request body.", body.get("message").asText());
  }

  @Test
  void checksCreateNormalizesWhitespaceAndModeBeforePersisting() throws Exception {
    String payload = """
        {
          "contractId": "  orders.created  ",
          "baseVersion": "  v1  ",
          "candidateVersion": "  v2 ",
          "mode": " backward ",
          "commitSha": "  strict-post-sha  ",
          "triggeredBy": "  strict-suite  "
        }
        """;

    MvcResult createResponse = mockMvc.perform(post("/checks")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isAccepted())
        .andReturn();

    JsonNode createBody = readJson(createResponse);
    String runId = createBody.get("runId").asText();
    assertNotNull(runId);
    assertEquals("QUEUED", createBody.get("status").asText());

    MvcResult fetchResponse = mockMvc.perform(get("/checks/" + runId))
        .andExpect(status().isOk())
        .andReturn();

    JsonNode fetched = readJson(fetchResponse);
    assertEquals("orders.created", fetched.get("contractId").asText());
    assertEquals("v1", fetched.get("baseVersion").asText());
    assertEquals("v2", fetched.get("candidateVersion").asText());
    assertEquals("strict-post-sha", fetched.get("commitSha").asText());
  }

  @Test
  void contractsCreateDefaultsInitialVersionAndWritesAuditLog() throws Exception {
    String contractId = uniqueContractId("payments.strict");
    String payload = """
        {
          "contractId": "%s",
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
        """.formatted(contractId);

    MvcResult response = mockMvc.perform(post("/contracts")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isCreated())
        .andReturn();

    JsonNode body = readJson(response);
    assertEquals(contractId, body.get("contractId").asText());
    assertEquals("v1", body.get("versions").get(0).asText());

    mockMvc.perform(get("/contracts/" + contractId + "/versions/v1"))
        .andExpect(status().isOk());

    assertAuditLog("CONTRACT_CREATE", "SUCCESS", "/contracts", contractId, "\"initialVersion\":\"v1\"");
  }

  @Test
  void contractsCreateRejectsDuplicateContractWithConflict() throws Exception {
    String contractId = uniqueContractId("ledger.entry");
    String payload = """
        {
          "contractId": "%s",
          "ownerTeam": "ledger",
          "domain": "finance",
          "compatibilityMode": "BACKWARD",
          "schema": {
            "type": "object",
            "properties": {
              "entryId": { "type": "string" }
            }
          }
        }
        """.formatted(contractId);

    mockMvc.perform(post("/contracts")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isCreated());

    MvcResult duplicateResponse = mockMvc.perform(post("/contracts")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isConflict())
        .andReturn();

    JsonNode body = readJson(duplicateResponse);
    assertEquals("RESOURCE_CONFLICT", body.get("code").asText());
    assertTrue(body.get("message").asText().contains("already exists"));
  }

  @Test
  void contractsCreateRejectsInvalidContractId() throws Exception {
    String payload = """
        {
          "contractId": "Payments.Completed",
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

    MvcResult response = mockMvc.perform(post("/contracts")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andReturn();

    JsonNode body = readJson(response);
    assertEquals("INVALID_REQUEST", body.get("code").asText());
    assertEquals("contractId must use lowercase dot-separated format.", body.get("message").asText());
  }

  @Test
  void contractVersionCreateRejectsBreakingChangesInStrictMode() throws Exception {
    String payload = """
        {
          "version": "v3",
          "schema": {
            "type": "object",
            "properties": {
              "orderId": { "type": "integer" },
              "status": { "type": "string" }
            }
          }
        }
        """;

    MvcResult response = mockMvc.perform(post("/contracts/orders.created/versions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isUnprocessableEntity())
        .andReturn();

    JsonNode body = readJson(response);
    assertEquals("COMPATIBILITY_FAILED", body.get("code").asText());
    assertTrue(body.get("message").asText().contains("Strict mode rejected version v3."));
    assertAuditLog(
        "CONTRACT_VERSION_CREATE",
        "FAILURE",
        "/contracts/orders.created/versions",
        "orders.created",
        "\"version\":\"v3\"");
  }

  @Test
  void contractVersionCreateRejectsNonIncrementalVersion() throws Exception {
    String payload = """
        {
          "version": "v4",
          "schema": {
            "type": "object",
            "properties": {
              "orderId": { "type": "string" },
              "status": { "type": "string" },
              "region": { "type": "string" }
            }
          }
        }
        """;

    MvcResult response = mockMvc.perform(post("/contracts/orders.created/versions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andReturn();

    JsonNode body = readJson(response);
    assertEquals("SCHEMA_VALIDATION_FAILED", body.get("code").asText());
    assertTrue(body.get("message").asText().contains("Version sequence must be incremental."));
  }

  @Test
  void contractVersionCreateRejectsMissingContract() throws Exception {
    String payload = """
        {
          "version": "v1",
          "schema": {
            "type": "object",
            "properties": {
              "value": { "type": "string" }
            }
          }
        }
        """;

    MvcResult response = mockMvc.perform(post("/contracts/unknown.contract/versions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isNotFound())
        .andReturn();

    JsonNode body = readJson(response);
    assertEquals("RESOURCE_NOT_FOUND", body.get("code").asText());
    assertEquals("Contract not found: unknown.contract", body.get("message").asText());
  }

  @Test
  void contractVersionCreateRejectsMalformedJsonPayload() throws Exception {
    String payload = """
        {
          "version": "v3",
          "schema": {
            "type": "object"
          }
        """;

    MvcResult response = mockMvc.perform(post("/contracts/orders.created/versions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andReturn();

    JsonNode body = readJson(response);
    assertEquals("INVALID_REQUEST", body.get("code").asText());
    assertEquals("Malformed JSON request body.", body.get("message").asText());
  }

  @Test
  void uiContractCheckPostRedirectsAndStoresUiTriggeredRun() throws Exception {
    MvcResult postResponse = mockMvc.perform(post("/ui/contracts/orders.created/checks")
            .param("baseVersion", "v1")
            .param("candidateVersion", "v2")
            .param("commitSha", "  ui-strict-sha  "))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrlPattern("/ui/checks/*"))
        .andReturn();

    String redirectPath = postResponse.getResponse().getRedirectedUrl();
    assertNotNull(redirectPath);
    String runId = redirectPath.substring("/ui/checks/".length());

    MvcResult fetchResponse = mockMvc.perform(get("/checks/" + runId))
        .andExpect(status().isOk())
        .andReturn();

    JsonNode body = readJson(fetchResponse);
    assertEquals("orders.created", body.get("contractId").asText());
    assertEquals("ui-strict-sha", body.get("commitSha").asText());

    assertQueuedRunMeta(runId, "ui", "BACKWARD");
    assertAuditLog(
        "CHECK_RUN_CREATE",
        "SUCCESS",
        "/ui/contracts/orders.created/checks",
        runId,
        "\"triggeredBy\":\"ui\"");
  }

  @Test
  void uiContractCheckPostShowsValidationErrorOnInvalidVersionPair() throws Exception {
    mockMvc.perform(post("/ui/contracts/orders.created/checks")
            .param("baseVersion", "v2")
            .param("candidateVersion", "v2"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("baseVersion must differ from candidateVersion.")));

    assertAuditLog(
        "CHECK_RUN_CREATE",
        "FAILURE",
        "/ui/contracts/orders.created/checks",
        null,
        "\"error\":\"baseVersion must differ from candidateVersion.\"");
  }

  @Test
  void uiContractCheckPostReturnsNotFoundForUnknownContract() throws Exception {
    mockMvc.perform(post("/ui/contracts/unknown.contract/checks")
            .param("baseVersion", "v1")
            .param("candidateVersion", "v2"))
        .andExpect(status().isNotFound());
  }

  private void assertQueuedRunMeta(String runId, String expectedTriggeredBy, String expectedMode)
      throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + checksDbPath);
         PreparedStatement statement = connection.prepareStatement("""
             SELECT triggered_by, compatibility_mode
             FROM check_runs
             WHERE run_id = ?
             """)) {
      statement.setString(1, runId);
      try (ResultSet rs = statement.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(expectedTriggeredBy, rs.getString("triggered_by"));
        assertEquals(expectedMode, rs.getString("compatibility_mode"));
      }
    }
  }

  private void assertAuditLog(
      String action,
      String status,
      String path,
      String resourceId,
      String detailContains) throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + checksDbPath);
         PreparedStatement statement = connection.prepareStatement("""
             SELECT action, status, path, resource_id, detail
             FROM audit_logs
             WHERE action = ?
               AND status = ?
               AND path = ?
             ORDER BY created_at DESC
             LIMIT 1
             """)) {
      statement.setString(1, action);
      statement.setString(2, status);
      statement.setString(3, path);

      try (ResultSet rs = statement.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(action, rs.getString("action"));
        assertEquals(status, rs.getString("status"));
        assertEquals(path, rs.getString("path"));
        if (resourceId != null) {
          assertEquals(resourceId, rs.getString("resource_id"));
        }
        String detail = rs.getString("detail");
        if (detailContains != null) {
          assertTrue(detail != null && detail.contains(detailContains));
        }
      }
    }
  }

  private JsonNode readJson(MvcResult response) throws Exception {
    return objectMapper.readTree(response.getResponse().getContentAsString());
  }

  private String uniqueContractId(String prefix) {
    return prefix + CONTRACT_SEQUENCE.incrementAndGet();
  }

  private static synchronized void ensureTestPaths() {
    if (tempRoot != null) {
      return;
    }
    try {
      tempRoot = Files.createTempDirectory("post-api-strict-it-");
      contractsRoot = tempRoot.resolve("contracts");
      checksDbPath = tempRoot.resolve("checks.db");
      Files.createDirectories(contractsRoot);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create integration test paths.", e);
    }
  }
}
