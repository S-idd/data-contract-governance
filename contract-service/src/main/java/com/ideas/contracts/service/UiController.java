package com.ideas.contracts.service;

import com.ideas.contracts.service.model.CheckRunResponse;
import com.ideas.contracts.service.model.CheckRunLogResponse;
import com.ideas.contracts.service.model.CheckRunCreateRequest;
import com.ideas.contracts.service.model.CheckRunCreateResponse;
import com.ideas.contracts.service.model.ContractDetailResponse;
import com.ideas.contracts.service.model.ContractSummaryResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
@RequestMapping("/ui")
@ConditionalOnProperty(prefix = "app.ui", name = "enabled", havingValue = "true", matchIfMissing = true)
public class UiController {
  private static final Logger LOGGER = LoggerFactory.getLogger(UiController.class);

  private final ContractCatalogService contractCatalogService;
  private final MetadataStore checkRunStore;
  private final OperationalStatusService operationalStatusService;
  private final NotificationService notificationService;

  public UiController(
      ContractCatalogService contractCatalogService,
      MetadataStore checkRunStore,
      OperationalStatusService operationalStatusService,
      NotificationService notificationService) {
    this.contractCatalogService = contractCatalogService;
    this.checkRunStore = checkRunStore;
    this.operationalStatusService = operationalStatusService;
    this.notificationService = notificationService;
  }

  @GetMapping
  public String dashboard(
      @RequestParam(name = "contractId", required = false) String contractId,
      @RequestParam(name = "commitSha", required = false) String commitSha,
      @RequestParam(name = "status", required = false) String status,
      HttpServletRequest request,
      Model model) {
    String requestId = requestId(request);
    logUiRequest("dashboard", requestId, contractId, commitSha);

    model.addAttribute("requestId", requestId);
    model.addAttribute("contractCount", contractCatalogService.listContracts().size());
    model.addAttribute("filterContractId", safe(contractId));
    model.addAttribute("filterCommitSha", safe(commitSha));
    model.addAttribute("filterStatus", safe(status));
    model.addAttribute("operationalStatus", operationalStatusService.currentStatus());
    model.addAttribute("recentFailedChecks", 0L);
    model.addAttribute("recentActiveChecks", 0L);
    addNotificationDeliverySummary(
        model,
        NotificationDeliveryQuery.from(null, null, null, null, 5));

    try {
      CheckRunQuery query = CheckRunQuery.from(contractId, commitSha, status, 20, 0);
      var page = checkRunStore.listPage(query);
      model.addAttribute("recentChecks", page.items());
      model.addAttribute("recentHasMore", page.hasMore());
      model.addAttribute("checkStoreUnavailable", false);
      model.addAttribute(
          "recentFailedChecks",
          page.items().stream().filter(item -> "FAIL".equalsIgnoreCase(item.status())).count());
      model.addAttribute(
          "recentActiveChecks",
          page.items().stream()
              .filter(item -> "QUEUED".equalsIgnoreCase(item.status())
                  || "RUNNING".equalsIgnoreCase(item.status()))
              .count());
    } catch (IllegalArgumentException ex) {
      model.addAttribute("recentChecks", List.of());
      model.addAttribute("recentHasMore", false);
      model.addAttribute("checkStoreUnavailable", false);
      model.addAttribute("uiErrorMessage", ex.getMessage());
    } catch (CheckRunStoreException ex) {
      model.addAttribute("recentChecks", List.of());
      model.addAttribute("recentHasMore", false);
      model.addAttribute("checkStoreUnavailable", true);
      model.addAttribute("uiErrorMessage", "Check history store is currently unavailable.");
    }
    return "ui/dashboard";
  }

  @GetMapping("/notifications")
  public String notificationDeliveries(
      @RequestParam(name = "limit", required = false, defaultValue = "50") int limit,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "contractId", required = false) String contractId,
      @RequestParam(name = "sink", required = false) String sink,
      @RequestParam(name = "eventType", required = false) String eventType,
      @RequestParam(name = "retryStatus", required = false) String retryStatus,
      @RequestParam(name = "retryMessage", required = false) String retryMessage,
      HttpServletRequest request,
      Model model) {
    String requestId = requestId(request);
    logUiRequest("notification_deliveries", requestId, status, contractId);

    model.addAttribute("requestId", requestId);
    model.addAttribute("operationalStatus", operationalStatusService.currentStatus());
    model.addAttribute("notificationRetryStatus", safe(retryStatus));
    model.addAttribute("notificationRetryMessage", safe(retryMessage));
    try {
      addNotificationDeliverySummary(
          model,
          NotificationDeliveryQuery.from(status, contractId, sink, eventType, limit));
    } catch (IllegalArgumentException ex) {
      addNotificationDeliveryErrorSummary(model, limit, status, contractId, sink, eventType, ex.getMessage());
    }
    return "ui/notifications";
  }

  @PostMapping("/notifications/{deliveryId}/retry")
  public String retryNotificationDelivery(
      @PathVariable("deliveryId") String deliveryId,
      @RequestParam(name = "limit", required = false, defaultValue = "50") int limit,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "contractId", required = false) String contractId,
      @RequestParam(name = "sink", required = false) String sink,
      @RequestParam(name = "eventType", required = false) String eventType,
      HttpServletRequest request) {
    String requestId = requestId(request);
    logUiRequest("notification_delivery_retry", requestId, deliveryId, null);

    String retryStatus = "success";
    String retryMessage = "Notification delivery was queued for retry.";
    try {
      if (notificationService.retryDelivery(deliveryId).isEmpty()) {
        retryStatus = "error";
        retryMessage = "Notification delivery was not found.";
      }
    } catch (IllegalArgumentException ex) {
      retryStatus = "error";
      retryMessage = ex.getMessage();
    }
    return notificationRedirect(limit, status, contractId, sink, eventType, retryStatus, retryMessage);
  }

  @GetMapping("/contracts")
  public String contracts(
      @RequestParam(name = "q", required = false) String search,
      HttpServletRequest request,
      Model model) {
    String requestId = requestId(request);
    logUiRequest("contracts", requestId, search, null);

    List<ContractSummaryResponse> contracts = contractCatalogService.listContracts();
    String query = safe(search).toLowerCase(Locale.ROOT);
    if (!query.isBlank()) {
      contracts = contracts.stream()
          .filter(item -> containsIgnoreCase(item.contractId(), query)
              || containsIgnoreCase(item.ownerTeam(), query)
              || containsIgnoreCase(item.domain(), query))
          .toList();
    }

    model.addAttribute("requestId", requestId);
    model.addAttribute("search", safe(search));
    model.addAttribute("contracts", contracts);
    return "ui/contracts";
  }

  @GetMapping("/contracts/{contractId}")
  public String contractDetail(
      @PathVariable("contractId") String contractId,
      HttpServletRequest request,
      Model model) {
    String requestId = requestId(request);
    logUiRequest("contract_detail", requestId, contractId, null);

    ContractDetailResponse detail = contractCatalogService.getContract(contractId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contract not found: " + contractId));

    return renderContractDetail(
        detail,
        requestId,
        model,
        null,
        null,
        null,
        null);
  }

  @PostMapping("/contracts/{contractId}/checks")
  public String runCheck(
      @PathVariable("contractId") String contractId,
      @RequestParam("baseVersion") String baseVersion,
      @RequestParam("candidateVersion") String candidateVersion,
      @RequestParam(name = "commitSha", required = false) String commitSha,
      HttpServletRequest request,
      Model model) {
    String requestId = requestId(request);
    logUiRequest("contract_check_run", requestId, contractId, baseVersion + "->" + candidateVersion);

    ContractDetailResponse detail = contractCatalogService.getContract(contractId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contract not found: " + contractId));

    CheckRunCreateRequest payload;
    try {
      payload = new CheckRunCreateRequest(
          contractId,
          baseVersion,
          candidateVersion,
          detail.compatibilityMode(),
          commitSha,
          "ui");
    } catch (IllegalArgumentException ex) {
      checkRunStore.recordAuditLog(
          AuditLogSupport.checkRunCreateFailure(request, null, ex));
      return renderContractDetail(
          detail,
          requestId,
          model,
          ex.getMessage(),
          baseVersion,
          candidateVersion,
          commitSha);
    }

    try {
      CheckRunCreateResponse response = checkRunStore.createQueuedRun(payload);
      checkRunStore.recordAuditLog(
          AuditLogSupport.checkRunCreateSuccess(request, payload, response));
      return "redirect:/ui/checks/" + response.runId();
    } catch (CheckRunStoreException ex) {
      checkRunStore.recordAuditLog(
          AuditLogSupport.checkRunCreateFailure(request, payload, ex));
      return renderContractDetail(
          detail,
          requestId,
          model,
          "Check history store is currently unavailable.",
          baseVersion,
          candidateVersion,
          commitSha);
    }
  }

  @GetMapping("/checks/{runId}")
  public String checkDetail(
      @PathVariable("runId") String runId,
      HttpServletRequest request,
      HttpServletResponse response,
      Model model) {
    String requestId = requestId(request);
    logUiRequest("check_detail", requestId, runId, null);
    model.addAttribute("requestId", requestId);

    try {
      CheckRunResponse checkRun = checkRunStore.findByRunId(runId)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Check run not found: " + runId));
      List<CheckRunLogResponse> checkLogs = checkRunStore.listLogs(runId);
      model.addAttribute("checkRun", checkRun);
      model.addAttribute("guidance", buildGuidance(checkRun));
      model.addAttribute("curlSnippet", "curl \"http://localhost:8080/checks/" + checkRun.runId() + "\"");
      model.addAttribute("cliSnippet", buildCliSnippet(checkRun));
      model.addAttribute("checkLogs", checkLogs);
      model.addAttribute("checkStoreUnavailable", false);
      model.addAttribute("uiErrorMessage", "");
    } catch (CheckRunStoreException ex) {
      response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
      model.addAttribute("checkRun", null);
      model.addAttribute("guidance", List.of());
      model.addAttribute("curlSnippet", "");
      model.addAttribute("cliSnippet", "");
      model.addAttribute("checkLogs", List.of());
      model.addAttribute("checkStoreUnavailable", true);
      model.addAttribute("uiErrorMessage", "Check history store is currently unavailable.");
    }
    return "ui/check-detail";
  }

  private String renderContractDetail(
      ContractDetailResponse detail,
      String requestId,
      Model model,
      String runCheckError,
      String selectedBaseVersion,
      String selectedCandidateVersion,
      String commitSha) {
    model.addAttribute("requestId", requestId);
    model.addAttribute("contract", detail);

    List<String> versions = detail.versions() == null ? List.of() : detail.versions();
    boolean canRunCheck = versions.size() >= 2;
    String defaultCandidate = resolveCandidateVersion(versions, selectedCandidateVersion);
    String defaultBase = resolveBaseVersion(versions, selectedBaseVersion, defaultCandidate);

    model.addAttribute("canRunCheck", canRunCheck);
    model.addAttribute("runCheckBase", defaultBase);
    model.addAttribute("runCheckCandidate", defaultCandidate);
    model.addAttribute("runCheckCommitSha", safe(commitSha));
    model.addAttribute("runCheckError", safe(runCheckError));
    model.addAttribute(
        "runCheckDisabledReason",
        canRunCheck ? "" : "At least two versions are required to run a compatibility check.");

    model.addAttribute("checks", List.of());
    model.addAttribute("checkStoreUnavailable", false);
    model.addAttribute("uiErrorMessage", "");
    try {
      var page = checkRunStore.listPage(CheckRunQuery.from(detail.contractId(), null, null, 20, 0));
      model.addAttribute("checks", page.items());
      model.addAttribute("checksHasMore", page.hasMore());
    } catch (CheckRunStoreException ex) {
      model.addAttribute("checkStoreUnavailable", true);
      model.addAttribute("uiErrorMessage", "Check history store is currently unavailable.");
      model.addAttribute("checksHasMore", false);
    }
    return "ui/contract-detail";
  }

  private String resolveCandidateVersion(List<String> versions, String selectedCandidate) {
    if (selectedCandidate != null && !selectedCandidate.isBlank()) {
      return selectedCandidate.trim();
    }
    if (versions.isEmpty()) {
      return "";
    }
    return versions.get(versions.size() - 1);
  }

  private String resolveBaseVersion(List<String> versions, String selectedBase, String candidateVersion) {
    if (selectedBase != null && !selectedBase.isBlank()) {
      return selectedBase.trim();
    }
    if (versions.size() < 2) {
      return "";
    }
    int candidateIndex = versions.indexOf(candidateVersion);
    if (candidateIndex > 0) {
      return versions.get(candidateIndex - 1);
    }
    return versions.get(versions.size() - 2);
  }

  private String buildCliSnippet(CheckRunResponse checkRun) {
    return "java -jar contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar check-compat"
        + " --base contracts/" + checkRun.contractId() + "/" + checkRun.baseVersion() + ".json"
        + " --candidate contracts/" + checkRun.contractId() + "/" + checkRun.candidateVersion() + ".json"
        + " --mode BACKWARD"
        + " --contract-id " + checkRun.contractId()
        + " --commit-sha " + safe(checkRun.commitSha());
  }

  private void addNotificationDeliverySummary(Model model, NotificationDeliveryQuery query) {
    addNotificationFilterAttributes(model, query.limit(), query.status(), query.contractId(), query.sinkName(),
        query.eventType());
    model.addAttribute("notificationDeliveryUnavailable", false);
    model.addAttribute("notificationDeliveryError", "");

    try {
      List<NotificationDelivery> deliveries = notificationService.recentDeliveries(query);
      model.addAttribute("notificationDeliveries", deliveries);
      model.addAttribute("notificationTotalShown", deliveries.size());
      model.addAttribute(
          "notificationFailedCount",
          deliveries.stream()
              .filter(delivery -> delivery.status() == NotificationDeliveryStatus.FAILED_PERMANENT
                  || delivery.status() == NotificationDeliveryStatus.FAILED_RETRYABLE)
              .count());
      model.addAttribute(
          "notificationPendingCount",
          deliveries.stream()
              .filter(delivery -> delivery.status() == NotificationDeliveryStatus.PENDING
                  || delivery.status() == NotificationDeliveryStatus.IN_FLIGHT)
              .count());
      model.addAttribute(
          "notificationDeliveredCount",
          deliveries.stream()
              .filter(delivery -> delivery.status() == NotificationDeliveryStatus.DELIVERED)
              .count());
    } catch (CheckRunStoreException ex) {
      model.addAttribute("notificationDeliveries", List.of());
      model.addAttribute("notificationTotalShown", 0);
      model.addAttribute("notificationFailedCount", 0L);
      model.addAttribute("notificationPendingCount", 0L);
      model.addAttribute("notificationDeliveredCount", 0L);
      model.addAttribute("notificationDeliveryUnavailable", true);
      model.addAttribute(
          "notificationDeliveryError",
          "Notification delivery history is currently unavailable.");
    }
  }

  private void addNotificationDeliveryErrorSummary(
      Model model,
      int limit,
      String status,
      String contractId,
      String sink,
      String eventType,
      String errorMessage) {
    addNotificationFilterAttributes(
        model,
        Math.max(1, Math.min(100, limit)),
        safe(status),
        safe(contractId),
        safe(sink),
        safe(eventType));
    model.addAttribute("notificationDeliveries", List.of());
    model.addAttribute("notificationTotalShown", 0);
    model.addAttribute("notificationFailedCount", 0L);
    model.addAttribute("notificationPendingCount", 0L);
    model.addAttribute("notificationDeliveredCount", 0L);
    model.addAttribute("notificationDeliveryUnavailable", false);
    model.addAttribute("notificationDeliveryError", safe(errorMessage));
  }

  private void addNotificationFilterAttributes(
      Model model,
      int limit,
      String status,
      String contractId,
      String sink,
      String eventType) {
    model.addAttribute("notificationDeliveryLimit", limit);
    model.addAttribute("notificationFilterStatus", safe(status));
    model.addAttribute("notificationFilterContractId", safe(contractId));
    model.addAttribute("notificationFilterSink", safe(sink));
    model.addAttribute("notificationFilterEventType", safe(eventType));
  }

  private String notificationRedirect(
      int limit,
      String status,
      String contractId,
      String sink,
      String eventType,
      String retryStatus,
      String retryMessage) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/ui/notifications")
        .queryParam("limit", Math.max(1, Math.min(100, limit)));
    addQueryParamIfPresent(builder, "status", status);
    addQueryParamIfPresent(builder, "contractId", contractId);
    addQueryParamIfPresent(builder, "sink", sink);
    addQueryParamIfPresent(builder, "eventType", eventType);
    addQueryParamIfPresent(builder, "retryStatus", retryStatus);
    addQueryParamIfPresent(builder, "retryMessage", retryMessage);
    return "redirect:" + builder.build().encode().toUriString();
  }

  private void addQueryParamIfPresent(UriComponentsBuilder builder, String name, String value) {
    String normalized = safe(value);
    if (!normalized.isBlank()) {
      builder.queryParam(name, normalized);
    }
  }

  private List<String> buildGuidance(CheckRunResponse checkRun) {
    if (checkRun == null) {
      return List.of();
    }
    List<String> guidance = new ArrayList<>();
    if ("FAIL".equalsIgnoreCase(safe(checkRun.status()))) {
      guidance.add("Treat this as blocking for consumers until compatibility issues are resolved.");
    }
    for (String change : checkRun.breakingChanges()) {
      String normalized = change.toLowerCase(Locale.ROOT);
      if (normalized.contains("type changed")) {
        guidance.add("Consider a new schema version or add a compatible transitional field type.");
      } else if (normalized.contains("field removed")) {
        guidance.add("Avoid removing fields directly. Deprecate first and remove in a later major version.");
      } else if (normalized.contains("required field")) {
        guidance.add("Prefer optional fields with defaults for backwards compatibility.");
      }
    }
    if (guidance.isEmpty()) {
      guidance.add("No additional guidance was generated for this check run.");
    }
    return guidance.stream().distinct().sorted(Comparator.naturalOrder()).toList();
  }

  private String requestId(HttpServletRequest request) {
    Object attributeValue = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    if (attributeValue instanceof String id && !id.isBlank()) {
      return id;
    }
    return "-";
  }

  private String safe(String value) {
    return value == null ? "" : value.trim();
  }

  private boolean containsIgnoreCase(String value, String query) {
    if (value == null || value.isBlank() || query == null || query.isBlank()) {
      return false;
    }
    return value.toLowerCase(Locale.ROOT).contains(query);
  }

  private void logUiRequest(String route, String requestId, String arg1, String arg2) {
    LOGGER.info(
        "event=ui_route_access component=ui_controller route={} request_id={} arg1={} arg2={}",
        route,
        requestId,
        safe(arg1).isBlank() ? "-" : safe(arg1),
        safe(arg2).isBlank() ? "-" : safe(arg2));
  }
}
