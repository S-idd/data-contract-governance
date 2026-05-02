package com.ideas.contracts.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ideas.contracts.core.ContractMetadata;
import com.ideas.contracts.service.model.CreateContractRequest;
import com.ideas.contracts.service.model.CreateContractVersionRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
}
