package com.ideas.contracts.service;

import com.ideas.contracts.service.model.ContractDetailResponse;
import com.ideas.contracts.service.model.ContractSummaryResponse;
import com.ideas.contracts.service.model.ContractVersionResponse;
import com.ideas.contracts.service.model.CreateContractRequest;
import com.ideas.contracts.service.model.CreateContractVersionRequest;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/contracts")
@Tag(name = "Contracts", description = "Read contract metadata and schema versions")
public class ContractController {
  private final ContractCatalogService catalogService;
  private final ContractWriteService contractWriteService;
  private final CheckRunRepository checkRunStore;

  public ContractController(
      ContractCatalogService catalogService,
      ContractWriteService contractWriteService,
      CheckRunRepository checkRunStore) {
    this.catalogService = catalogService;
    this.contractWriteService = contractWriteService;
    this.checkRunStore = checkRunStore;
  }

  @GetMapping
  @Operation(summary = "List contracts", description = "Returns all discovered contracts with summary metadata.")
  @ApiResponse(
      responseCode = "200",
      description = "Contracts fetched successfully",
      content = @Content(
          mediaType = "application/json",
          array = @ArraySchema(schema = @Schema(implementation = ContractSummaryResponse.class)),
          examples = @ExampleObject(value = """
              [
                {
                  "contractId": "orders.created",
                  "ownerTeam": "platform",
                  "domain": "commerce",
                  "compatibilityMode": "BACKWARD",
                  "policyPack": "baseline",
                  "latestVersion": "v2",
                  "status": "ACTIVE"
                }
              ]
              """)))
  public List<ContractSummaryResponse> listContracts() {
    return catalogService.listContracts();
  }

  @GetMapping("/{contractId}")
  @Operation(summary = "Get contract detail", description = "Returns metadata and version list for a single contract.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "200",
          description = "Contract found",
          content = @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ContractDetailResponse.class),
              examples = @ExampleObject(value = """
                  {
                    "contractId": "orders.created",
                    "ownerTeam": "platform",
                    "domain": "commerce",
                    "compatibilityMode": "BACKWARD",
                    "policyPack": "baseline",
                    "versions": ["v1", "v2"],
                    "status": "ACTIVE"
                  }
                  """))),
      @ApiResponse(
          responseCode = "404",
          description = "Contract not found",
          content = @Content(
              mediaType = "application/json",
              examples = @ExampleObject(value = """
                  {
                    "timestamp": "2026-02-27T18:00:00.000+05:30",
                    "status": 404,
                    "error": "Not Found",
                    "path": "/contracts/unknown.contract"
                  }
                  """)))
  })
  public ContractDetailResponse getContract(@PathVariable("contractId") String contractId) {
    return catalogService.getContract(contractId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contract not found: " + contractId));
  }

  @GetMapping("/{contractId}/versions")
  @Operation(summary = "List contract versions", description = "Returns ordered versions for a contract, e.g. v1, v2.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "200",
          description = "Versions fetched successfully",
          content = @Content(
              mediaType = "application/json",
              array = @ArraySchema(schema = @Schema(implementation = String.class)),
              examples = @ExampleObject(value = """
                  ["v1", "v2"]
                  """))),
      @ApiResponse(responseCode = "404", description = "Contract not found")
  })
  public List<String> getVersions(@PathVariable("contractId") String contractId) {
    List<String> versions = catalogService.getContractVersions(contractId);
    if (versions.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Contract not found: " + contractId);
    }
    return versions;
  }

  @GetMapping("/{contractId}/versions/{version}")
  @Operation(summary = "Get contract schema version", description = "Returns the schema payload for a specific contract version.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "200",
          description = "Schema version fetched successfully",
          content = @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ContractVersionResponse.class),
              examples = @ExampleObject(value = """
                  {
                    "contractId": "orders.created",
                    "version": "v1",
                    "schema": {
                      "type": "object",
                      "properties": {
                        "orderId": {"type": "string"}
                      }
                    }
                  }
                  """))),
      @ApiResponse(responseCode = "400", description = "Invalid version format"),
      @ApiResponse(responseCode = "404", description = "Version not found")
  })
  public ContractVersionResponse getVersion(
      @PathVariable("contractId") String contractId,
      @PathVariable("version") String version) {
    try {
      return catalogService.getContractVersion(contractId, version)
          .orElseThrow(() -> new ResponseStatusException(
              HttpStatus.NOT_FOUND,
              "Version not found: " + contractId + "/" + version));
    } catch (IllegalStateException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  @PostMapping
  @Operation(summary = "Create contract", description = "Creates a new contract with metadata and initial schema version.")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Contract created successfully"),
      @ApiResponse(responseCode = "400", description = "Invalid request payload"),
      @ApiResponse(responseCode = "409", description = "Contract already exists")
  })
  public ResponseEntity<ContractDetailResponse> createContract(
      @org.springframework.web.bind.annotation.RequestBody CreateContractRequest request,
      HttpServletRequest httpRequest) {
    try {
      ContractDetailResponse response = contractWriteService.createContract(request);
      checkRunStore.recordAuditLog(
          AuditLogSupport.contractCreateSuccess(httpRequest, request, response.contractId()));
      return ResponseEntity.status(HttpStatus.CREATED).body(response);
    } catch (RuntimeException ex) {
      checkRunStore.recordAuditLog(AuditLogSupport.contractCreateFailure(httpRequest, request, ex));
      throw ex;
    }
  }

  @PostMapping("/{contractId}/versions")
  @Operation(summary = "Create contract version", description = "Adds a new versioned schema to an existing contract.")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Contract version created successfully"),
      @ApiResponse(responseCode = "400", description = "Invalid request payload"),
      @ApiResponse(responseCode = "404", description = "Contract not found"),
      @ApiResponse(responseCode = "422", description = "Compatibility check failed in strict mode")
  })
  public ResponseEntity<ContractVersionResponse> createContractVersion(
      @PathVariable("contractId") String contractId,
      @org.springframework.web.bind.annotation.RequestBody CreateContractVersionRequest request,
      HttpServletRequest httpRequest) {
    try {
      ContractVersionResponse response = contractWriteService.createVersion(contractId, request);
      checkRunStore.recordAuditLog(
          AuditLogSupport.contractVersionCreateSuccess(httpRequest, contractId, request, response.version()));
      return ResponseEntity.status(HttpStatus.CREATED).body(response);
    } catch (RuntimeException ex) {
      checkRunStore.recordAuditLog(
          AuditLogSupport.contractVersionCreateFailure(httpRequest, contractId, request, ex));
      throw ex;
    }
  }

  @GetMapping("/{contractId}/badge")
  @Operation(summary = "Get contract badge", description = "Returns Shields-compatible badge payload for contract status.")
  public Map<String, Object> badge(@PathVariable("contractId") String contractId) {
    catalogService.getContract(contractId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contract not found: " + contractId));
    List<com.ideas.contracts.service.model.CheckRunResponse> runs = checkRunStore.list(contractId, null);
    if (runs.isEmpty()) {
      return Map.of("schemaVersion", 1, "label", contractId, "message", "unknown", "color", "lightgrey");
    }
    String latest = runs.get(0).status();
    if ("FAIL".equalsIgnoreCase(latest) || "FAILED".equalsIgnoreCase(latest)) {
      return Map.of("schemaVersion", 1, "label", contractId, "message", "failing", "color", "red");
    }
    if ("QUEUED".equalsIgnoreCase(latest) || "RUNNING".equalsIgnoreCase(latest)) {
      return Map.of("schemaVersion", 1, "label", contractId, "message", "running", "color", "yellow");
    }
    return Map.of("schemaVersion", 1, "label", contractId, "message", "passing", "color", "brightgreen");
  }
}
