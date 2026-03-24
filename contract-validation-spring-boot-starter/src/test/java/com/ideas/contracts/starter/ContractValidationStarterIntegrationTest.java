package com.ideas.contracts.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
    classes = ContractValidationStarterIntegrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContractValidationStarterIntegrationTest {
  private static Path tempRoot;
  private static Path contractsRoot;

  @Autowired
  private MockMvc mockMvc;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    ensurePaths();
    registry.add("contract.validation.enabled", () -> "true");
    registry.add("contract.validation.contracts-root", () -> contractsRoot.toString());
  }

  @BeforeAll
  void setUpContracts() throws Exception {
    ensurePaths();
    Path contractDir = contractsRoot.resolve("payments.created");
    Files.createDirectories(contractDir);
    Files.writeString(
        contractDir.resolve("v1.json"),
        """
        {
          "type": "object",
          "properties": {
            "paymentId": {"type": "string"}
          },
          "required": ["paymentId"]
        }
        """
    );
  }

  @Test
  void validPayloadPassesContractValidation() throws Exception {
    mockMvc.perform(post("/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"paymentId\":\"pay_123\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void invalidPayloadReturnsStructuredError() throws Exception {
    MvcResult response = mockMvc.perform(post("/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":42}"))
        .andExpect(status().isBadRequest())
        .andReturn();

    JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
    assertEquals("CONTRACT_PAYLOAD_INVALID", body.get("code").asText());
  }

  @SpringBootApplication
  static class TestApplication {
    @RestController
    static class TestController {
      @PostMapping("/payments")
      @ValidateContract(contractId = "payments.created", version = "v1")
      public Map<String, Object> createPayment(@RequestBody Map<String, Object> payload) {
        return payload;
      }
    }
  }

  private static synchronized void ensurePaths() {
    if (tempRoot != null) {
      return;
    }
    try {
      tempRoot = Files.createTempDirectory("contract-validation-starter-it-");
      contractsRoot = tempRoot.resolve("contracts");
      Files.createDirectories(contractsRoot);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to prepare test contract root.", ex);
    }
  }
}
