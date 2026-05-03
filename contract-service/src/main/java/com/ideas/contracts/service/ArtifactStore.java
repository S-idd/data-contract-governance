package com.ideas.contracts.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ideas.contracts.core.ContractMetadata;
import com.ideas.contracts.service.model.CreateContractRequest;
import com.ideas.contracts.service.model.CreateContractVersionRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Stable artifact persistence port for contract metadata and schema versions.
 *
 * <p>The metadata database owns check runs, logs, audit rows, and query indexes. The artifact
 * store owns contract definition files: {@code metadata.yaml} plus immutable versioned schema
 * payloads. Callers should use this interface instead of assuming a filesystem layout so the
 * current local store can later be replaced by an S3-backed store.
 */
public interface ArtifactStore {
  List<String> listContracts();

  Optional<ContractMetadata> readMetadata(String contractId);

  List<String> listVersions(String contractId);

  Optional<JsonNode> readSchema(String contractId, String version);

  boolean contractExists(String contractId);

  Path contractDirectory(String contractId);

  Path schemaPath(String contractId, String version);

  long contractLastModified(String contractId);

  void createContract(CreateContractRequest request);

  void createVersion(String contractId, CreateContractVersionRequest request);

  void deleteContractIfExists(String contractId);

  void deleteVersionIfExists(String contractId, String version);

  default ArtifactReference metadataReference(String contractId) {
    return new ArtifactReference(
        "filesystem",
        contractDirectory(contractId).resolve("metadata.yaml").toString());
  }

  default ArtifactReference schemaReference(String contractId, String version) {
    return new ArtifactReference("filesystem", schemaPath(contractId, version).toString());
  }

  record ArtifactReference(String backend, String key) {
    public ArtifactReference {
      if (backend == null || backend.isBlank()) {
        throw new IllegalArgumentException("backend must not be blank.");
      }
      if (key == null || key.isBlank()) {
        throw new IllegalArgumentException("key must not be blank.");
      }
      backend = backend.trim();
      key = key.trim();
    }
  }
}
