package com.ideas.contracts.service;

import com.ideas.contracts.service.model.CheckRunResponse;
import com.ideas.contracts.service.model.ApiErrorResponse;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
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
}
