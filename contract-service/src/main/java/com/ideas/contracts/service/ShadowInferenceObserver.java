package com.ideas.contracts.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideas.contracts.core.CompatibilityEngineIdentity;
import com.ideas.contracts.core.CompatibilityResult;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class ShadowInferenceObserver {
  private static final Logger LOGGER = LoggerFactory.getLogger(ShadowInferenceObserver.class);

  private final ShadowInferenceProperties properties;
  private final ShadowInferenceGateway gateway;
  private final ObjectMapper objectMapper;
  private final Executor executor;
  private final ExecutorService ownedExecutor;

  @Autowired
  ShadowInferenceObserver(
      ShadowInferenceProperties properties,
      ShadowInferenceGateway gateway,
      ObjectMapper objectMapper) {
    this(properties, gateway, objectMapper, createExecutor(properties), true);
  }

  ShadowInferenceObserver(
      ShadowInferenceProperties properties,
      ShadowInferenceGateway gateway,
      ObjectMapper objectMapper,
      Executor executor) {
    this(properties, gateway, objectMapper, executor, false);
  }

  private ShadowInferenceObserver(
      ShadowInferenceProperties properties,
      ShadowInferenceGateway gateway,
      ObjectMapper objectMapper,
      Executor executor,
      boolean ownsExecutor) {
    this.properties = properties;
    this.gateway = gateway;
    this.objectMapper = objectMapper;
    this.executor = executor;
    this.ownedExecutor = ownsExecutor && executor instanceof ExecutorService service
        ? service
        : null;
  }

  /**
   * Enqueues a log-only observation. This method never waits for inference and never propagates a
   * shadow failure into the authoritative check path.
   */
  void observe(ShadowInferenceObservation observation) {
    if (!properties.isEnabled()) {
      return;
    }
    try {
      executor.execute(() -> runObservation(observation));
    } catch (RuntimeException error) {
      logFailure(observation, Instant.now(), "-", "-", "DISPATCH", error);
    }
  }

  private void runObservation(ShadowInferenceObservation observation) {
    Instant observedAt = Instant.now();
    String baseSha256 = "-";
    String candidateSha256 = "-";
    try {
      byte[] baseBytes = Files.readAllBytes(observation.baseSchemaPath());
      byte[] candidateBytes = Files.readAllBytes(observation.candidateSchemaPath());
      baseSha256 = CompatibilityEngineIdentity.sha256(baseBytes);
      candidateSha256 = CompatibilityEngineIdentity.sha256(candidateBytes);
      JsonNode baseSchema = readSchema("base", baseBytes);
      JsonNode candidateSchema = readSchema("candidate", candidateBytes);
      ShadowInferenceResponse response = gateway.predict(new ShadowInferenceRequest(
          baseSchema,
          candidateSchema,
          observation.policyPack()));
      logPrediction(observation, observedAt, baseSha256, candidateSha256, response);
    } catch (ShadowInferenceException error) {
      logFailure(
          observation,
          observedAt,
          baseSha256,
          candidateSha256,
          error.failureStage(),
          error);
    } catch (IOException error) {
      logFailure(
          observation,
          observedAt,
          baseSha256,
          candidateSha256,
          "SCHEMA_READ",
          error);
    } catch (RuntimeException error) {
      logFailure(
          observation,
          observedAt,
          baseSha256,
          candidateSha256,
          "UNEXPECTED",
          error);
    }
  }

  private JsonNode readSchema(String role, byte[] bytes) {
    try {
      return objectMapper.readTree(bytes);
    } catch (IOException error) {
      throw new ShadowInferenceException(
          "SCHEMA_PARSE", role + " schema could not be parsed for shadow inference", error);
    }
  }

  private void logPrediction(
      ShadowInferenceObservation observation,
      Instant observedAt,
      String baseSha256,
      String candidateSha256,
      ShadowInferenceResponse response) {
    String authoritativeLabel = authoritativeLabel(observation.authoritativeResult());
    boolean agreement = response.predictions().stream()
        .allMatch(prediction -> authoritativeLabel.equals(prediction.label()));
    final String rawPredictions;
    try {
      rawPredictions = objectMapper.writeValueAsString(response.predictions());
    } catch (JsonProcessingException error) {
      throw new ShadowInferenceException(
          "LOG_SERIALIZATION", "raw seed predictions could not be serialized", error);
    }
    LOGGER.info(
        "event=shadow_inference_prediction component=shadow_inference role=LOG_ONLY observed_at={} contract_id={} run_id={} base_version={} candidate_version={} commit_sha={} base_schema_sha256={} candidate_schema_sha256={} authoritative_label={} authoritative_status={} compatibility_mode={} policy_pack={} breaking_changes_count={} warnings_count={} agreement_basis=ALL_THREE agreement={} seed_predictions={}",
        observedAt,
        safe(observation.contractId()),
        safe(observation.runId()),
        safe(observation.baseVersion()),
        safe(observation.candidateVersion()),
        safe(observation.commitSha()),
        baseSha256,
        candidateSha256,
        authoritativeLabel,
        observation.authoritativeResult().status(),
        observation.mode(),
        safe(observation.policyPack()),
        observation.authoritativeResult().breakingChanges().size(),
        observation.authoritativeResult().warnings().size(),
        agreement,
        rawPredictions);
  }

  private void logFailure(
      ShadowInferenceObservation observation,
      Instant observedAt,
      String baseSha256,
      String candidateSha256,
      String failureStage,
      Throwable error) {
    CompatibilityResult result = observation.authoritativeResult();
    LOGGER.warn(
        "event=shadow_inference_call_failed component=shadow_inference role=LOG_ONLY observed_at={} contract_id={} run_id={} base_version={} candidate_version={} commit_sha={} base_schema_sha256={} candidate_schema_sha256={} authoritative_label={} authoritative_status={} compatibility_mode={} policy_pack={} agreement_basis=ALL_THREE agreement=NOT_APPLICABLE failure_stage={} error_type={} error_message={}",
        observedAt,
        safe(observation.contractId()),
        safe(observation.runId()),
        safe(observation.baseVersion()),
        safe(observation.candidateVersion()),
        safe(observation.commitSha()),
        baseSha256,
        candidateSha256,
        authoritativeLabel(result),
        result.status(),
        observation.mode(),
        safe(observation.policyPack()),
        safe(failureStage),
        error.getClass().getSimpleName(),
        safe(error.getMessage()));
  }

  private String authoritativeLabel(CompatibilityResult result) {
    if (!result.breakingChanges().isEmpty()) {
      return "BREAKING";
    }
    if (!result.warnings().isEmpty()) {
      return "WARNING";
    }
    return "SAFE";
  }

  private String safe(String value) {
    if (value == null || value.isBlank()) {
      return "-";
    }
    return value.replaceAll("\\s+", "_").trim();
  }

  @PreDestroy
  void close() {
    if (ownedExecutor != null) {
      ownedExecutor.shutdownNow();
    }
  }

  private static ExecutorService createExecutor(ShadowInferenceProperties properties) {
    int workerThreads = Math.max(1, properties.getWorkerThreads());
    int queueCapacity = Math.max(1, properties.getQueueCapacity());
    return new ThreadPoolExecutor(
        workerThreads,
        workerThreads,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(queueCapacity),
        new ShadowThreadFactory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  private static final class ShadowThreadFactory implements ThreadFactory {
    private final AtomicInteger sequence = new AtomicInteger();

    @Override
    public Thread newThread(Runnable task) {
      Thread thread = new Thread(task, "dcg-shadow-inference-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  }
}
