package com.ideas.contracts.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideas.contracts.service.model.ContractDetailResponse;
import com.ideas.contracts.service.model.ContractSummaryResponse;
import com.ideas.contracts.service.model.ContractVersionResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ContractCatalogService {
  private static final Comparator<String> VERSION_COMPARATOR =
      Comparator.comparingInt(ContractCatalogService::versionNumber);

  private final Path contractsRoot;
  private final ObjectMapper objectMapper;

  public ContractCatalogService(@Value("${contracts.root:contracts}") String contractsRoot) {
    this.contractsRoot = Paths.get(contractsRoot);
    this.objectMapper = new ObjectMapper();
  }

  public List<ContractSummaryResponse> listContracts() {
    if (!Files.isDirectory(contractsRoot)) {
      return List.of();
    }

    List<ContractSummaryResponse> contracts = new ArrayList<>();
    try (Stream<Path> entries = Files.list(contractsRoot)) {
      entries
          .filter(Files::isDirectory)
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .forEach(contractDir -> contracts.add(toSummary(contractDir)));
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read contracts root: " + contractsRoot, e);
    }
    return contracts;
  }

  public Optional<ContractDetailResponse> getContract(String contractId) {
    Path contractDir = contractsRoot.resolve(contractId);
    if (!Files.isDirectory(contractDir)) {
      return Optional.empty();
    }
    return Optional.of(toDetail(contractDir));
  }

  public Optional<ContractVersionResponse> getContractVersion(String contractId, String version) {
    Path contractDir = contractsRoot.resolve(contractId);
    if (!Files.isDirectory(contractDir)) {
      return Optional.empty();
    }

    String normalizedVersion = normalizeVersion(version);
    Path schemaPath = contractDir.resolve(normalizedVersion + ".json");
    if (!Files.exists(schemaPath)) {
      return Optional.empty();
    }

    try {
      JsonNode schema = objectMapper.readTree(schemaPath.toFile());
      return Optional.of(new ContractVersionResponse(contractId, normalizedVersion, schema));
    } catch (IOException e) {
      throw new IllegalStateException("Unable to parse schema file: " + schemaPath, e);
    }
  }

  public List<String> getContractVersions(String contractId) {
    Path contractDir = contractsRoot.resolve(contractId);
    if (!Files.isDirectory(contractDir)) {
      return List.of();
    }
    return versionNames(contractDir);
  }

  private ContractSummaryResponse toSummary(Path contractDir) {
    String contractId = contractDir.getFileName().toString();
    Map<String, String> metadata = readMetadata(contractDir.resolve("metadata.yaml"));
    List<String> versions = versionNames(contractDir);
    String latestVersion = versions.isEmpty() ? null : versions.get(versions.size() - 1);
    return new ContractSummaryResponse(
        contractId,
        metadata.getOrDefault("ownerTeam", ""),
        metadata.getOrDefault("domain", ""),
        metadata.getOrDefault("compatibilityMode", ""),
        latestVersion,
        "ACTIVE");
  }

  private ContractDetailResponse toDetail(Path contractDir) {
    String contractId = contractDir.getFileName().toString();
    Map<String, String> metadata = readMetadata(contractDir.resolve("metadata.yaml"));
    List<String> versions = versionNames(contractDir);
    return new ContractDetailResponse(
        contractId,
        metadata.getOrDefault("ownerTeam", ""),
        metadata.getOrDefault("domain", ""),
        metadata.getOrDefault("compatibilityMode", ""),
        versions,
        "ACTIVE");
  }

  private Map<String, String> readMetadata(Path metadataPath) {
    Map<String, String> values = new HashMap<>();
    if (!Files.exists(metadataPath)) {
      return values;
    }

    try {
      List<String> lines = Files.readAllLines(metadataPath, StandardCharsets.UTF_8);
      for (String rawLine : lines) {
        String line = rawLine.trim();
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        int separator = line.indexOf(':');
        if (separator <= 0) {
          continue;
        }
        String key = line.substring(0, separator).trim();
        String value = line.substring(separator + 1).trim();
        if (!key.isEmpty()) {
          values.put(key, value);
        }
      }
      return values;
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read metadata file: " + metadataPath, e);
    }
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
    String normalized = version.endsWith(".json") ? version.substring(0, version.length() - 5) : version;
    if (!normalized.matches("^v[1-9][0-9]*$")) {
      throw new IllegalStateException("Invalid version format: " + version);
    }
    return normalized;
  }

  private static int versionNumber(String version) {
    return Integer.parseInt(version.substring(1));
  }
}
