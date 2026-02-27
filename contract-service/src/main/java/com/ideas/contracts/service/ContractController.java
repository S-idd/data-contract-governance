package com.ideas.contracts.service;

import com.ideas.contracts.service.model.ContractDetailResponse;
import com.ideas.contracts.service.model.ContractSummaryResponse;
import com.ideas.contracts.service.model.ContractVersionResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/contracts")
public class ContractController {
  private final ContractCatalogService catalogService;

  public ContractController(ContractCatalogService catalogService) {
    this.catalogService = catalogService;
  }

  @GetMapping
  public List<ContractSummaryResponse> listContracts() {
    return catalogService.listContracts();
  }

  @GetMapping("/{contractId}")
  public ContractDetailResponse getContract(@PathVariable("contractId") String contractId) {
    return catalogService.getContract(contractId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contract not found: " + contractId));
  }

  @GetMapping("/{contractId}/versions")
  public List<String> getVersions(@PathVariable("contractId") String contractId) {
    List<String> versions = catalogService.getContractVersions(contractId);
    if (versions.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Contract not found: " + contractId);
    }
    return versions;
  }

  @GetMapping("/{contractId}/versions/{version}")
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
}
