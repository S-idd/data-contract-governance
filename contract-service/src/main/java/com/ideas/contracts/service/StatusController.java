package com.ideas.contracts.service;

import java.util.Map;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Status", description = "Service health and status endpoints")
public class StatusController {
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
}
