package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideas.contracts.core.CheckStatus;
import com.ideas.contracts.core.CompatibilityMode;
import com.ideas.contracts.core.CompatibilityResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ShadowInferenceObserverTest {
  @TempDir
  Path tempDir;

  @Test
  void logsAllRawSeedsWithTheAlreadyComputedAuthoritativeResult(CapturedOutput output)
      throws Exception {
    SchemaPaths schemas = writeSchemas();
    ShadowInferenceObserver observer = new ShadowInferenceObserver(
        enabledProperties(),
        request -> matchingResponse("WARNING"),
        new ObjectMapper(),
        Runnable::run);

    observer.observe(observation(
        schemas,
        new CompatibilityResult(CheckStatus.PASS, List.of(), List.of("policy warning"))));

    String logs = output.toString();
    assertTrue(logs.contains("event=shadow_inference_prediction"));
    assertTrue(logs.contains("role=LOG_ONLY"));
    assertTrue(logs.contains("contract_id=orders.created"));
    assertTrue(logs.contains("run_id=run-shadow-1"));
    assertTrue(logs.contains("base_schema_sha256="));
    assertTrue(logs.contains("candidate_schema_sha256="));
    assertTrue(logs.contains("authoritative_label=WARNING"));
    assertTrue(logs.contains("authoritative_status=PASS"));
    assertTrue(logs.contains("compatibility_mode=BACKWARD"));
    assertTrue(logs.contains("policy_pack=baseline"));
    assertTrue(logs.contains("agreement_basis=ALL_THREE agreement=true"));
    assertTrue(logs.contains("20260826"));
    assertTrue(logs.contains("20260827"));
    assertTrue(logs.contains("20260828"));
    assertTrue(logs.contains("\"safe\":0.1"));
    assertTrue(logs.contains("\"warning\":0.8"));
    assertTrue(logs.contains("\"breaking\":0.1"));
    assertFalse(logs.contains("event=shadow_inference_call_failed"));
  }

  @Test
  void logsTimeoutConnectionRefusalAndMalformedResponseAsFailuresNotDisagreements(
      CapturedOutput output) throws Exception {
    SchemaPaths schemas = writeSchemas();
    CompatibilityResult authoritative = CompatibilityResult.pass();

    for (String stage : List.of("TIMEOUT", "CONNECTION_REFUSED", "MALFORMED_RESPONSE")) {
      ShadowInferenceObserver observer = new ShadowInferenceObserver(
          enabledProperties(),
          request -> {
            throw new ShadowInferenceException(stage, "simulated " + stage);
          },
          new ObjectMapper(),
          Runnable::run);
      observer.observe(observation(schemas, authoritative));
    }

    String logs = output.toString();
    assertTrue(logs.contains("failure_stage=TIMEOUT"));
    assertTrue(logs.contains("failure_stage=CONNECTION_REFUSED"));
    assertTrue(logs.contains("failure_stage=MALFORMED_RESPONSE"));
    assertTrue(logs.contains("agreement=NOT_APPLICABLE"));
    assertFalse(logs.contains("event=shadow_inference_prediction"));
  }

  @Test
  void observeReturnsWithoutWaitingForTheNetworkCall() throws Exception {
    SchemaPaths schemas = writeSchemas();
    CountDownLatch gatewayStarted = new CountDownLatch(1);
    CountDownLatch releaseGateway = new CountDownLatch(1);
    CountDownLatch gatewayCompleted = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      ShadowInferenceObserver observer = new ShadowInferenceObserver(
          enabledProperties(),
          request -> {
            gatewayStarted.countDown();
            try {
              releaseGateway.await(5, TimeUnit.SECONDS);
              return matchingResponse("SAFE");
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              throw new ShadowInferenceException("INTERRUPTED", "test interrupted", error);
            } finally {
              gatewayCompleted.countDown();
            }
          },
          new ObjectMapper(),
          executor);

      observer.observe(observation(schemas, CompatibilityResult.pass()));

      assertTrue(gatewayStarted.await(1, TimeUnit.SECONDS));
      assertFalse(gatewayCompleted.await(100, TimeUnit.MILLISECONDS));
      releaseGateway.countDown();
      assertTrue(gatewayCompleted.await(1, TimeUnit.SECONDS));
    } finally {
      releaseGateway.countDown();
      executor.shutdownNow();
    }
  }

  private SchemaPaths writeSchemas() throws Exception {
    Path base = tempDir.resolve("base.json");
    Path candidate = tempDir.resolve("candidate.json");
    Files.writeString(base, "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}");
    Files.writeString(candidate, "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"note\":{\"type\":\"string\"}}}");
    return new SchemaPaths(base, candidate);
  }

  private ShadowInferenceObservation observation(
      SchemaPaths schemas,
      CompatibilityResult result) {
    return new ShadowInferenceObservation(
        "orders.created",
        "run-shadow-1",
        "v1",
        "v2",
        "abc123",
        schemas.base(),
        schemas.candidate(),
        CompatibilityMode.BACKWARD,
        "baseline",
        result);
  }

  private ShadowInferenceProperties enabledProperties() {
    ShadowInferenceProperties properties = new ShadowInferenceProperties();
    properties.setEnabled(true);
    properties.setTimeout(Duration.ofMillis(100));
    return properties;
  }

  private ShadowInferenceResponse matchingResponse(String label) {
    return new ShadowInferenceResponse(List.of(
        prediction("20260826", label),
        prediction("20260827", label),
        prediction("20260828", label)));
  }

  private ShadowInferenceResponse.SeedPrediction prediction(String seed, String label) {
    return new ShadowInferenceResponse.SeedPrediction(
        seed,
        label,
        new ShadowInferenceResponse.Probabilities(0.1, 0.8, 0.1));
  }

  private record SchemaPaths(Path base, Path candidate) {}
}
