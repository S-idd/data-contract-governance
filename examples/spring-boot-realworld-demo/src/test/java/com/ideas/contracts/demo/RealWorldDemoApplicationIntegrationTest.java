package com.ideas.contracts.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class RealWorldDemoApplicationIntegrationTest {
  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private DemoWebhookInbox webhookInbox;

  @BeforeEach
  void clearWebhookInbox() {
    webhookInbox.clear();
  }

  @Test
  void acceptsOrderPayloadThatMatchesTheRuntimeContract() throws Exception {
    mockMvc.perform(post("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"orderId\":\"order-123\",\"status\":\"CREATED\",\"amount\":42.50}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accepted").value(true));
  }

  @Test
  void rejectsOrderPayloadThatBreaksTheRuntimeContract() throws Exception {
    mockMvc.perform(post("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"orderId\":42,\"status\":\"CREATED\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONTRACT_PAYLOAD_INVALID"));
  }

  @Test
  void capturesWebhookEventsForTheBreakingChangeWalkthrough() throws Exception {
    mockMvc.perform(post("/demo/webhooks")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "eventId":"event-1",
                  "eventType":"CONTRACT_CHECK_FAILED",
                  "contractId":"orders.created",
                  "runId":"run-breaking",
                  "summary":"Compatibility check failed."
                }
                """))
        .andExpect(status().isAccepted());

    mockMvc.perform(get("/demo/webhooks"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.events[0].eventType").value("CONTRACT_CHECK_FAILED"))
        .andExpect(jsonPath("$.events[0].runId").value("run-breaking"));
  }

}
