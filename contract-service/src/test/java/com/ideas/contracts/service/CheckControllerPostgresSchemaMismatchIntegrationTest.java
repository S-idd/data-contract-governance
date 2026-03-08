package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
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
class CheckControllerPostgresSchemaMismatchIntegrationTest {
  private static final String BASE_JDBC_URL = PostgresTestSupport.localJdbcUrl();
  private static final String USERNAME = PostgresTestSupport.localUsername();
  private static final String PASSWORD = PostgresTestSupport.localPassword();
  private static final String SCHEMA = PostgresTestSupport.randomSchema("it_schema_mismatch");
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

  @Test
  void checksEndpointReturnsStructured503WhenPostgresSchemaMismatches() throws Exception {
    prepareSchemaMismatch();

    MvcResult response = mockMvc.perform(get("/checks"))
        .andExpect(status().isServiceUnavailable())
        .andReturn();

    assertEquals(503, response.getResponse().getStatus());
    assertTrue(response.getResponse().getHeaderNames().contains(RequestIdFilter.REQUEST_ID_HEADER));

    JsonNode payload = objectMapper.readTree(response.getResponse().getContentAsString());
    assertEquals(503, payload.get("status").asInt());
    assertEquals("CHECK_STORE_UNAVAILABLE", payload.get("code").asText());
    assertEquals("Check history store is currently unavailable.", payload.get("message").asText());
    assertEquals("/checks", payload.get("path").asText());
  }

  private void prepareSchemaMismatch() throws Exception {
    PostgresTestSupport.assumeLocalPostgresAvailable();
    PostgresTestSupport.createSchema(BASE_JDBC_URL, USERNAME, PASSWORD, SCHEMA);
    PostgresTestSupport.migrateSchema(schemaJdbcUrl, USERNAME, PASSWORD);
    PostgresTestSupport.dropWarningsColumn(schemaJdbcUrl, USERNAME, PASSWORD);
  }

  private static synchronized void ensureTestPaths() {
    if (tempRoot != null) {
      return;
    }
    try {
      tempRoot = Files.createTempDirectory("check-controller-postgres-schema-mismatch-it-");
      contractsRoot = tempRoot.resolve("contracts");
      Files.createDirectories(contractsRoot);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create integration test paths.", e);
    }
  }
}
