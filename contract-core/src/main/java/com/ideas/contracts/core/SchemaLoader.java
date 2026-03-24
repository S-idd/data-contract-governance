package com.ideas.contracts.core;

import java.nio.file.Path;
import java.util.List;

public interface SchemaLoader {
  ContractMetadata loadMetadata(Path metadataPath, String contractId);

  SchemaSnapshot loadSchema(Path schemaPath);

  List<Path> listVersionFiles(Path contractDirectory);
}
