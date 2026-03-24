package com.ideas.contracts.service;

import com.ideas.contracts.service.model.CheckRunCreateRequest;
import com.ideas.contracts.service.model.CheckRunCreateResponse;
import com.ideas.contracts.service.model.CreateContractRequest;
import com.ideas.contracts.service.model.CreateContractVersionRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

final class AuditLogSupport {
  private AuditLogSupport() {}

  static AuditLogEntry checkRunCreateSuccess(
      HttpServletRequest request,
      CheckRunCreateRequest payload,
      CheckRunCreateResponse response) {
    return buildCheckRunCreateEntry(request, payload, response.runId(), "SUCCESS", null);
  }

  static AuditLogEntry checkRunCreateFailure(
      HttpServletRequest request,
      CheckRunCreateRequest payload,
      Exception error) {
    String errorMessage = error == null ? null : error.getMessage();
    return buildCheckRunCreateEntry(request, payload, null, "FAILURE", errorMessage);
  }

  private static AuditLogEntry buildCheckRunCreateEntry(
      HttpServletRequest request,
      CheckRunCreateRequest payload,
      String runId,
      String status,
      String errorMessage) {
    Map<String, Object> detail = new LinkedHashMap<>();
    if (payload != null) {
      detail.put("contractId", payload.contractId());
      detail.put("baseVersion", payload.baseVersion());
      detail.put("candidateVersion", payload.candidateVersion());
      detail.put("mode", payload.mode());
      detail.put("commitSha", payload.commitSha());
      detail.put("triggeredBy", payload.triggeredBy());
    }
    if (errorMessage != null && !errorMessage.isBlank()) {
      detail.put("error", errorMessage);
    }

    return new AuditLogEntry(
        "CHECK_RUN_CREATE",
        safeValue(status, "UNKNOWN"),
        resolveActor(),
        resolveRoles(),
        payload == null ? "-" : safeValue(payload.triggeredBy(), "-"),
        resolveRequestId(request),
        request == null ? "-" : safeValue(request.getMethod(), "-"),
        request == null ? "-" : safeValue(request.getRequestURI(), "-"),
        "check_run",
        runId,
        detail.isEmpty() ? null : detail);
  }

  static AuditLogEntry contractCreateSuccess(
      HttpServletRequest request,
      CreateContractRequest payload,
      String contractId) {
    return buildContractCreateEntry(request, payload, contractId, "SUCCESS", null);
  }

  static AuditLogEntry contractCreateFailure(
      HttpServletRequest request,
      CreateContractRequest payload,
      Exception error) {
    String message = error == null ? null : error.getMessage();
    return buildContractCreateEntry(request, payload, null, "FAILURE", message);
  }

  private static AuditLogEntry buildContractCreateEntry(
      HttpServletRequest request,
      CreateContractRequest payload,
      String contractId,
      String status,
      String errorMessage) {
    Map<String, Object> detail = new LinkedHashMap<>();
    if (payload != null) {
      detail.put("contractId", payload.contractId());
      detail.put("ownerTeam", payload.ownerTeam());
      detail.put("domain", payload.domain());
      detail.put("compatibilityMode", payload.compatibilityMode());
      detail.put("policyPack", payload.policyPack());
      detail.put("initialVersion", payload.initialVersion());
    }
    if (errorMessage != null && !errorMessage.isBlank()) {
      detail.put("error", errorMessage);
    }
    return new AuditLogEntry(
        "CONTRACT_CREATE",
        safeValue(status, "UNKNOWN"),
        resolveActor(),
        resolveRoles(),
        "api",
        resolveRequestId(request),
        request == null ? "-" : safeValue(request.getMethod(), "-"),
        request == null ? "-" : safeValue(request.getRequestURI(), "-"),
        "contract",
        contractId,
        detail.isEmpty() ? null : detail);
  }

  static AuditLogEntry contractVersionCreateSuccess(
      HttpServletRequest request,
      String contractId,
      CreateContractVersionRequest payload,
      String version) {
    return buildContractVersionCreateEntry(request, contractId, payload, version, "SUCCESS", null);
  }

  static AuditLogEntry contractVersionCreateFailure(
      HttpServletRequest request,
      String contractId,
      CreateContractVersionRequest payload,
      Exception error) {
    String message = error == null ? null : error.getMessage();
    return buildContractVersionCreateEntry(request, contractId, payload, null, "FAILURE", message);
  }

  private static AuditLogEntry buildContractVersionCreateEntry(
      HttpServletRequest request,
      String contractId,
      CreateContractVersionRequest payload,
      String version,
      String status,
      String errorMessage) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("contractId", contractId);
    if (payload != null) {
      detail.put("version", payload.version());
    }
    if (version != null && !version.isBlank()) {
      detail.put("createdVersion", version);
    }
    if (errorMessage != null && !errorMessage.isBlank()) {
      detail.put("error", errorMessage);
    }
    return new AuditLogEntry(
        "CONTRACT_VERSION_CREATE",
        safeValue(status, "UNKNOWN"),
        resolveActor(),
        resolveRoles(),
        "api",
        resolveRequestId(request),
        request == null ? "-" : safeValue(request.getMethod(), "-"),
        request == null ? "-" : safeValue(request.getRequestURI(), "-"),
        "contract_version",
        contractId,
        detail);
  }

  static String resolveRequestId(HttpServletRequest request) {
    if (request == null) {
      return "-";
    }
    Object attributeValue = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    if (attributeValue instanceof String requestId && !requestId.isBlank()) {
      return requestId;
    }
    String headerValue = request.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
    return safeValue(headerValue, "-");
  }

  static String resolveActor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return "anonymous";
    }
    String name = authentication.getName();
    return safeValue(name, "anonymous");
  }

  static String resolveRoles() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || authentication.getAuthorities() == null) {
      return "-";
    }
    Set<String> roles = new TreeSet<>();
    for (GrantedAuthority authority : authentication.getAuthorities()) {
      if (authority == null || authority.getAuthority() == null) {
        continue;
      }
      String value = authority.getAuthority().trim();
      if (value.startsWith("ROLE_")) {
        value = value.substring("ROLE_".length());
      }
      if (!value.isBlank()) {
        roles.add(value);
      }
    }
    if (roles.isEmpty()) {
      return "-";
    }
    return String.join(",", roles);
  }

  private static String safeValue(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.trim();
  }
}
