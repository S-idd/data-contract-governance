package com.ideas.contracts.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.ideas.contracts.core.CompatibilityMode;
import com.ideas.contracts.core.CompatibilityResult;
import com.ideas.contracts.core.ContractEngine;
import com.ideas.contracts.core.ContractMetadata;
import com.ideas.contracts.core.CompatibilityException;
import com.ideas.contracts.core.PolicyPack;
import com.ideas.contracts.core.SchemaLoader;
import com.ideas.contracts.core.DefaultSchemaLoader;
import com.ideas.contracts.core.SchemaValidationException;
import com.ideas.contracts.service.model.ContractDetailResponse;
import com.ideas.contracts.service.model.ContractVersionResponse;
import com.ideas.contracts.service.model.CreateContractRequest;
import com.ideas.contracts.service.model.CreateContractVersionRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ContractWriteService {
  private final Path contractsRoot;
  private final ContractEngine contractEngine;
  private final ContractCatalogService contractCatalogService;
  private final PolicyPackRegistry policyPackRegistry;
  private final boolean strictMode;
  private final ObjectMapper jsonMapper;
  private final YAMLMapper yamlMapper;
  private final SchemaLoader schemaLoader;

  public ContractWriteService(
      ContractEngine contractEngine,
      ContractCatalogService contractCatalogService,
      PolicyPackRegistry policyPackRegistry,
      @Value("${contracts.root:contracts}") String contractsRoot,
      @Value("${contracts.validation.strict-mode:true}") boolean strictMode) {
    this.contractEngine = contractEngine;
    this.contractCatalogService = contractCatalogService;
    this.policyPackRegistry = policyPackRegistry;
    this.contractsRoot = Paths.get(contractsRoot);
    this.strictMode = strictMode;
    this.jsonMapper = new ObjectMapper();
    this.yamlMapper = new YAMLMapper();
    this.schemaLoader = new DefaultSchemaLoader();
  }

  public ContractDetailResponse createContract(CreateContractRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null.");
    }
    ensureContractsRootExists();

    Path contractDir = resolveContractDir(request.contractId());
    if (Files.exists(contractDir)) {
      throw new SchemaValidationException("Contract already exists: " + request.contractId());
    }

    try {
      Files.createDirectories(contractDir);
      writeMetadata(
          contractDir.resolve("metadata.yaml"),
          request.ownerTeam(),
          request.domain(),
          request.compatibilityMode(),
          request.policyPack());
      writeSchema(contractDir.resolve(request.initialVersion() + ".json"), request.schema());
      contractEngine.lint(contractDir);
      contractCatalogService.invalidateContract(request.contractId());
      return contractCatalogService.getContract(request.contractId())
          .orElseThrow(() -> new IllegalStateException("Created contract not found in catalog."));
    } catch (RuntimeException ex) {
      deleteDirectoryQuietly(contractDir);
      throw ex;
    } catch (Exception ex) {
      deleteDirectoryQuietly(contractDir);
      throw new com.ideas.contracts.core.ExecutionException(
          "Failed to create contract: " + request.contractId(),
          ex);
    }
  }

  public ContractVersionResponse createVersion(String contractId, CreateContractVersionRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null.");
    }
    String normalizedContractId = normalizeContractId(contractId);
    Path contractDir = resolveContractDir(normalizedContractId);
    if (!Files.isDirectory(contractDir)) {
      throw new SchemaValidationException("Contract not found: " + normalizedContractId);
    }

    Path candidatePath = contractDir.resolve(request.version() + ".json");
    if (Files.exists(candidatePath)) {
      throw new SchemaValidationException(
          "Version already exists for contract " + normalizedContractId + ": " + request.version());
    }

    List<String> existingVersions = contractCatalogService.getContractVersions(normalizedContractId);
    ensureVersionMonotonic(existingVersions, request.version());

    try {
      writeSchema(candidatePath, request.schema());
      contractEngine.lint(contractDir);
      validateCompatibilityIfNeeded(normalizedContractId, request.version(), existingVersions, candidatePath);
      contractCatalogService.invalidateContract(normalizedContractId);
      return contractCatalogService.getContractVersion(normalizedContractId, request.version())
          .orElseThrow(() -> new IllegalStateException("Created version not found in catalog."));
    } catch (RuntimeException ex) {
      deleteFileQuietly(candidatePath);
      throw ex;
    } catch (Exception ex) {
      deleteFileQuietly(candidatePath);
      throw new com.ideas.contracts.core.ExecutionException(
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
    Path basePath = resolveContractDir(contractId).resolve(baseVersion + ".json");
    ContractMetadata metadata =
        schemaLoader.loadMetadata(resolveContractDir(contractId).resolve("metadata.yaml"), contractId);
    CompatibilityMode mode = metadata.compatibilityMode();
    PolicyPack policyPack = policyPackRegistry.resolve(metadata.policyPack());
    CompatibilityResult result = contractEngine.checkCompatibility(basePath, candidatePath, mode, policyPack);
    if (strictMode && result.status() == com.ideas.contracts.core.CheckStatus.FAIL) {
      throw new CompatibilityException(
          "Strict mode rejected version "
              + candidateVersion
              + ". Breaking changes: "
              + String.join("; ", result.breakingChanges()));
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
      throw new SchemaValidationException("New version must be greater than existing latest version v" + currentMax + ".");
    }
    if (candidate != currentMax + 1) {
      throw new SchemaValidationException("Version sequence must be incremental. Expected v" + (currentMax + 1) + ".");
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

  private Path resolveContractDir(String contractId) {
    Path normalizedRoot = contractsRoot.toAbsolutePath().normalize();
    Path candidate = normalizedRoot.resolve(contractId).normalize();
    if (!candidate.startsWith(normalizedRoot)) {
      throw new IllegalArgumentException("contractId resolves outside contracts root.");
    }
    return candidate;
  }

  private void ensureContractsRootExists() {
    try {
      Files.createDirectories(contractsRoot);
    } catch (IOException ex) {
      throw new com.ideas.contracts.core.ExecutionException("Unable to create contracts root directory.", ex);
    }
  }

  private void writeMetadata(
      Path metadataPath,
      String ownerTeam,
      String domain,
      String compatibilityMode,
      String policyPack) throws IOException {
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("ownerTeam", ownerTeam);
    payload.put("domain", domain);
    payload.put("compatibilityMode", compatibilityMode);
    if (policyPack != null && !policyPack.isBlank()) {
      payload.put("policyPack", policyPack);
    }
    yamlMapper.writerWithDefaultPrettyPrinter().writeValue(metadataPath.toFile(), payload);
  }

  private void writeSchema(Path schemaPath, com.fasterxml.jackson.databind.JsonNode schema) throws IOException {
    jsonMapper.writerWithDefaultPrettyPrinter().writeValue(schemaPath.toFile(), schema);
  }

  private void deleteFileQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (Exception ignored) {
      // Best effort cleanup only.
    }
  }

  private void deleteDirectoryQuietly(Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
      stream.sorted(java.util.Comparator.reverseOrder()).forEach(this::deleteFileQuietly);
    } catch (Exception ignored) {
      // Best effort cleanup only.
    }
  }
}
