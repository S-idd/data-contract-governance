package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    }
  }

  @Test
  void contractsEndpointReturnsSeededContract() throws Exception {
    MvcResult response = mockMvc.perform(get("/contracts"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
    assertTrue(body.isArray());
    assertEquals(1, body.size());
    assertEquals("orders.created", body.get(0).get("contractId").asText());
    assertEquals("v2", body.get(0).get("latestVersion").asText());
  }

  @Test
  void checksEndpointReturnsStructuredArrays() throws Exception {
    MvcResult response = mockMvc.perform(get("/checks").queryParam("contractId", "orders.created"))
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
  void checksPageEndpointReturns400ForInvalidLimit() throws Exception {
    MvcResult response = mockMvc.perform(get("/checks/page").queryParam("limit", "0"))
        .andExpect(status().isBadRequest())
        .andReturn();
    JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
    assertEquals("INVALID_REQUEST", body.get("code").asText());
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
