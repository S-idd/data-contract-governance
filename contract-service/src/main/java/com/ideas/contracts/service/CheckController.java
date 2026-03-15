package com.ideas.contracts.service;

import com.ideas.contracts.service.model.ApiErrorResponse;
import com.ideas.contracts.service.model.CheckRunCreateRequest;
import com.ideas.contracts.service.model.CheckRunCreateResponse;
import com.ideas.contracts.service.model.CheckRunLogResponse;
import com.ideas.contracts.service.model.CheckRunPageResponse;
import com.ideas.contracts.service.model.CheckRunResponse;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/checks")
@Tag(name = "Checks", description = "Query compatibility check history")
public class CheckController {
  private final CheckRunStore checkRunStore;

  public CheckController(CheckRunStore checkRunStore) {
    this.checkRunStore = checkRunStore;
  }

  @GetMapping
  @Operation(summary = "List checks", description = "Returns compatibility check runs filtered by optional contractId and commitSha.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "200",
          description = "Check runs fetched successfully",
          content = @Content(
              mediaType = "application/json",
              array = @ArraySchema(schema = @Schema(implementation = CheckRunResponse.class)),
              examples = @ExampleObject(value = """
                  [
                    {
                      "runId": "cccf0cac-0bff-499b-b02c-d3e024dd5a03",
                      "contractId": "orders.created",
                      "baseVersion": "v1",
                      "candidateVersion": "v2",
                      "status": "FAIL",
                      "breakingChanges": ["Field type changed: orderId (string -> integer)"],
                      "warnings": ["Enum value added: status.SHIPPED"],
                      "commitSha": "local-dev",
                      "createdAt": "2026-02-27T12:32:31.247219200Z"
                    }
                  ]
                  """))),
      @ApiResponse(
          responseCode = "503",
          description = "Check history store unavailable",
          content = @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ApiErrorResponse.class),
              examples = @ExampleObject(value = """
                  {
                    "timestamp": "2026-03-02T15:22:10.120Z",
                    "status": 503,
                    "error": "Service Unavailable",
                    "code": "CHECK_STORE_UNAVAILABLE",
                    "message": "Check history store is currently unavailable.",
                    "path": "/checks",
                    "requestId": "c91fdc87-812e-4530-8382-e3529c2d9f4c"
                  }
                  """)))
  })
  public List<CheckRunResponse> listChecks(
      @RequestParam(name = "contractId", required = false) String contractId,
      @RequestParam(name = "commitSha", required = false) String commitSha) {
    return checkRunStore.list(contractId, commitSha);
  }

  @PostMapping
  @Operation(summary = "Submit check run", description = "Queues a compatibility check run for asynchronous execution.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "202",
          description = "Check run accepted",
          content = @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = CheckRunCreateResponse.class),
              examples = @ExampleObject(value = """
                  {
                    "runId": "8b1e6af2-0f45-4b3c-a5a9-6f6f2f2a5a70",
                    "status": "QUEUED"
                  }
                  """))),
      @ApiResponse(
          responseCode = "400",
          description = "Invalid request payload",
          content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(
          responseCode = "503",
          description = "Check history store unavailable",
          content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  public ResponseEntity<CheckRunCreateResponse> createCheckRun(
      @RequestBody(
          required = true,
          description = "Check run request payload",
          content = @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = CheckRunCreateRequest.class),
              examples = @ExampleObject(value = """
                  {
                    "contractId": "orders.created",
                    "baseVersion": "v1",
                    "candidateVersion": "v2",
                    "mode": "BACKWARD",
                    "commitSha": "local-dev",
                    "triggeredBy": "ui"
                  }
                  """)))
      @org.springframework.web.bind.annotation.RequestBody CheckRunCreateRequest request) {
    CheckRunCreateResponse response = checkRunStore.createQueuedRun(request);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
  }

  @GetMapping("/page")
  @Operation(summary = "List checks page", description = "Returns a paginated checks response filtered by optional contractId, commitSha, and status.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "200",
          description = "Check run page fetched successfully",
          content = @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = CheckRunPageResponse.class))),
      @ApiResponse(
          responseCode = "400",
          description = "Invalid pagination or filter query",
          content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(
          responseCode = "503",
          description = "Check history store unavailable",
          content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  public CheckRunPageResponse listChecksPage(
      @RequestParam(name = "contractId", required = false) String contractId,
      @RequestParam(name = "commitSha", required = false) String commitSha,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "offset", required = false) Integer offset) {
    CheckRunQuery query = CheckRunQuery.from(contractId, commitSha, status, limit, offset);
    return checkRunStore.listPage(query);
  }

  @GetMapping("/{runId}")
  @Operation(summary = "Get check run", description = "Returns a single check run by runId.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "200",
          description = "Check run fetched successfully",
          content = @Content(mediaType = "application/json", schema = @Schema(implementation = CheckRunResponse.class))),
      @ApiResponse(
          responseCode = "404",
          description = "Check run not found",
          content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(
          responseCode = "503",
          description = "Check history store unavailable",
          content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  public CheckRunResponse getCheckRun(@PathVariable("runId") String runId) {
    return checkRunStore.findByRunId(runId)
        .orElseThrow(() -> new CheckRunNotFoundException(runId));
  }

  @GetMapping("/{runId}/logs")
  @Operation(summary = "Get check run logs", description = "Returns execution logs for a single check run.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "200",
          description = "Check run logs fetched successfully",
          content = @Content(
              mediaType = "application/json",
              array = @ArraySchema(schema = @Schema(implementation = CheckRunLogResponse.class)),
              examples = @ExampleObject(value = """
                  [
                    {
                      "logId": "f86e1d93-12b4-42f4-a6a9-2bf1b8df4b6e",
                      "runId": "cccf0cac-0bff-499b-b02c-d3e024dd5a03",
                      "level": "INFO",
                      "message": "Check run claimed for execution.",
                      "createdAt": "2026-03-03T09:41:14.120Z"
                    }
                  ]
                  """))),
      @ApiResponse(
          responseCode = "404",
          description = "Check run not found",
          content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(
          responseCode = "503",
          description = "Check history store unavailable",
          content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  public List<CheckRunLogResponse> getCheckRunLogs(@PathVariable("runId") String runId) {
    checkRunStore.findByRunId(runId)
        .orElseThrow(() -> new CheckRunNotFoundException(runId));
    return checkRunStore.listLogs(runId);
  }
}
