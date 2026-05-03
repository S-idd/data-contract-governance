package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ArtifactKeyStrategyTest {

  @Test
  void buildsCanonicalS3ReadyKeys() {
    ArtifactKeyStrategy strategy = new ArtifactKeyStrategy("dcg/prod/contracts/");

    assertEquals("dcg/prod/contracts/orders.created", strategy.contractPrefix("orders.created"));
    assertEquals("dcg/prod/contracts/orders.created/metadata.yaml", strategy.metadataKey("orders.created"));
    assertEquals(
        "dcg/prod/contracts/orders.created/versions/v12/schema.json",
        strategy.schemaKey("orders.created", "v12"));
    assertEquals(
        "dcg/prod/contracts/orders.created/versions/v12/schema.sha256",
        strategy.checksumKey("orders.created", "v12.json"));
  }

  @Test
  void rejectsUnsafeKeysBeforeTheyReachObjectStorage() {
    ArtifactKeyStrategy strategy = new ArtifactKeyStrategy();

    assertThrows(IllegalArgumentException.class, () -> new ArtifactKeyStrategy("../contracts"));
    assertThrows(IllegalArgumentException.class, () -> strategy.metadataKey("Orders.Created"));
    assertThrows(IllegalArgumentException.class, () -> strategy.schemaKey("orders.created", "v0"));
    assertThrows(IllegalArgumentException.class, () -> strategy.schemaKey("orders.created/../../x", "v1"));
  }
}
