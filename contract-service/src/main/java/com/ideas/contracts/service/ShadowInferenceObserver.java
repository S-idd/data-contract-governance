package com.ideas.contracts.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideas.contracts.core.CompatibilityEngineIdentity;
import com.ideas.contracts.core.CompatibilityResult;
import com.ideas.contracts.service.model.CheckRunAdvisoryResponse;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
  private MetadataStore checkRunStore;
  private static final String MODEL_VERSION = "frozen-v9-three-seed";
  private static final String FEATURE_VERSION = "dcg-features-v6";
  private static final String MODEL_ARTIFACT_SHA256 = CompatibilityEngineIdentity.sha256((
      "5da2fedbee5d1b3c84c79cb75e2cd10c0b3462066b66571570e93fb7ccd84988"
      + "bd464322c272b8ec1d5ab88605022d4a48e3738b63ab1791a4c7e56f37222ac8"
      + "24ec9e4e758370093c228af34e3b05ac65820b3fd413ca80a269206ce1edd2c0")
      .getBytes(java.nio.charset.StandardCharsets.UTF_8));

  @Autowired
  void attachStore(MetadataStore store) {
    this.checkRunStore = store;
  }

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

  /** Enqueues an optional advisory after the authoritative check has been persisted. */
  void observe(ShadowInferenceObservation observation) {
    if (!properties.isEnabled()) {
      return;
    }
    try {
      executor.execute(() -> runObservation(observation));
    } catch (RuntimeException error) {
      logFailure(observation, Instant.now(), "-", "-", "DISPATCH", error);
      persistFailure(observation, Instant.now(), 0, "UNAVAILABLE", null);
    }
  }

  private void runObservation(ShadowInferenceObservation observation) {
    Instant observedAt = Instant.now();
    String baseSha256 = "-";
    String candidateSha256 = "-";
    String inputHash = null;
    try {
      byte[] baseBytes = Files.readAllBytes(observation.baseSchemaPath());
      byte[] candidateBytes = Files.readAllBytes(observation.candidateSchemaPath());
      baseSha256 = CompatibilityEngineIdentity.sha256(baseBytes);
      candidateSha256 = CompatibilityEngineIdentity.sha256(candidateBytes);
      JsonNode baseSchema = readSchema("base", baseBytes);
      JsonNode candidateSchema = readSchema("candidate", candidateBytes);
      ShadowInferenceRequest request = new ShadowInferenceRequest(
          baseSchema, candidateSchema, observation.policyPack());
      inputHash = CompatibilityEngineIdentity.sha256(objectMapper.writeValueAsBytes(request));
      ShadowInferenceResponse response = gateway.predict(request);
      logPrediction(observation, observedAt, baseSha256, candidateSha256, response);
      persistPrediction(observation, observedAt, inputHash, response);
    } catch (ShadowInferenceException error) {
      logFailure(
          observation,
          observedAt,
          baseSha256,
          candidateSha256,
          error.failureStage(),
          error);
      persistFailure(observation, observedAt, elapsed(observedAt),
          failureStatus(error.failureStage()), inputHash);
    } catch (IOException error) {
      logFailure(
          observation,
          observedAt,
          baseSha256,
          candidateSha256,
          "SCHEMA_READ",
          error);
      persistFailure(observation, observedAt, elapsed(observedAt), "UNAVAILABLE", inputHash);
    } catch (RuntimeException error) {
      logFailure(
          observation,
          observedAt,
          baseSha256,
          candidateSha256,
          "UNEXPECTED",
          error);
      persistFailure(observation, observedAt, elapsed(observedAt), "UNAVAILABLE", inputHash);
    }
  }

  private void persistPrediction(
      ShadowInferenceObservation observation, Instant started, String inputHash,
      ShadowInferenceResponse response) {
    String authoritative = authoritativeLabel(observation.authoritativeResult());
    Map<String, Long> votes = response.predictions().stream().collect(
        java.util.stream.Collectors.groupingBy(ShadowInferenceResponse.SeedPrediction::label,
            java.util.stream.Collectors.counting()));
    String label = List.of("SAFE", "WARNING", "BREAKING").stream()
        .max(java.util.Comparator.comparingLong(name -> votes.getOrDefault(name, 0L)))
        .orElse("SAFE");
    Map<String, Double> probabilities = Map.of(
        "SAFE", response.predictions().stream().mapToDouble(p -> p.probabilities().safe()).average().orElse(0),
        "WARNING", response.predictions().stream().mapToDouble(p -> p.probabilities().warning()).average().orElse(0),
        "BREAKING", response.predictions().stream().mapToDouble(p -> p.probabilities().breaking()).average().orElse(0));
    List<CheckRunAdvisoryResponse.SeedPrediction> seeds = response.predictions().stream()
        .map(p -> new CheckRunAdvisoryResponse.SeedPrediction(p.seed(), p.label(),
            Map.of("SAFE", p.probabilities().safe(), "WARNING", p.probabilities().warning(),
                "BREAKING", p.probabilities().breaking())))
        .toList();
    persist(new CheckRunAdvisoryResponse(observation.runId(), true,
        properties.isTestOnlyAdapter(), "AVAILABLE",
        properties.isTestOnlyAdapter() ? "test-only-adapter" : MODEL_VERSION,
        properties.isTestOnlyAdapter() ? null : MODEL_ARTIFACT_SHA256,
        properties.isTestOnlyAdapter() ? null : FEATURE_VERSION, inputHash, label,
        probabilities, seeds, elapsed(started),
        authoritative.equals(label) ? "AGREES" : "DISAGREES",
        started.toString(), Instant.now().toString()));
  }

  private void persistFailure(ShadowInferenceObservation observation, Instant started,
      long durationMs, String status, String inputHash) {
    persist(new CheckRunAdvisoryResponse(observation.runId(), true,
        properties.isTestOnlyAdapter(), status,
        properties.isTestOnlyAdapter() ? "test-only-adapter" : MODEL_VERSION,
        properties.isTestOnlyAdapter() ? null : MODEL_ARTIFACT_SHA256,
        properties.isTestOnlyAdapter() ? null : FEATURE_VERSION, inputHash, null,
        null, null, durationMs, "NOT_AVAILABLE", started.toString(), Instant.now().toString()));
  }

  private void persist(CheckRunAdvisoryResponse advisory) {
    if (checkRunStore == null) {
      return;
    }
    try {
      checkRunStore.saveAdvisory(advisory);
      checkRunStore.appendLog(advisory.runId(), "INFO",
          "code=ai_advisory_completed advisory_only=true status=" + advisory.status()
              + " agreement=" + advisory.agreement());
    } catch (RuntimeException error) {
      LOGGER.warn("event=shadow_inference_persist_failed run_id={} error_type={}",
          safe(advisory.runId()), error.getClass().getSimpleName());
    }
  }

  private static String failureStatus(String stage) {
    return switch (stage) {
      case "TIMEOUT" -> "TIMEOUT";
      case "MALFORMED_RESPONSE" -> "INVALID_OUTPUT";
      default -> "UNAVAILABLE";
    };
  }

  private static long elapsed(Instant started) {
    return Math.max(0, java.time.Duration.between(started, Instant.now()).toMillis());
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
