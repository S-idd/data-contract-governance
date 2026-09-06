package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ideas.contracts.service.model.CheckRunCreateRequest;
import com.ideas.contracts.service.model.CheckRunCreateResponse;
import com.ideas.contracts.service.model.CheckRunResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
@Import(CheckRunnerIntegrationTest.ShadowInferenceTestConfiguration.class)
@TestPropertySource(properties = {
    "checks.runner.enabled=true",
    "notifications.enabled=true",
    "notifications.sinks=log",
    "shadow.inference.enabled=true",
    "spring.task.scheduling.enabled=false"
})
class CheckRunnerIntegrationTest {
  private static Path tempRoot;
  private static Path contractsRoot;
  private static Path checksDbPath;

  @Autowired
  private CheckRunStore checkRunStore;

  @Autowired
  private CheckRunner checkRunner;

  @Autowired
  private NotificationService notificationService;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    ensurePaths();
    registry.add("contracts.root", () -> contractsRoot.toString());
    registry.add("checks.db.path", () -> checksDbPath.toString());
  }

  @BeforeAll
  void setUpContract() throws Exception {
    ensurePaths();
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
    Files.writeString(
        contractDir.resolve("v3.json"),
        "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"integer\"},\"status\":{\"type\":\"string\"}}}");
  }

  @Test
  void runnerProcessesQueuedRunAndEmitsALogOnlyShadowObservation(CapturedOutput output)
      throws Exception {
    CheckRunCreateResponse created = checkRunStore.createQueuedRun(new CheckRunCreateRequest(
        "orders.created",
        "v1",
        "v2",
        "BACKWARD",
        "runner-test",
        "integration-test"));

    checkRunner.pollQueue();
    notificationService.dispatchPendingDeliveries();

    CheckRunResponse completed = checkRunStore.findByRunId(created.runId()).orElseThrow();
    assertEquals("PASS", completed.status());
    assertNotNull(completed.finishedAt());
    awaitLog(output, "event=shadow_inference_prediction", created.runId());
    assertTrue(output.toString().contains("role=LOG_ONLY"));
    assertTrue(output.toString().contains("authoritative_status=PASS"));
    assertTrue(output.toString().contains("agreement_basis=ALL_THREE agreement=true"));
    assertTrue(output.toString().contains("seed_predictions="));
  }

  @Test
  void disagreeingShadowPredictionCannotChangeTheFailedCompatibilityCheck(CapturedOutput output)
      throws Exception {
    CheckRunCreateResponse created = checkRunStore.createQueuedRun(new CheckRunCreateRequest(
        "orders.created",
        "v2",
        "v3",
        "BACKWARD",
        "runner-fail-test",
        "integration-test"));

    checkRunner.pollQueue();
    notificationService.dispatchPendingDeliveries();

    CheckRunResponse completed = checkRunStore.findByRunId(created.runId()).orElseThrow();
    assertEquals("FAIL", completed.status());
    assertTrue(output.toString().contains("event=notification_event"));
    assertTrue(output.toString().contains("event_type=CONTRACT_CHECK_FAILED"));
    assertTrue(output.toString().contains("run_id=" + created.runId()));
    awaitLog(output, "event=shadow_inference_prediction", created.runId());
    String shadowLine = output.toString().lines()
        .filter(line -> line.contains("event=shadow_inference_prediction"))
        .filter(line -> line.contains("run_id=" + created.runId()))
        .findFirst()
        .orElseThrow();
    assertTrue(shadowLine.contains("authoritative_status=FAIL"));
    assertTrue(shadowLine.contains("agreement_basis=ALL_THREE agreement=false"));
  }

  private static synchronized void ensurePaths() {
    if (tempRoot != null) {
      return;
    }
    try {
      tempRoot = Files.createTempDirectory("check-runner-it-");
      contractsRoot = tempRoot.resolve("contracts");
      checksDbPath = tempRoot.resolve("checks-runner.db");
      Files.createDirectories(contractsRoot);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create integration test paths.", e);
    }
  }

  private void awaitLog(CapturedOutput output, String event, String runId) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      String logs = output.toString();
      if (logs.contains(event) && logs.contains("run_id=" + runId)) {
        return;
      }
      Thread.sleep(10);
    }
    assertTrue(
        output.toString().contains(event) && output.toString().contains("run_id=" + runId),
        "Expected the asynchronous shadow event for run " + runId);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ShadowInferenceTestConfiguration {
    @Bean
    @Primary
    ShadowInferenceGateway shadowInferenceGateway() {
      return request -> new ShadowInferenceResponse(List.of(
          prediction("20260826"),
          prediction("20260827"),
          prediction("20260828")));
    }

    private static ShadowInferenceResponse.SeedPrediction prediction(String seed) {
      return new ShadowInferenceResponse.SeedPrediction(
          seed,
          "SAFE",
          new ShadowInferenceResponse.Probabilities(0.9, 0.05, 0.05));
    }
  }
}
