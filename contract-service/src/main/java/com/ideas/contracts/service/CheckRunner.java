package com.ideas.contracts.service;

import com.ideas.contracts.core.CompatibilityMode;
import com.ideas.contracts.core.CompatibilityResult;
import com.ideas.contracts.core.ContractEngine;
import com.ideas.contracts.core.PolicyPack;
import com.ideas.contracts.service.model.ContractDetailResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "checks.runner", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CheckRunner {
  private static final Logger LOGGER = LoggerFactory.getLogger(CheckRunner.class);

  private final CheckRunRepository checkRunStore;
  private final ContractEngine contractEngine;
  private final ContractCatalogService contractCatalogService;
  private final PolicyPackRegistry policyPackRegistry;
  private final CheckMetrics checkMetrics;
  private final Path contractsRoot;
  private final int maxPerPoll;
  private final int maxRetries;
  private final ConcurrentMap<String, Integer> retryCounts = new ConcurrentHashMap<>();

  public CheckRunner(
      CheckRunRepository checkRunStore,
      ContractEngine contractEngine,
      ContractCatalogService contractCatalogService,
      PolicyPackRegistry policyPackRegistry,
      CheckMetrics checkMetrics,
      @Value("${contracts.root:contracts}") String contractsRoot,
      @Value("${checks.runner.max-per-poll:3}") int maxPerPoll,
      @Value("${checks.runner.max-retries:2}") int maxRetries) {
    this.checkRunStore = checkRunStore;
    this.contractEngine = contractEngine;
    this.contractCatalogService = contractCatalogService;
    this.policyPackRegistry = policyPackRegistry;
    this.checkMetrics = checkMetrics;
    this.contractsRoot = Paths.get(contractsRoot);
    this.maxPerPoll = Math.max(1, maxPerPoll);
    this.maxRetries = Math.max(0, maxRetries);
  }

  @Scheduled(fixedDelayString = "${checks.runner.poll-interval:5000}")
  public void pollQueue() {
    int processed = 0;
    while (processed < maxPerPoll) {
      Optional<CheckRunRepository.QueuedCheckRun> next = checkRunStore.claimNextQueuedRun();
      if (next.isEmpty()) {
        return;
      }
      processed++;
      processRun(next.get());
    }
  }

  private void processRun(CheckRunRepository.QueuedCheckRun run) {
    Instant startedAt = Instant.now();
    checkRunStore.appendLog(run.runId(), "INFO", "code=check_run_claimed message=Check run claimed for execution.");
    try {
      CompatibilityMode mode = resolveMode(run.mode());
      ContractDetailResponse contract = contractCatalogService.getContract(run.contractId())
          .orElseThrow(() -> new IllegalStateException("Contract not found: " + run.contractId()));
      PolicyPack policyPack = policyPackRegistry.resolve(contract.policyPack());
      Path baseSchema = resolveSchemaPath(run.contractId(), run.baseVersion());
      Path candidateSchema = resolveSchemaPath(run.contractId(), run.candidateVersion());
      CompatibilityResult result = contractEngine.checkCompatibility(baseSchema, candidateSchema, mode, policyPack);

      boolean updated = checkRunStore.completeRun(
          run.runId(),
          result.status().name(),
          result.breakingChanges(),
          result.warnings());
      if (!updated) {
        LOGGER.warn("event=check_run_update_skipped component=check_runner run_id={} message=Run not updated", run.runId());
        return;
      }
      retryCounts.remove(run.runId());
      Duration duration = Duration.between(startedAt, Instant.now());
      checkMetrics.recordCompleted(run.contractId(), result.status().name(), duration);
      if (result.status() == com.ideas.contracts.core.CheckStatus.FAIL) {
        checkMetrics.recordFailed(run.contractId(), "compatibility_breaking");
      }
      checkRunStore.appendLog(
          run.runId(),
          "INFO",
          "code=check_run_completed status=" + result.status().name() + " message=Check run completed.");
    } catch (Exception ex) {
      handleFailure(run, ex, startedAt);
    }
  }

  private void handleFailure(CheckRunRepository.QueuedCheckRun run, Exception ex, Instant startedAt) {
    int attempt = retryCounts.merge(run.runId(), 1, Integer::sum);
    String message = summarizeFailure(ex);
    checkRunStore.appendLog(
        run.runId(),
        "ERROR",
        "code=check_run_attempt_failed attempt=" + attempt + " message=" + message);

    if (attempt <= maxRetries) {
      boolean requeued = checkRunStore.requeueRun(run.runId());
      if (requeued) {
        checkRunStore.appendLog(
            run.runId(),
            "WARN",
            "code=check_run_requeued attempt=" + attempt + " maxRetries=" + maxRetries + " message=Run re-queued for retry.");
      } else {
        LOGGER.warn(
            "event=check_run_requeue_skipped component=check_runner run_id={} message=Run not re-queued",
            run.runId());
        retryCounts.remove(run.runId());
      }
      return;
    }

    boolean updated = checkRunStore.completeRun(
        run.runId(),
        "FAIL",
        List.of(),
        List.of("Execution error: " + message));
    if (!updated) {
      LOGGER.warn(
          "event=check_run_finalize_skipped component=check_runner run_id={} message=Run not finalized",
          run.runId());
    } else {
      checkMetrics.recordCompleted(run.contractId(), "FAIL", Duration.between(startedAt, Instant.now()));
      checkMetrics.recordFailed(run.contractId(), "execution_error");
      checkRunStore.appendLog(run.runId(), "ERROR", "code=check_run_failed message=Run failed after retries.");
    }
    retryCounts.remove(run.runId());
  }

  private CompatibilityMode resolveMode(String rawMode) {
    if (rawMode == null || rawMode.isBlank()) {
      throw new IllegalStateException("Compatibility mode is missing.");
    }
    try {
      return CompatibilityMode.valueOf(rawMode.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException("Unsupported compatibility mode: " + rawMode, ex);
    }
  }

  private Path resolveSchemaPath(String contractId, String version) {
    if (contractId == null || contractId.isBlank()) {
      throw new IllegalStateException("contractId is missing.");
    }
    if (version == null || version.isBlank()) {
      throw new IllegalStateException("version is missing.");
    }
    Path schemaPath = contractsRoot.resolve(contractId).resolve(version + ".json");
    if (!Files.exists(schemaPath)) {
      throw new IllegalStateException("Schema file does not exist: " + schemaPath);
    }
    return schemaPath;
  }

  private String summarizeFailure(Exception ex) {
    String message = ex.getMessage();
    if (message == null || message.isBlank()) {
      return ex.getClass().getSimpleName();
    }
    return ex.getClass().getSimpleName() + ": " + message;
  }
}
