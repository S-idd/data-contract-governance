package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContractServiceApiIntegrationTest {
  private static Path tempRoot;
  private static Path contractsRoot;
  private static Path checksDbPath;

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
    ensureTestPaths();

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

    String jdbcUrl = "jdbc:sqlite:" + checksDbPath;
    Flyway.configure()
        .dataSource(jdbcUrl, null, null)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion(MigrationVersion.fromVersion("0"))
        .load()
        .migrate();

    try (Connection connection = DriverManager.getConnection(jdbcUrl);
         PreparedStatement insert = connection.prepareStatement("""
             INSERT OR REPLACE INTO check_runs (
               run_id, contract_id, base_version, candidate_version, status,
               breaking_changes, warnings, commit_sha, created_at
             ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
             """);
         PreparedStatement insertLog = connection.prepareStatement("""
             INSERT OR REPLACE INTO check_run_logs (
               log_id, run_id, level, message, created_at
             ) VALUES (?, ?, ?, ?, ?)
             """)) {
      insert.setString(1, "run-1");
      insert.setString(2, "orders.created");
      insert.setString(3, "v1");
      insert.setString(4, "v2");
      insert.setString(5, "PASS");
      insert.setString(6, "[]");
      insert.setString(7, "[\"Enum value added: status.SHIPPED\"]");
      insert.setString(8, "integration-test");
      insert.setString(9, "2026-02-27T12:00:00Z");
      insert.executeUpdate();

      insertLog.setString(1, "log-1");
      insertLog.setString(2, "run-1");
      insertLog.setString(3, "INFO");
      insertLog.setString(4, "Check run completed with status PASS.");
      insertLog.setString(5, "2026-02-27T12:01:00Z");
      insertLog.executeUpdate();
    }
  }

  @Test
  void contractsEndpointReturnsSeededContract() throws Exception {
    MvcResult response = mockMvc.perform(get("/contracts"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
    assertTrue(body.isArray());
    JsonNode seeded = null;
    for (JsonNode item : body) {
      if ("orders.created".equals(item.get("contractId").asText())) {
        seeded = item;
        break;
      }
    }
    assertTrue(seeded != null);
    assertEquals("baseline", seeded.get("policyPack").asText());
  }

  @Test
  void checksEndpointReturnsStructuredArrays() throws Exception {
    MvcResult response = mockMvc.perform(
            get("/checks")
                .queryParam("contractId", "orders.created")
                .queryParam("commitSha", "integration-test"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
    assertTrue(body.isArray());
    assertEquals(1, body.size());
    JsonNode first = body.get(0);
    assertEquals("orders.created", first.get("contractId").asText());
    assertTrue(first.get("breakingChanges").isArray());
    assertTrue(first.get("warnings").isArray());
    assertEquals("Enum value added: status.SHIPPED", first.get("warnings").get(0).asText());
  }

  @Test
  void checksPageEndpointReturnsPaginatedPayload() throws Exception {
    MvcResult response = mockMvc.perform(
            get("/checks/page")
                .queryParam("contractId", "orders.created")
                .queryParam("commitSha", "integration-test")
                .queryParam("limit", "1")
                .queryParam("offset", "0"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
    assertTrue(body.get("items").isArray());
    assertEquals(1, body.get("items").size());
    assertEquals(1, body.get("limit").asInt());
    assertEquals(0, body.get("offset").asInt());
    assertEquals(false, body.get("hasMore").asBoolean());
  }

  @Test
  void checksCreateEndpointQueuesRunAndPersists() throws Exception {
    String payload = """
        {
          "contractId": "orders.created",
          "baseVersion": "v1",
          "candidateVersion": "v2",
          "mode": "BACKWARD",
          "commitSha": "create-test",
          "triggeredBy": "integration-suite"
        }
        """;

    MvcResult response = mockMvc.perform(
            post("/checks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andExpect(status().isAccepted())
        .andReturn();

    JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
    String runId = body.get("runId").asText();
    assertEquals("QUEUED", body.get("status").asText());

    MvcResult getResponse = mockMvc.perform(get("/checks/" + runId))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode checkBody = objectMapper.readTree(getResponse.getResponse().getContentAsString());
    assertEquals("orders.created", checkBody.get("contractId").asText());
    assertEquals("v1", checkBody.get("baseVersion").asText());
    assertEquals("v2", checkBody.get("candidateVersion").asText());
    assertEquals("QUEUED", checkBody.get("status").asText());
  }

  @Test
  void checksCreateEndpointRejectsInvalidMode() throws Exception {
    String payload = """
        {
          "contractId": "orders.created",
          "baseVersion": "v1",
          "candidateVersion": "v2",
          "mode": "SIDEWAYS",
          "triggeredBy": "integration-suite"
        }
        """;

    MvcResult response = mockMvc.perform(
            post("/checks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andExpect(status().isBadRequest())
        .andReturn();

    JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
    assertEquals("INVALID_REQUEST", body.get("code").asText());
  }

  @Test
  void checkRunEndpointReturnsSingleRunById() throws Exception {
    MvcResult response = mockMvc.perform(get("/checks/run-1"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
    assertEquals("run-1", body.get("runId").asText());
    assertEquals("orders.created", body.get("contractId").asText());
  }

  @Test
  void checkRunEndpointReturns404ForUnknownRunId() throws Exception {
    MvcResult response = mockMvc.perform(get("/checks/unknown-run"))
        .andExpect(status().isNotFound())
        .andReturn();
    JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
    assertEquals("CHECK_RUN_NOT_FOUND", body.get("code").asText());
  }

  @Test
  void checkRunLogsEndpointReturnsLogsForRun() throws Exception {
    MvcResult response = mockMvc.perform(get("/checks/run-1/logs"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
    assertTrue(body.isArray());
    assertEquals(1, body.size());
    assertEquals("run-1", body.get(0).get("runId").asText());
    assertEquals("INFO", body.get(0).get("level").asText());
  }

  @Test
  void runLogsEndpointReturnsLogsForRun() throws Exception {
    MvcResult response = mockMvc.perform(get("/runs/run-1/logs"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
    assertTrue(body.isArray());
    assertEquals(1, body.size());
    assertEquals("run-1", body.get(0).get("runId").asText());
  }

  @Test
  void checkRunLogsEndpointReturns404ForUnknownRunId() throws Exception {
    MvcResult response = mockMvc.perform(get("/checks/unknown-run/logs"))
        .andExpect(status().isNotFound())
        .andReturn();
    JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
    assertEquals("CHECK_RUN_NOT_FOUND", body.get("code").asText());
  }

  @Test
  void checksPageEndpointReturns400ForInvalidLimit() throws Exception {
    MvcResult response = mockMvc.perform(get("/checks/page").queryParam("limit", "0"))
        .andExpect(status().isBadRequest())
        .andReturn();
    JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
    assertEquals("INVALID_REQUEST", body.get("code").asText());
  }

  @Test
  void contractsCreateEndpointCreatesNewContract() throws Exception {
    String payload = """
        {
          "contractId": "payments.completed",
          "ownerTeam": "payments",
          "domain": "finance",
          "compatibilityMode": "BACKWARD",
          "initialVersion": "v1",
          "schema": {
            "type": "object",
            "properties": {
              "paymentId": { "type": "string" },
              "amount": { "type": "number" }
            },
            "required": ["paymentId"]
          }
        }
        """;

    MvcResult response = mockMvc.perform(
            post("/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andExpect(status().isCreated())
        .andReturn();
    JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
    assertEquals("payments.completed", body.get("contractId").asText());

    MvcResult getResponse = mockMvc.perform(get("/contracts/payments.completed"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode fetched = objectMapper.readTree(getResponse.getResponse().getContentAsString());
    assertEquals("payments.completed", fetched.get("contractId").asText());
    assertEquals("v1", fetched.get("versions").get(0).asText());
  }

  @Test
  void contractVersionCreateEndpointCreatesNextVersion() throws Exception {
    String payload = """
        {
          "version": "v3",
          "schema": {
            "type": "object",
            "properties": {
              "orderId": { "type": "string" },
              "status": { "type": "string" },
              "region": { "type": "string", "nullable": true }
            }
          }
        }
        """;

    MvcResult response = mockMvc.perform(
            post("/contracts/orders.created/versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andExpect(status().isCreated())
        .andReturn();
    JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
    assertEquals("orders.created", body.get("contractId").asText());
    assertEquals("v3", body.get("version").asText());
  }

  private static synchronized void ensureTestPaths() {
    if (tempRoot != null) {
      return;
    }
    try {
      tempRoot = Files.createTempDirectory("contract-service-it-");
      contractsRoot = tempRoot.resolve("contracts");
      checksDbPath = tempRoot.resolve("checks.db");
      Files.createDirectories(contractsRoot);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create integration test paths.", e);
    }
  }
}
