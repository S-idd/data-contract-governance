package com.ideas.contracts.service;

import com.ideas.contracts.service.model.CheckRunCreateRequest;
import com.ideas.contracts.service.model.CheckRunCreateResponse;
import com.ideas.contracts.service.model.CheckRunLogResponse;
import com.ideas.contracts.service.model.CheckRunPageResponse;
import com.ideas.contracts.service.model.CheckRunResponse;
import java.time.Instant;
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

  record NotificationEnqueueResult(NotificationDelivery delivery, boolean created) {}

  record EvidenceImportResult(CheckEvidence evidence, boolean created) {}

  List<CheckRunResponse> list(String contractId, String commitSha);

  CheckRunPageResponse listPage(CheckRunQuery query);

  Optional<CheckRunResponse> findByRunId(String runId);

  List<CheckRunLogResponse> listLogs(String runId);

  Optional<QueuedCheckRun> claimNextQueuedRun();

  boolean completeRun(String runId, String status, List<String> breakingChanges, List<String> warnings);

  boolean requeueRun(String runId);

  void appendLog(String runId, String level, String message);

  CheckRunCreateResponse createQueuedRun(CheckRunCreateRequest request);

  EvidenceImportResult importEvidence(CheckEvidence evidence);

  Optional<CheckEvidence> findEvidenceByIdempotencyKey(String idempotencyKey);

  List<CheckEvidence> listEvidence(String contractId, String importStatus, int limit);

  List<CheckEvidence> listRetentionCandidates(
      List<String> importStatuses, Instant importedBefore, int limit);

  EvidenceLegalHold placeEvidenceLegalHold(EvidenceLegalHold hold);

  boolean releaseEvidenceLegalHold(String holdId, String releasedBy, String reason);

  boolean recordArchiveAndPurgeRawEvidence(
      String evidenceId, EvidenceArchiveReceipt archive, String policyVersion, String actor);

  List<EvidenceRetentionEvent> listEvidenceRetentionEvents(String evidenceId, int limit);

  EvidenceRateLimitDecision tryAcquireEvidenceRateLimit(
      String bucketKey, String windowType, Instant windowStart, int maxRequests, Instant now);

  void recordAuditLog(AuditLogEntry entry);

  NotificationEnqueueResult enqueueNotificationDelivery(NotificationEvent event, String sinkName);

  Optional<NotificationDelivery> claimNextNotificationDelivery(Instant now, Instant staleClaimBefore);

  boolean markNotificationDeliveryDelivered(String deliveryId, Instant deliveredAt);

  boolean markNotificationDeliveryFailed(
      String deliveryId,
      String failureMessage,
      Instant nextAttemptAt,
      boolean permanentlyFailed);

  List<NotificationDelivery> listNotificationDeliveries(int limit);

  List<NotificationDelivery> listNotificationDeliveries(NotificationDeliveryQuery query);

  Optional<NotificationDelivery> findNotificationDelivery(String deliveryId);

  boolean requeueNotificationDelivery(String deliveryId, Instant nextAttemptAt);

  int backfillLegacyRuns(
      Function<String, String> modeResolver,
      String defaultTriggeredBy,
      String defaultMode);

  String configuredDbTarget();

  PoolSnapshot poolSnapshot();

  HealthSnapshot healthSnapshot();
}
