package com.ideas.contracts.service;

import com.ideas.contracts.core.CompatibilityMode;
import com.ideas.contracts.core.CompatibilityResult;
import com.ideas.contracts.core.ContractEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

  private final CheckRunStore checkRunStore;
  private final ContractEngine contractEngine;
  private final Path contractsRoot;
  private final int maxPerPoll;
  private final int maxRetries;
  private final ConcurrentMap<String, Integer> retryCounts = new ConcurrentHashMap<>();

  public CheckRunner(
      CheckRunStore checkRunStore,
      ContractEngine contractEngine,
      @Value("${contracts.root:contracts}") String contractsRoot,
      @Value("${checks.runner.max-per-poll:3}") int maxPerPoll,
      @Value("${checks.runner.max-retries:2}") int maxRetries) {
    this.checkRunStore = checkRunStore;
    this.contractEngine = contractEngine;
    this.contractsRoot = Paths.get(contractsRoot);
    this.maxPerPoll = Math.max(1, maxPerPoll);
    this.maxRetries = Math.max(0, maxRetries);
  }

  @Scheduled(fixedDelayString = "${checks.runner.poll-interval:5000}")
  public void pollQueue() {
    int processed = 0;
    while (processed < maxPerPoll) {
      Optional<CheckRunStore.QueuedCheckRun> next = checkRunStore.claimNextQueuedRun();
      if (next.isEmpty()) {
        return;
      }
      processed++;
      processRun(next.get());
    }
  }

  private void processRun(CheckRunStore.QueuedCheckRun run) {
    checkRunStore.appendLog(run.runId(), "INFO", "Check run claimed for execution.");
    try {
      CompatibilityMode mode = resolveMode(run.mode());
      Path baseSchema = resolveSchemaPath(run.contractId(), run.baseVersion());
      Path candidateSchema = resolveSchemaPath(run.contractId(), run.candidateVersion());
      CompatibilityResult result = contractEngine.checkCompatibility(baseSchema, candidateSchema, mode);

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
      checkRunStore.appendLog(
          run.runId(),
          "INFO",
          "Check run completed with status " + result.status().name() + ".");
    } catch (Exception ex) {
      handleFailure(run, ex);
    }
  }

  private void handleFailure(CheckRunStore.QueuedCheckRun run, Exception ex) {
    int attempt = retryCounts.merge(run.runId(), 1, Integer::sum);
    String message = summarizeFailure(ex);
    checkRunStore.appendLog(
        run.runId(),
        "ERROR",
        "Attempt " + attempt + " failed: " + message);

    if (attempt <= maxRetries) {
      boolean requeued = checkRunStore.requeueRun(run.runId());
      if (requeued) {
        checkRunStore.appendLog(
            run.runId(),
            "WARN",
            "Re-queued for retry (" + attempt + "/" + maxRetries + ").");
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
      checkRunStore.appendLog(run.runId(), "ERROR", "Run failed after retries.");
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
