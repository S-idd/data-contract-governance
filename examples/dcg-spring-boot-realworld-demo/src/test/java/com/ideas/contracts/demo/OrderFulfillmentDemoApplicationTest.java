package com.ideas.contracts.demo;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = "contract.validation.contracts-root=contracts")
class OrderFulfillmentDemoApplicationTest {
  @Autowired
  private MockMvc mockMvc;

  @Test
  void acceptsOrderPayloadThatMatchesTheRuntimeContract() throws Exception {
    mockMvc.perform(post("/demo/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"orderId\":\"ord-100\",\"status\":\"CREATED\",\"amount\":42.50}"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("orders.created")));
  }

  @Test
  void rejectsOrderPayloadThatViolatesTheRuntimeContract() throws Exception {
    mockMvc.perform(post("/demo/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"orderId\":\"ord-100\",\"amount\":42.50}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("CONTRACT_PAYLOAD_INVALID")));
  }

  @Test
  void capturesWebhookEventsForTheBreakingChangeDemo() throws Exception {
    mockMvc.perform(post("/demo/webhooks")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"eventId\":\"demo-event-1\",\"eventType\":\"CONTRACT_CHECK_FAILED\",\"contractId\":\"orders.created\"}"))
        .andExpect(status().isAccepted());

    mockMvc.perform(get("/demo/webhooks"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("CONTRACT_CHECK_FAILED")))
        .andExpect(content().string(containsString("orders.created")));
  }
}
