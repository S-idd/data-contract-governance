package com.ideas.contracts.core;

import java.nio.file.Path;
import java.util.List;

public interface SchemaValidator {
  void validateContractDirectory(Path contractDirectory);

  void validateMetadata(ContractMetadata metadata, String contractId);

  void validateVersionFiles(List<Path> versionFiles);

  void validateSchema(SchemaSnapshot snapshot, Path schemaPath);
}
