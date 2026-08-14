package com.ideas.contracts.demo;

import com.ideas.contracts.sdk.ContractValidationClient;
import com.ideas.contracts.sdk.SubmitCheckRequest;
import com.ideas.contracts.sdk.SubmitCheckResponse;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
class DemoCheckSubmissionService {
  private static final String CONTRACT_ID = "orders.created";

  private final ContractValidationClient client;
  private final DemoDcgProperties properties;

  DemoCheckSubmissionService(ContractValidationClient client, DemoDcgProperties properties) {
    this.client = client;
    this.properties = properties;
  }

  DemoCheckSubmission submit(DemoCheckScenario scenario) {
    SubmitCheckResponse response = client.submitCheck(new SubmitCheckRequest(
        CONTRACT_ID,
        scenario.baseVersion(),
        scenario.candidateVersion(),
        "BACKWARD",
        "realworld-demo-" + scenario.name().toLowerCase() + "-" + Instant.now().toEpochMilli(),
        "spring-boot-realworld-demo"));
    return new DemoCheckSubmission(
        response.runId(),
        response.status(),
        scenario.name().toLowerCase(),
        CONTRACT_ID,
        scenario.baseVersion(),
        scenario.candidateVersion(),
        trimTrailingSlash(properties.getServiceBaseUrl()) + "/ui/checks/" + response.runId());
  }

  private String trimTrailingSlash(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
