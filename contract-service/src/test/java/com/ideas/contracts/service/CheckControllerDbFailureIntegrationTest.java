package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
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
class CheckControllerDbFailureIntegrationTest {
  private static Path tempRoot;
  private static Path contractsRoot;
  private static Path unavailableDbPath;

  @LocalServerPort
  private int port;

  @Autowired
  private TestRestTemplate restTemplate;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    ensureTestPaths();
    registry.add("contracts.root", () -> contractsRoot.toString());
    registry.add("checks.db.path", () -> unavailableDbPath.toString());
  }

  @Test
  void checksEndpointReturnsStructured503WhenStoreIsUnavailable() throws Exception {
    ResponseEntity<String> response =
        restTemplate.getForEntity("http://localhost:" + port + "/checks", String.class);

    assertEquals(503, response.getStatusCode().value());
    assertTrue(response.getHeaders().containsKey(RequestIdFilter.REQUEST_ID_HEADER));

    JsonNode payload = objectMapper.readTree(response.getBody());
    assertEquals(503, payload.get("status").asInt());
    assertEquals("CHECK_STORE_UNAVAILABLE", payload.get("code").asText());
    assertEquals("Check history store is currently unavailable.", payload.get("message").asText());
    assertEquals("/checks", payload.get("path").asText());
    assertEquals(
        response.getHeaders().getFirst(RequestIdFilter.REQUEST_ID_HEADER),
        payload.get("requestId").asText());
  }

  @Test
  void actuatorHealthReturnsDownWhenStoreIsUnavailable() throws Exception {
    ResponseEntity<String> response =
        restTemplate.getForEntity("http://localhost:" + port + "/actuator/health", String.class);

    assertEquals(503, response.getStatusCode().value());
    JsonNode payload = objectMapper.readTree(response.getBody());
    assertEquals("DOWN", payload.get("status").asText());
  }

  private static synchronized void ensureTestPaths() {
    if (tempRoot != null) {
      return;
    }
    try {
      tempRoot = Files.createTempDirectory("check-db-failure-it-");
      contractsRoot = tempRoot.resolve("contracts");
      unavailableDbPath = tempRoot.resolve("unavailable-checks-db");
      Files.createDirectories(contractsRoot);
      Files.createDirectories(unavailableDbPath);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create integration test paths.", e);
    }
  }
}
