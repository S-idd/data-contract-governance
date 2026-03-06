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
class CheckControllerPostgresNetworkFailureIntegrationTest {
  private static Path tempRoot;
  private static Path contractsRoot;

  @Autowired
  private MockMvc mockMvc;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    ensureTestPaths();
    registry.add("contracts.root", () -> contractsRoot.toString());
    registry.add("checks.db.url", () -> "jdbc:postgresql://127.0.0.1:1/contracts?connectTimeout=1&socketTimeout=1");
    registry.add("checks.db.username", () -> "contracts_user");
    registry.add("checks.db.password", () -> "contracts_password");
    registry.add("checks.db.pool.connection-timeout", () -> "500ms");
  }

  @Test
  void checksEndpointReturnsStructured503WhenPostgresNetworkIsUnavailable() throws Exception {
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

  private static synchronized void ensureTestPaths() {
    if (tempRoot != null) {
      return;
    }
    try {
      tempRoot = Files.createTempDirectory("check-controller-postgres-network-failure-it-");
      contractsRoot = tempRoot.resolve("contracts");
      Files.createDirectories(contractsRoot);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create integration test paths.", e);
    }
  }
}
