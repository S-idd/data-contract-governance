package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
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
class CheckControllerPostgresSuccessIntegrationTest {
  private static final String BASE_JDBC_URL = PostgresTestSupport.localJdbcUrl();
  private static final String USERNAME = PostgresTestSupport.localUsername();
  private static final String PASSWORD = PostgresTestSupport.localPassword();
  private static final String SCHEMA = PostgresTestSupport.randomSchema("it_success");
  private static Path tempRoot;
  private static Path contractsRoot;
  private static String schemaJdbcUrl;

  @Autowired
  private MockMvc mockMvc;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    ensureTestPaths();
    schemaJdbcUrl = PostgresTestSupport.withCurrentSchema(BASE_JDBC_URL, SCHEMA);

    registry.add("contracts.root", () -> contractsRoot.toString());
    registry.add("checks.db.url", () -> schemaJdbcUrl);
    registry.add("checks.db.username", () -> USERNAME);
    registry.add("checks.db.password", () -> PASSWORD);
    registry.add("checks.db.pool.connection-timeout", () -> "500ms");
  }

  @BeforeAll
  void setUpData() throws Exception {
    PostgresTestSupport.assumeLocalPostgresAvailable();
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

    PostgresTestSupport.createSchema(BASE_JDBC_URL, USERNAME, PASSWORD, SCHEMA);
    PostgresTestSupport.migrateSchema(schemaJdbcUrl, USERNAME, PASSWORD);
    PostgresTestSupport.insertCheckRun(
        schemaJdbcUrl,
        USERNAME,
        PASSWORD,
        "pg-run-1",
        "orders.created",
        "PASS",
        "[\"Enum value added: status.SHIPPED\"]");
  }

  @Test
  void checksEndpointReturnsRowsFromPostgresStore() throws Exception {
    MvcResult response = mockMvc.perform(get("/checks").queryParam("contractId", "orders.created"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
    assertTrue(body.isArray());
    assertEquals(1, body.size());
    JsonNode first = body.get(0);
    assertEquals("orders.created", first.get("contractId").asText());
    assertEquals("pg-run-1", first.get("runId").asText());
    assertEquals("Enum value added: status.SHIPPED", first.get("warnings").get(0).asText());
  }

  private static synchronized void ensureTestPaths() {
    if (tempRoot != null) {
      return;
    }
    try {
      tempRoot = Files.createTempDirectory("check-controller-postgres-success-it-");
      contractsRoot = tempRoot.resolve("contracts");
      Files.createDirectories(contractsRoot);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create integration test paths.", e);
    }
  }
}
