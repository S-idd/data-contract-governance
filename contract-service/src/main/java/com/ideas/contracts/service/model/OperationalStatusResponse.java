package com.ideas.contracts.service.model;

import java.util.List;

public record OperationalStatusResponse(
    String overallStatus,
    List<ComponentStatus> components) {

  public OperationalStatusResponse {
    overallStatus = overallStatus == null || overallStatus.isBlank() ? "UNKNOWN" : overallStatus.trim();
    components = components == null ? List.of() : List.copyOf(components);
  }

  public record ComponentStatus(
      String id,
      String label,
      String status,
      String detail,
      String action) {
    public ComponentStatus {
      id = id == null || id.isBlank() ? "unknown" : id.trim();
      label = label == null || label.isBlank() ? "Unknown component" : label.trim();
      status = status == null || status.isBlank() ? "UNKNOWN" : status.trim();
      detail = detail == null || detail.isBlank() ? "No detail available." : detail.trim();
      action = action == null || action.isBlank() ? "No action available." : action.trim();
    }
  }
}
