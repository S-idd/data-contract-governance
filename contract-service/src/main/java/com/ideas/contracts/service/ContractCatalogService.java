package com.ideas.contracts.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.ideas.contracts.core.ContractMetadata;
import com.ideas.contracts.core.DefaultSchemaLoader;
import com.ideas.contracts.service.model.ContractDetailResponse;
import com.ideas.contracts.service.model.ContractSummaryResponse;
import com.ideas.contracts.service.model.ContractVersionResponse;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ContractCatalogService {
  private final ArtifactStore artifactStore;
  private final PolicyPackRegistry policyPackRegistry;
  private final ConcurrentMap<String, CachedContract> cache;
  private final ConcurrentMap<String, Long> contractModifiedAt;

  @Autowired
  public ContractCatalogService(ArtifactStore artifactStore, PolicyPackRegistry policyPackRegistry) {
    this.artifactStore = artifactStore;
    this.policyPackRegistry = policyPackRegistry;
    this.cache = new ConcurrentHashMap<>();
    this.contractModifiedAt = new ConcurrentHashMap<>();
  }

  ContractCatalogService(PolicyPackRegistry policyPackRegistry, String contractsRoot) {
    this(
        new FilesystemArtifactStore(
            Paths.get(contractsRoot),
            new DefaultSchemaLoader(),
            new ObjectMapper(),
            new YAMLMapper()),
        policyPackRegistry);
  }

  public List<ContractSummaryResponse> listContracts() {
    refreshIncrementally();
    return cache.values().stream()
        .map(CachedContract::summary)
        .sorted(Comparator.comparing(ContractSummaryResponse::contractId))
        .toList();
  }

  public Optional<ContractDetailResponse> getContract(String contractId) {
    String normalized = normalizeContractId(contractId);
    refreshContract(normalized);
    CachedContract cached = cache.get(normalized);
    return cached == null ? Optional.empty() : Optional.of(cached.detail());
  }

  public Optional<ContractVersionResponse> getContractVersion(String contractId, String version) {
    String normalizedContractId = normalizeContractId(contractId);
    String normalizedVersion = normalizeVersion(version);
    refreshContract(normalizedContractId);

    Optional<JsonNode> schema = artifactStore.readSchema(normalizedContractId, normalizedVersion);
    return schema.map(jsonNode -> new ContractVersionResponse(normalizedContractId, normalizedVersion, jsonNode));
  }

  public List<String> getContractVersions(String contractId) {
    String normalized = normalizeContractId(contractId);
    refreshContract(normalized);
    CachedContract cached = cache.get(normalized);
    return cached == null ? List.of() : cached.detail().versions();
  }

  public void invalidateContract(String contractId) {
    if (contractId == null || contractId.isBlank()) {
      return;
    }
    String normalized = contractId.trim();
    cache.remove(normalized);
    contractModifiedAt.remove(normalized);
  }

  private synchronized void refreshIncrementally() {
    List<String> contracts = artifactStore.listContracts();
    if (contracts.isEmpty()) {
      cache.clear();
      contractModifiedAt.clear();
      return;
    }

    Set<String> seen = new HashSet<>();
    for (String contractId : contracts) {
      seen.add(contractId);
      long modifiedAt = artifactStore.contractLastModified(contractId);
      Long previous = contractModifiedAt.get(contractId);
      if (previous == null || previous != modifiedAt) {
        cache.put(contractId, loadContract(contractId));
        contractModifiedAt.put(contractId, modifiedAt);
      }
    }

    for (String existing : new ArrayList<>(cache.keySet())) {
      if (!seen.contains(existing)) {
        cache.remove(existing);
        contractModifiedAt.remove(existing);
      }
    }
  }

  private synchronized void refreshContract(String contractId) {
    if (!artifactStore.contractExists(contractId)) {
      cache.remove(contractId);
      contractModifiedAt.remove(contractId);
      return;
    }

    long modifiedAt = artifactStore.contractLastModified(contractId);
    Long previous = contractModifiedAt.get(contractId);
    if (previous == null || previous != modifiedAt) {
      cache.put(contractId, loadContract(contractId));
      contractModifiedAt.put(contractId, modifiedAt);
    }
  }

  private CachedContract loadContract(String contractId) {
    ContractMetadata metadata = artifactStore.readMetadata(contractId)
        .orElseThrow(() -> new IllegalStateException("Missing metadata.yaml for contract: " + contractId));
    List<String> versions = artifactStore.listVersions(contractId);
    String latestVersion = versions.isEmpty() ? null : versions.get(versions.size() - 1);
    String policyPack = policyPackRegistry.resolveName(metadata.policyPack());

    ContractSummaryResponse summary = new ContractSummaryResponse(
        contractId,
        metadata.ownerTeam(),
        metadata.domain(),
        metadata.compatibilityMode().name(),
        policyPack,
        latestVersion,
        "ACTIVE");
    ContractDetailResponse detail = new ContractDetailResponse(
        contractId,
        metadata.ownerTeam(),
        metadata.domain(),
        metadata.compatibilityMode().name(),
        policyPack,
        versions,
        "ACTIVE");
    return new CachedContract(summary, detail);
  }

  private String normalizeVersion(String version) {
    if (version == null || version.isBlank()) {
      throw new IllegalArgumentException("version must not be blank.");
    }
    String normalized = version.endsWith(".json") ? version.substring(0, version.length() - 5) : version;
    if (!normalized.matches("^v[1-9][0-9]*$")) {
      throw new IllegalArgumentException("Invalid version format: " + version);
    }
    return normalized;
  }

  private String normalizeContractId(String contractId) {
    if (contractId == null || contractId.isBlank()) {
      throw new IllegalArgumentException("contractId must not be blank.");
    }
    return contractId.trim();
  }

  private record CachedContract(ContractSummaryResponse summary, ContractDetailResponse detail) {}
}
