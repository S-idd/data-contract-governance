package com.ideas.contracts.service;

import com.ideas.contracts.core.CompatibilityException;
import com.ideas.contracts.core.CompatibilityMode;
import com.ideas.contracts.core.CompatibilityResult;
import com.ideas.contracts.core.ContractEngine;
import com.ideas.contracts.core.ContractMetadata;
import com.ideas.contracts.core.ExecutionException;
import com.ideas.contracts.core.PolicyPack;
import com.ideas.contracts.core.SchemaValidationException;
import com.ideas.contracts.service.model.ContractDetailResponse;
import com.ideas.contracts.service.model.ContractVersionResponse;
import com.ideas.contracts.service.model.CreateContractRequest;
import com.ideas.contracts.service.model.CreateContractVersionRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ContractWriteService {
  private final ContractEngine contractEngine;
  private final ContractCatalogService contractCatalogService;
  private final PolicyPackRegistry policyPackRegistry;
  private final ArtifactStore artifactStore;
  private final boolean strictMode;

  public ContractWriteService(
      ContractEngine contractEngine,
      ContractCatalogService contractCatalogService,
      PolicyPackRegistry policyPackRegistry,
      ArtifactStore artifactStore,
      @Value("${contracts.validation.strict-mode:true}") boolean strictMode) {
    this.contractEngine = contractEngine;
    this.contractCatalogService = contractCatalogService;
    this.policyPackRegistry = policyPackRegistry;
    this.artifactStore = artifactStore;
    this.strictMode = strictMode;
  }

  public ContractDetailResponse createContract(CreateContractRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null.");
    }

    Path contractDir = artifactStore.contractDirectory(request.contractId());
    boolean created = false;

    try {
      artifactStore.createContract(request);
      created = true;
      contractEngine.lint(contractDir);
      contractCatalogService.invalidateContract(request.contractId());
      return contractCatalogService.getContract(request.contractId())
          .orElseThrow(() -> new IllegalStateException("Created contract not found in catalog."));
    } catch (RuntimeException ex) {
      if (created) {
        artifactStore.deleteContractIfExists(request.contractId());
      }
      throw ex;
    } catch (Exception ex) {
      if (created) {
        artifactStore.deleteContractIfExists(request.contractId());
      }
      throw new ExecutionException("Failed to create contract: " + request.contractId(), ex);
    }
  }

  public ContractVersionResponse createVersion(String contractId, CreateContractVersionRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null.");
    }

    String normalizedContractId = normalizeContractId(contractId);
    if (!artifactStore.contractExists(normalizedContractId)) {
      throw new SchemaValidationException("Contract not found: " + normalizedContractId);
    }

    List<String> existingVersions = contractCatalogService.getContractVersions(normalizedContractId);
    ensureVersionMonotonic(existingVersions, request.version());

    boolean createdVersion = false;
    Path contractDir = artifactStore.contractDirectory(normalizedContractId);
    Path candidatePath = artifactStore.schemaPath(normalizedContractId, request.version());

    try {
      artifactStore.createVersion(normalizedContractId, request);
      createdVersion = true;
      contractEngine.lint(contractDir);
      validateCompatibilityIfNeeded(normalizedContractId, request.version(), existingVersions, candidatePath);
      contractCatalogService.invalidateContract(normalizedContractId);
      return contractCatalogService.getContractVersion(normalizedContractId, request.version())
          .orElseThrow(() -> new IllegalStateException("Created version not found in catalog."));
    } catch (RuntimeException ex) {
      if (createdVersion) {
        artifactStore.deleteVersionIfExists(normalizedContractId, request.version());
      }
      throw ex;
    } catch (Exception ex) {
      if (createdVersion) {
        artifactStore.deleteVersionIfExists(normalizedContractId, request.version());
      }
      throw new ExecutionException(
          "Failed to create version "
              + request.version()
              + " for contract "
              + normalizedContractId,
          ex);
    }
  }

  private void validateCompatibilityIfNeeded(
      String contractId,
      String candidateVersion,
      List<String> existingVersions,
      Path candidatePath) {
    if (existingVersions.isEmpty()) {
      return;
    }

    String baseVersion = existingVersions.get(existingVersions.size() - 1);
    Path basePath = artifactStore.schemaPath(contractId, baseVersion);
    ContractMetadata metadata = artifactStore.readMetadata(contractId)
        .orElseThrow(() -> new IllegalStateException("Contract metadata not found: " + contractId));
    CompatibilityMode mode = metadata.compatibilityMode();
    PolicyPack policyPack = resolvePolicyPack(contractId, metadata.policyPack());
    CompatibilityResult result = contractEngine.checkCompatibility(basePath, candidatePath, mode, policyPack);
    if (strictMode && result.status() == com.ideas.contracts.core.CheckStatus.FAIL) {
      throw new CompatibilityException(
          "Strict mode rejected version "
              + candidateVersion
              + ". Breaking changes: "
          + String.join("; ", result.breakingChanges()));
    }
  }

  private PolicyPack resolvePolicyPack(String contractId, String policyPack) {
    try {
      return policyPackRegistry.resolve(policyPack);
    } catch (RuntimeException ex) {
      throw new PolicyPackResolutionException(contractId, null, policyPack, ex);
    }
  }

  private void ensureVersionMonotonic(List<String> existingVersions, String candidateVersion) {
    if (existingVersions.isEmpty()) {
      if (!"v1".equals(candidateVersion)) {
        throw new SchemaValidationException("First contract version must be v1.");
      }
      return;
    }

    int currentMax = versionNumber(existingVersions.get(existingVersions.size() - 1));
    int candidate = versionNumber(candidateVersion);
    if (candidate <= currentMax) {
      throw new SchemaValidationException(
          "New version must be greater than existing latest version v" + currentMax + ".");
    }
    if (candidate != currentMax + 1) {
      throw new SchemaValidationException(
          "Version sequence must be incremental. Expected v" + (currentMax + 1) + ".");
    }
  }

  private int versionNumber(String version) {
    String normalized = version.toLowerCase(Locale.ROOT).trim();
    return Integer.parseInt(normalized.substring(1));
  }

  private String normalizeContractId(String contractId) {
    if (contractId == null || contractId.isBlank()) {
      throw new IllegalArgumentException("contractId must not be blank.");
    }
    String normalized = contractId.trim();
    if (!normalized.matches("^[a-z0-9]+(\\.[a-z0-9]+)*$")) {
      throw new IllegalArgumentException("contractId must use lowercase dot-separated format.");
    }
    return normalized;
  }
}
