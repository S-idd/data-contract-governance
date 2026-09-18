package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideas.contracts.core.CompatibilityMode;
import com.ideas.contracts.core.CompatibilityResult;
import com.ideas.contracts.service.model.CheckRunAdvisoryResponse;
import com.ideas.contracts.service.model.CheckRunCreateRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckRunAdvisoryPersistenceTest {
  @TempDir Path tempDir;

  @Test
  void advisorySurvivesRestartWithoutChangingDeterministicResult() throws Exception {
    CheckStoreProperties settings = new CheckStoreProperties();
    settings.setPath(tempDir.resolve("history.db").toString());
    CheckRunStore store = new CheckRunStore(settings);
    store.initialize();
    String runId = store.createQueuedRun(new CheckRunCreateRequest(
        "students", "v1", "v2", "BACKWARD", "test", "test")).runId();
    assertTrue(store.claimNextQueuedRun().isPresent());
    assertTrue(store.completeRun(runId, "FAIL", List.of("type changed"), List.of()));
    Path base = tempDir.resolve("base.json");
    Path candidate = tempDir.resolve("candidate.json");
    Files.writeString(base, "{\"type\":\"object\"}");
    Files.writeString(candidate, "{\"type\":\"array\"}");

    ShadowInferenceProperties properties = new ShadowInferenceProperties();
    properties.setEnabled(true);
    properties.setTimeout(Duration.ofMillis(100));
    ShadowInferenceObserver observer = new ShadowInferenceObserver(properties,
        request -> new ShadowInferenceResponse(List.of(
            prediction("20260826", "SAFE"), prediction("20260827", "SAFE"),
            prediction("20260828", "SAFE"))), new ObjectMapper(), Runnable::run);
    observer.attachStore(store);
    observer.observe(new ShadowInferenceObservation("students", runId, "v1", "v2", "test",
        base, candidate, CompatibilityMode.BACKWARD, "baseline",
        new CompatibilityResult(com.ideas.contracts.core.CheckStatus.FAIL,
            List.of("type changed"), List.of())));

    assertEquals("FAIL", store.findByRunId(runId).orElseThrow().status());
    CheckRunAdvisoryResponse advisory = store.findAdvisory(runId).orElseThrow();
    assertEquals("AVAILABLE", advisory.status());
    assertEquals("SAFE", advisory.predictionLabel());
    assertEquals("DISAGREES", advisory.agreement());
    assertEquals(3, advisory.seedPredictions().size());
    store.shutdown();

    CheckRunStore restarted = new CheckRunStore(settings);
    restarted.initialize();
    assertEquals("FAIL", restarted.findByRunId(runId).orElseThrow().status());
    assertEquals(advisory, restarted.findAdvisory(runId).orElseThrow());
    restarted.shutdown();
  }

  @Test
  void failedInferenceHasNoFabricatedPrediction() throws Exception {
    CheckStoreProperties settings = new CheckStoreProperties();
    settings.setPath(tempDir.resolve("failed.db").toString());
    CheckRunStore store = new CheckRunStore(settings);
    store.initialize();
    String runId = store.createQueuedRun(new CheckRunCreateRequest(
        "students", "v1", "v2", "BACKWARD", "test", "test")).runId();
    store.claimNextQueuedRun();
    store.completeRun(runId, "PASS", List.of(), List.of());
    Path base = tempDir.resolve("base.json");
    Files.writeString(base, "{\"type\":\"object\"}");
    ShadowInferenceProperties properties = new ShadowInferenceProperties();
    properties.setEnabled(true);
    ShadowInferenceObserver observer = new ShadowInferenceObserver(properties,
        request -> { throw new ShadowInferenceException("TIMEOUT", "test timeout"); },
        new ObjectMapper(), Runnable::run);
    observer.attachStore(store);
    observer.observe(new ShadowInferenceObservation("students", runId, "v1", "v2", "test",
        base, base, CompatibilityMode.BACKWARD, "baseline", CompatibilityResult.pass()));
    CheckRunAdvisoryResponse advisory = store.findAdvisory(runId).orElseThrow();
    assertEquals("TIMEOUT", advisory.status());
    assertNull(advisory.predictionLabel());
    assertNull(advisory.probabilities());
    assertEquals("PASS", store.findByRunId(runId).orElseThrow().status());
    store.shutdown();
  }

  @Test
  void migratesExistingPhaseOneRunWithoutCreatingAdvisory() throws Exception {
    Path db = tempDir.resolve("phase1.db");
    Path oldMigrations = tempDir.resolve("old-migrations");
    Files.createDirectory(oldMigrations);
    for (int version = 1; version <= 12; version++) {
      String resource = List.of(
          "V1__create_check_runs.sql", "V2__add_check_run_indexes.sql",
          "V3__add_check_run_queue_fields.sql", "V4__create_check_run_logs.sql",
          "V5__create_audit_logs.sql", "V6__add_check_run_operational_indexes.sql",
          "V7__create_notification_deliveries.sql", "V8__create_check_evidence.sql",
          "V9__add_check_evidence_provenance.sql",
          "V10__add_evidence_retention_and_rate_limit.sql",
          "V11__add_evidence_raw_payload_purge_marker.sql",
          "V12__add_check_run_idempotency_key.sql").get(version - 1);
      try (var source = getClass().getClassLoader().getResourceAsStream("db/migration/" + resource)) {
        Files.copy(java.util.Objects.requireNonNull(source), oldMigrations.resolve(resource));
      }
    }
    Flyway.configure().dataSource("jdbc:sqlite:" + db, null, null)
        .locations("filesystem:" + oldMigrations).load().migrate();
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
         var statement = connection.createStatement()) {
      statement.executeUpdate("""
          INSERT INTO check_runs (run_id, contract_id, base_version, candidate_version,
            status, created_at) VALUES ('old-run', 'students', 'v1', 'v2', 'FAIL',
            '2026-09-17T00:00:00Z')
          """);
    }
    CheckStoreProperties settings = new CheckStoreProperties();
    settings.setPath(db.toString());
    CheckRunStore store = new CheckRunStore(settings);
    store.initialize();
    assertEquals("FAIL", store.findByRunId("old-run").orElseThrow().status());
    assertTrue(store.findAdvisory("old-run").isEmpty());
    store.shutdown();
  }

  private static ShadowInferenceResponse.SeedPrediction prediction(String seed, String label) {
    return new ShadowInferenceResponse.SeedPrediction(seed, label,
        new ShadowInferenceResponse.Probabilities(0.8, 0.1, 0.1));
  }
}
