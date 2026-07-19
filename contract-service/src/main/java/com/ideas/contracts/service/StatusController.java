package com.ideas.contracts.service;

import com.ideas.contracts.service.model.OperationalStatusResponse;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Status", description = "Service health and status endpoints")
public class StatusController {
  private final OperationalStatusService operationalStatusService;
  private final NotificationService notificationService;

  public StatusController(
      OperationalStatusService operationalStatusService,
      NotificationService notificationService) {
    this.operationalStatusService = operationalStatusService;
    this.notificationService = notificationService;
  }

  @GetMapping("/status")
  @Operation(summary = "Service status", description = "Returns a basic service availability response.")
  @ApiResponse(
      responseCode = "200",
      description = "Service is up",
      content = @Content(
          mediaType = "application/json",
          examples = @ExampleObject(value = """
              {
                "status": "ok",
                "service": "contract-service"
              }
              """)))
  public Map<String, String> status() {
    return Map.of("status", "ok", "service", "contract-service");
  }

  @GetMapping("/operational-status")
  @Operation(
      summary = "Operational status",
      description = "Returns safe readiness details for metadata, artifact, notification, and security components.")
  @ApiResponse(responseCode = "200", description = "Operational component status")
  public OperationalStatusResponse operationalStatus() {
    return operationalStatusService.currentStatus();
  }

  @GetMapping("/notification-deliveries")
  @Operation(
      summary = "Recent notification deliveries",
      description = "Returns authenticated, redacted delivery history for operational diagnosis.")
  @ApiResponse(responseCode = "200", description = "Recent notification deliveries")
  public List<NotificationDelivery> notificationDeliveries(
      @RequestParam(name = "limit", required = false, defaultValue = "20") int limit) {
    return notificationService.recentDeliveries(limit);
  }
}
