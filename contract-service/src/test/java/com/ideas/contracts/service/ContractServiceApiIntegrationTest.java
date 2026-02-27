package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContractServiceApiIntegrationTest {
  private static Path tempRoot;
  private static Path contractsRoot;
  private static Path checksDbPath;

  @LocalServerPort
  private int port;

  @Autowired
  private TestRestTemplate restTemplate;

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

    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + checksDbPath);
         PreparedStatement create = connection.prepareStatement("""
             CREATE TABLE IF NOT EXISTS check_runs (
               run_id TEXT PRIMARY KEY,
               contract_id TEXT NOT NULL,
               base_version TEXT NOT NULL,
               candidate_version TEXT NOT NULL,
               status TEXT NOT NULL,
               breaking_changes TEXT,
               warnings TEXT,
               commit_sha TEXT,
               created_at TEXT NOT NULL
             )
             """);
         PreparedStatement insert = connection.prepareStatement("""
             INSERT OR REPLACE INTO check_runs (
               run_id, contract_id, base_version, candidate_version, status,
               breaking_changes, warnings, commit_sha, created_at
             ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
             """)) {
      create.execute();
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
    ResponseEntity<String> response =
        restTemplate.getForEntity("http://localhost:" + port + "/contracts", String.class);

    assertEquals(200, response.getStatusCode().value());
    JsonNode body = objectMapper.readTree(response.getBody());
    assertTrue(body.isArray());
    assertEquals(1, body.size());
    assertEquals("orders.created", body.get(0).get("contractId").asText());
    assertEquals("v2", body.get(0).get("latestVersion").asText());
  }

  @Test
  void checksEndpointReturnsStructuredArrays() throws Exception {
    ResponseEntity<String> response =
        restTemplate.getForEntity(
            "http://localhost:" + port + "/checks?contractId=orders.created",
            String.class);

    assertEquals(200, response.getStatusCode().value());
    JsonNode body = objectMapper.readTree(response.getBody());
    assertTrue(body.isArray());
    assertEquals(1, body.size());
    JsonNode first = body.get(0);
    assertEquals("orders.created", first.get("contractId").asText());
    assertTrue(first.get("breakingChanges").isArray());
    assertTrue(first.get("warnings").isArray());
    assertEquals("Enum value added: status.SHIPPED", first.get("warnings").get(0).asText());
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
