package com.ideas.contracts.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.ideas.contracts.core.ContractMetadata;
import com.ideas.contracts.core.DefaultSchemaLoader;
import com.ideas.contracts.core.ExecutionException;
import com.ideas.contracts.core.SchemaLoader;
import com.ideas.contracts.core.SchemaValidationException;
import com.ideas.contracts.service.model.CreateContractRequest;
import com.ideas.contracts.service.model.CreateContractVersionRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class FilesystemArtifactStore implements ArtifactStore {
  private static final Comparator<String> VERSION_COMPARATOR =
      Comparator.comparingInt(FilesystemArtifactStore::versionNumber);

  private final Path contractsRoot;
  private final SchemaLoader schemaLoader;
  private final ObjectMapper jsonMapper;
  private final YAMLMapper yamlMapper;

  @Autowired
  public FilesystemArtifactStore(@Value("${contracts.root:contracts}") String contractsRoot) {
    this(Paths.get(contractsRoot), new DefaultSchemaLoader(), new ObjectMapper(), new YAMLMapper());
  }

  FilesystemArtifactStore(
      Path contractsRoot,
      SchemaLoader schemaLoader,
      ObjectMapper jsonMapper,
      YAMLMapper yamlMapper) {
    this.contractsRoot = contractsRoot;
    this.schemaLoader = schemaLoader;
    this.jsonMapper = jsonMapper;
    this.yamlMapper = yamlMapper;
  }

  @Override
  public List<String> listContracts() {
    if (!Files.isDirectory(contractsRoot)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(contractsRoot)) {
      return entries
          .filter(Files::isDirectory)
          .map(path -> path.getFileName().toString())
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read contracts root: " + contractsRoot, e);
    }
  }

  @Override
  public Optional<ContractMetadata> readMetadata(String contractId) {
    String normalizedContractId = normalizeContractId(contractId);
    Path metadataPath = contractDirectory(normalizedContractId).resolve("metadata.yaml");
    if (!Files.exists(metadataPath)) {
      return Optional.empty();
    }
    return Optional.of(schemaLoader.loadMetadata(metadataPath, normalizedContractId));
  }

  @Override
  public List<String> listVersions(String contractId) {
    String normalizedContractId = normalizeContractId(contractId);
    Path contractDir = contractDirectory(normalizedContractId);
    if (!Files.isDirectory(contractDir)) {
      return List.of();
    }
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

  @Override
  public Optional<JsonNode> readSchema(String contractId, String version) {
    String normalizedContractId = normalizeContractId(contractId);
    String normalizedVersion = normalizeVersion(version);
    Path schemaPath = schemaPath(normalizedContractId, normalizedVersion);
    if (!Files.exists(schemaPath)) {
      return Optional.empty();
    }
    try {
      return Optional.of(jsonMapper.readTree(schemaPath.toFile()));
    } catch (IOException e) {
      throw new IllegalStateException("Unable to parse schema file: " + schemaPath, e);
    }
  }

  @Override
  public boolean contractExists(String contractId) {
    return Files.isDirectory(contractDirectory(normalizeContractId(contractId)));
  }

  @Override
  public Path contractDirectory(String contractId) {
    String normalizedContractId = normalizeContractId(contractId);
    Path normalizedRoot = contractsRoot.toAbsolutePath().normalize();
    Path candidate = normalizedRoot.resolve(normalizedContractId).normalize();
    if (!candidate.startsWith(normalizedRoot)) {
      throw new IllegalArgumentException("contractId resolves outside contracts root.");
    }
    return candidate;
  }

  @Override
  public Path schemaPath(String contractId, String version) {
    return contractDirectory(contractId).resolve(normalizeVersion(version) + ".json");
  }

  @Override
  public long contractLastModified(String contractId) {
    String normalizedContractId = normalizeContractId(contractId);
    Path contractDir = contractDirectory(normalizedContractId);
    if (!Files.isDirectory(contractDir)) {
      return 0L;
    }

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

  @Override
  public void createContract(CreateContractRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null.");
    }
    ensureContractsRootExists();

    Path contractDir = contractDirectory(request.contractId());
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
      writeSchema(schemaPath(request.contractId(), request.initialVersion()), request.schema());
    } catch (IOException ex) {
      throw new ExecutionException("Failed to create contract: " + request.contractId(), ex);
    }
  }

  @Override
  public void createVersion(String contractId, CreateContractVersionRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null.");
    }
    String normalizedContractId = normalizeContractId(contractId);
    Path contractDir = contractDirectory(normalizedContractId);
    if (!Files.isDirectory(contractDir)) {
      throw new SchemaValidationException("Contract not found: " + normalizedContractId);
    }

    Path candidatePath = schemaPath(normalizedContractId, request.version());
    if (Files.exists(candidatePath)) {
      throw new SchemaValidationException(
          "Version already exists for contract " + normalizedContractId + ": " + request.version());
    }

    try {
      writeSchema(candidatePath, request.schema());
    } catch (IOException ex) {
      throw new ExecutionException(
          "Failed to create version " + request.version() + " for contract " + normalizedContractId,
          ex);
    }
  }

  @Override
  public void deleteContractIfExists(String contractId) {
    Path contractDir;
    try {
      contractDir = contractDirectory(contractId);
    } catch (Exception ignored) {
      return;
    }
    if (!Files.exists(contractDir)) {
      return;
    }

    try (Stream<Path> stream = Files.walk(contractDir)) {
      stream.sorted(Comparator.reverseOrder()).forEach(this::deleteFileQuietly);
    } catch (Exception ignored) {
      // Best effort cleanup only.
    }
  }

  @Override
  public void deleteVersionIfExists(String contractId, String version) {
    try {
      deleteFileQuietly(schemaPath(contractId, version));
    } catch (Exception ignored) {
      // Best effort cleanup only.
    }
  }

  private void ensureContractsRootExists() {
    try {
      Files.createDirectories(contractsRoot);
    } catch (IOException ex) {
      throw new ExecutionException("Unable to create contracts root directory.", ex);
    }
  }

  private void writeMetadata(
      Path metadataPath,
      String ownerTeam,
      String domain,
      String compatibilityMode,
      String policyPack) throws IOException {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ownerTeam", ownerTeam);
    payload.put("domain", domain);
    payload.put("compatibilityMode", compatibilityMode);
    if (policyPack != null && !policyPack.isBlank()) {
      payload.put("policyPack", policyPack);
    }
    yamlMapper.writerWithDefaultPrettyPrinter().writeValue(metadataPath.toFile(), payload);
  }

  private void writeSchema(Path schemaPath, JsonNode schema) throws IOException {
    jsonMapper.writerWithDefaultPrettyPrinter().writeValue(schemaPath.toFile(), schema);
  }

  private void deleteFileQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (Exception ignored) {
      // Best effort cleanup only.
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

  private String normalizeVersion(String version) {
    if (version == null || version.isBlank()) {
      throw new IllegalArgumentException("version must not be blank.");
    }
    String normalized = version.endsWith(".json") ? version.substring(0, version.length() - 5) : version;
    if (!normalized.matches("^v[1-9][0-9]*$")) {
      throw new IllegalArgumentException("version must match the format v{number}.");
    }
    return normalized;
  }

  private static int versionNumber(String version) {
    return Integer.parseInt(version.substring(1));
  }
}
