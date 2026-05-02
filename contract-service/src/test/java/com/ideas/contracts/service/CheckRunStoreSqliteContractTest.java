package com.ideas.contracts.service;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.io.TempDir;

class CheckRunStoreSqliteContractTest extends AbstractMetadataStoreContractTest {
  @TempDir
  Path tempDir;

  @Override
  protected MetadataStoreFixture createFixture() {
    Path dbPath = tempDir.resolve("checks-contract.db");
    CheckStoreProperties properties = new CheckStoreProperties();
    properties.setPath(dbPath.toString());
    properties.getPool().setConnectionTimeout(Duration.ofMillis(250));

    CheckRunStore store = new CheckRunStore(properties);
    store.initialize();

    return new MetadataStoreFixture() {
      @Override
      public MetadataStore store() {
        return store;
      }

      @Override
      public void close() {
        store.shutdown();
      }
    };
  }
}
