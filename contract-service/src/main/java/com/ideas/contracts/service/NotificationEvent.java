package com.ideas.contracts.service;

import com.ideas.contracts.core.CompatibilityException;
import com.ideas.contracts.core.CompatibilityResult;
import com.ideas.contracts.service.model.ContractDetailResponse;
import com.ideas.contracts.service.model.ContractVersionResponse;
import com.ideas.contracts.service.model.CreateContractVersionRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record NotificationEvent(
    String eventId,
    NotificationEventType eventType,
    NotificationSeverity severity,
    Instant occurredAt,
    String contractId,
    String runId,
    String baseVersion,
    String candidateVersion,
    String commitSha,
    String triggeredBy,
    String policyPack,
    String summary,
    List<String> breakingChanges,
    List<String> warnings,
    Map<String, String> links,
    String dedupeKey
) {
  public NotificationEvent {
    eventId = normalize(eventId, UUID.randomUUID().toString());
    occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    contractId = normalize(contractId, null);
    runId = normalize(runId, null);
    baseVersion = normalize(baseVersion, null);
    candidateVersion = normalize(candidateVersion, null);
    commitSha = normalize(commitSha, null);
    triggeredBy = normalize(triggeredBy, null);
    policyPack = normalize(policyPack, null);
    summary = normalize(summary, "");
    breakingChanges = breakingChanges == null ? List.of() : List.copyOf(breakingChanges);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
    links = links == null ? Map.of() : Map.copyOf(links);
    dedupeKey = normalize(dedupeKey, eventType == null ? eventId : eventType + ":" + eventId);
  }

  public static NotificationEvent checkFailed(
      MetadataStore.QueuedCheckRun run,
      ContractDetailResponse contract,
      CompatibilityResult result) {
    String summary = "Compatibility check failed for "
        + run.contractId()
        + " "
        + run.baseVersion()
        + " -> "
        + run.candidateVersion()
        + ".";
    return new NotificationEvent(
        null,
        NotificationEventType.CONTRACT_CHECK_FAILED,
        NotificationSeverity.HIGH,
        null,
        run.contractId(),
        run.runId(),
        run.baseVersion(),
        run.candidateVersion(),
        run.commitSha(),
        run.triggeredBy(),
        contract.policyPack(),
        summary,
        result.breakingChanges(),
        result.warnings(),
        Map.of("checkRun", "/checks/" + run.runId()),
        dedupe(
            NotificationEventType.CONTRACT_CHECK_FAILED.name(),
            run.contractId(),
            run.commitSha(),
            run.baseVersion(),
            run.candidateVersion()));
  }

  public static NotificationEvent contractVersionRejected(
      String contractId,
      CreateContractVersionRequest request,
      RuntimeException exception) {
    String version = request == null ? null : request.version();
    String summary = exception instanceof CompatibilityException
        ? "Contract version was rejected by compatibility enforcement."
        : "Contract version was rejected.";
    return new NotificationEvent(
        null,
        NotificationEventType.CONTRACT_VERSION_REJECTED,
        NotificationSeverity.HIGH,
        null,
        contractId,
        null,
        null,
        version,
        null,
        null,
        null,
        summary,
        List.of(safeMessage(exception)),
        List.of(),
        Map.of("contract", "/contracts/" + normalize(contractId, "unknown")),
        dedupe(NotificationEventType.CONTRACT_VERSION_REJECTED.name(), contractId, version));
  }

  public static NotificationEvent contractRegistered(ContractDetailResponse response) {
    return new NotificationEvent(
        null,
        NotificationEventType.CONTRACT_REGISTERED,
        NotificationSeverity.INFO,
        null,
        response.contractId(),
        null,
        null,
        firstVersion(response),
        null,
        null,
        response.policyPack(),
        "Contract was registered.",
        List.of(),
        List.of(),
        Map.of("contract", "/contracts/" + response.contractId()),
        dedupe(NotificationEventType.CONTRACT_REGISTERED.name(), response.contractId()));
  }

  public static NotificationEvent schemaVersionPublished(ContractVersionResponse response) {
    return new NotificationEvent(
        null,
        NotificationEventType.SCHEMA_VERSION_PUBLISHED,
        NotificationSeverity.INFO,
        null,
        response.contractId(),
        null,
        null,
        response.version(),
        null,
        null,
        null,
        "Schema version was published.",
        List.of(),
        List.of(),
        Map.of("contractVersion", "/contracts/" + response.contractId() + "/versions/" + response.version()),
        dedupe(NotificationEventType.SCHEMA_VERSION_PUBLISHED.name(), response.contractId(), response.version()));
  }

  public static NotificationEvent policyPackResolutionFailed(PolicyPackResolutionException exception) {
    String contractId = exception == null ? null : exception.contractId();
    String runId = exception == null ? null : exception.runId();
    String policyPack = exception == null ? null : exception.policyPack();
    RuntimeException cause = exception == null || !(exception.getCause() instanceof RuntimeException runtimeException)
        ? exception
        : runtimeException;
    String summary = "Policy pack resolution failed.";
    return new NotificationEvent(
        null,
        NotificationEventType.POLICY_PACK_RESOLUTION_FAILED,
        NotificationSeverity.HIGH,
        null,
        contractId,
        runId,
        null,
        null,
        null,
        null,
        policyPack,
        summary,
        List.of(),
        List.of(safeMessage(cause)),
        runId == null
            ? Map.of("contract", "/contracts/" + normalize(contractId, "unknown"))
            : Map.of("checkRun", "/checks/" + runId),
        dedupe(NotificationEventType.POLICY_PACK_RESOLUTION_FAILED.name(), contractId, policyPack, runId));
  }

  public static NotificationEvent policyPackConfigInvalid(RuntimeException exception) {
    return new NotificationEvent(
        null,
        NotificationEventType.POLICY_PACK_CONFIG_INVALID,
        NotificationSeverity.HIGH,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "Policy pack configuration is invalid.",
        List.of(),
        List.of(safeMessage(exception)),
        Map.of(),
        dedupe(NotificationEventType.POLICY_PACK_CONFIG_INVALID.name(), safeMessage(exception)));
  }

  private static String firstVersion(ContractDetailResponse response) {
    if (response.versions() == null || response.versions().isEmpty()) {
      return null;
    }
    return response.versions().get(0);
  }

  private static String dedupe(String... parts) {
    return String.join(":", java.util.Arrays.stream(parts)
        .map(part -> normalize(part, "-"))
        .toList());
  }

  private static String safeMessage(RuntimeException exception) {
    if (exception == null || exception.getMessage() == null || exception.getMessage().isBlank()) {
      return "Rejected without a detailed message.";
    }
    return exception.getMessage();
  }

  private static String normalize(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.trim();
  }
}
