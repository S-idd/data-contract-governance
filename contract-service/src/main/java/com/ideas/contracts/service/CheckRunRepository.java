package com.ideas.contracts.service;

import com.ideas.contracts.service.model.CheckRunCreateRequest;
import com.ideas.contracts.service.model.CheckRunCreateResponse;
import com.ideas.contracts.service.model.CheckRunLogResponse;
import com.ideas.contracts.service.model.CheckRunPageResponse;
import com.ideas.contracts.service.model.CheckRunResponse;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public interface CheckRunRepository {
  record HealthSnapshot(boolean available, String reason) {}

  record PoolSnapshot(
      int totalConnections,
      int activeConnections,
      int idleConnections,
      int threadsAwaitingConnection,
      int maximumPoolSize,
      int minimumIdle,
      long connectionTimeoutMs) {}

  record QueuedCheckRun(
      String runId,
      String contractId,
      String baseVersion,
      String candidateVersion,
      String mode,
      String commitSha,
      String triggeredBy) {}

  List<CheckRunResponse> list(String contractId, String commitSha);

  CheckRunPageResponse listPage(CheckRunQuery query);

  Optional<CheckRunResponse> findByRunId(String runId);

  List<CheckRunLogResponse> listLogs(String runId);

  Optional<QueuedCheckRun> claimNextQueuedRun();

  boolean completeRun(String runId, String status, List<String> breakingChanges, List<String> warnings);

  boolean requeueRun(String runId);

  void appendLog(String runId, String level, String message);

  CheckRunCreateResponse createQueuedRun(CheckRunCreateRequest request);

  void recordAuditLog(AuditLogEntry entry);

  int backfillLegacyRuns(
      Function<String, String> modeResolver,
      String defaultTriggeredBy,
      String defaultMode);

  String configuredDbTarget();

  PoolSnapshot poolSnapshot();

  HealthSnapshot healthSnapshot();
}
