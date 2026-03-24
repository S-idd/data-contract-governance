package com.ideas.contracts.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideas.contracts.core.ContractMetadata;
import com.ideas.contracts.core.DefaultSchemaLoader;
import com.ideas.contracts.core.SchemaLoader;
import com.ideas.contracts.service.model.ContractDetailResponse;
import com.ideas.contracts.service.model.ContractSummaryResponse;
import com.ideas.contracts.service.model.ContractVersionResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ContractCatalogService {
  private static final Comparator<String> VERSION_COMPARATOR =
      Comparator.comparingInt(ContractCatalogService::versionNumber);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final Path contractsRoot;
  private final PolicyPackRegistry policyPackRegistry;
  private final SchemaLoader schemaLoader;
  private final ConcurrentMap<String, CachedContract> cache;
  private final ConcurrentMap<String, Long> contractModifiedAt;

  public ContractCatalogService(
      PolicyPackRegistry policyPackRegistry,
      @Value("${contracts.root:contracts}") String contractsRoot) {
    this.contractsRoot = Paths.get(contractsRoot);
    this.policyPackRegistry = policyPackRegistry;
    this.schemaLoader = new DefaultSchemaLoader();
    this.cache = new ConcurrentHashMap<>();
    this.contractModifiedAt = new ConcurrentHashMap<>();
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
    Path schemaPath = contractsRoot.resolve(normalizedContractId).resolve(normalizedVersion + ".json");
    if (!Files.exists(schemaPath)) {
      return Optional.empty();
    }
    try {
      JsonNode schema = OBJECT_MAPPER.readTree(schemaPath.toFile());
      return Optional.of(new ContractVersionResponse(normalizedContractId, normalizedVersion, schema));
    } catch (IOException e) {
      throw new IllegalStateException("Unable to parse schema file: " + schemaPath, e);
    }
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
    if (!Files.isDirectory(contractsRoot)) {
      cache.clear();
      contractModifiedAt.clear();
      return;
    }

    Set<String> seen = new HashSet<>();
    try (Stream<Path> entries = Files.list(contractsRoot)) {
      entries
          .filter(Files::isDirectory)
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .forEach(contractDir -> {
            String contractId = contractDir.getFileName().toString();
            seen.add(contractId);
            long modifiedAt = computeContractModifiedAt(contractDir);
            Long previous = contractModifiedAt.get(contractId);
            if (previous == null || previous != modifiedAt) {
              cache.put(contractId, loadContract(contractDir));
              contractModifiedAt.put(contractId, modifiedAt);
            }
          });
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read contracts root: " + contractsRoot, e);
    }

    for (String existing : new ArrayList<>(cache.keySet())) {
      if (!seen.contains(existing)) {
        cache.remove(existing);
        contractModifiedAt.remove(existing);
      }
    }
  }

  private synchronized void refreshContract(String contractId) {
    Path contractDir = contractsRoot.resolve(contractId);
    if (!Files.isDirectory(contractDir)) {
      cache.remove(contractId);
      contractModifiedAt.remove(contractId);
      return;
    }
    long modifiedAt = computeContractModifiedAt(contractDir);
    Long previous = contractModifiedAt.get(contractId);
    if (previous == null || previous != modifiedAt) {
      cache.put(contractId, loadContract(contractDir));
      contractModifiedAt.put(contractId, modifiedAt);
    }
  }

  private long computeContractModifiedAt(Path contractDir) {
    long max = lastModified(contractDir.resolve("metadata.yaml"));
    try (Stream<Path> entries = Files.list(contractDir)) {
      long schemaMax = entries
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().matches("^v[1-9][0-9]*\\.json$"))
          .mapToLong(this::lastModified)
          .max()
          .orElse(0L);
      return Math.max(max, schemaMax);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to inspect contract directory: " + contractDir, e);
    }
  }

  private long lastModified(Path path) {
    try {
      FileTime modified = Files.getLastModifiedTime(path);
      return modified.toMillis();
    } catch (Exception ignored) {
      return 0L;
    }
  }

  private CachedContract loadContract(Path contractDir) {
    String contractId = contractDir.getFileName().toString();
    ContractMetadata metadata = schemaLoader.loadMetadata(contractDir.resolve("metadata.yaml"), contractId);
    List<String> versions = versionNames(contractDir);
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

  private List<String> versionNames(Path contractDir) {
    try (Stream<Path> entries = Files.list(contractDir)) {
      return entries
          .filter(Files::isRegularFile)
          .map(path -> path.getFileName().toString())
          .filter(name -> name.matches("^v[1-9][0-9]*\\.json$"))
          .map(name -> name.substring(0, name.length() - 5))
          .sorted(VERSION_COMPARATOR)
          .toList();
    } catch (IOException e) {
      throw new IllegalStateException("Unable to list versions for: " + contractDir, e);
    }
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

  private static int versionNumber(String version) {
    return Integer.parseInt(version.substring(1));
  }

  private record CachedContract(ContractSummaryResponse summary, ContractDetailResponse detail) {}
}
