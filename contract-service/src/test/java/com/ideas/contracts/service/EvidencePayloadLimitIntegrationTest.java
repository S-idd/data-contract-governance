package com.ideas.contracts.service;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "checks.evidence.max-payload-bytes=32",
    "checks.runner.enabled=false"
})
class EvidencePayloadLimitIntegrationTest {
  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private EvidencePayloadLimitFilter payloadLimitFilter;

  @Test
  void declaredOversizePayloadIsRejectedAtTheFilterBoundaryWithStable413Response() throws Exception {
    mockMvc.perform(post("/checks/evidence")
            .header("X-Request-Id", "payload-cap-test")
            .contentType(MediaType.APPLICATION_JSON)
            .content("x".repeat(33)))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(header().string("X-Request-Id", "payload-cap-test"))
        .andExpect(content().string(containsString("\"code\":\"EVIDENCE_PAYLOAD_TOO_LARGE\"")));
  }

  @Test
  void unknownLengthBodyIsStoppedWhenItCrossesTheBoundaryCap() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/checks/evidence") {
      @Override
      public long getContentLengthLong() {
        return -1;
      }
    };
    request.setContent("x".repeat(33).getBytes(StandardCharsets.UTF_8));
    request.addHeader("X-Request-Id", "chunked-payload-cap-test");
    MockHttpServletResponse response = new MockHttpServletResponse();

    payloadLimitFilter.doFilter(request, response, (wrappedRequest, ignoredResponse) ->
        wrappedRequest.getInputStream().transferTo(OutputStream.nullOutputStream()));

    org.junit.jupiter.api.Assertions.assertEquals(413, response.getStatus());
    org.junit.jupiter.api.Assertions.assertTrue(
        response.getContentAsString().contains("\"code\":\"EVIDENCE_PAYLOAD_TOO_LARGE\""));
  }
}
