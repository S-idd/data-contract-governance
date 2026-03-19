package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.ideas.contracts.service.model.ContractSummaryResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContractCatalogPolicyPackTest {
  @TempDir
  Path tempDir;

  @Test
  void resolvesPolicyPackFromMetadataOrDefault() throws Exception {
    Path contractsRoot = tempDir.resolve("contracts");
    Files.createDirectories(contractsRoot);

    Files.writeString(
        contractsRoot.resolve("policy-packs.json"),
        """
        {
          "defaultPack": "baseline",
          "packs": {
            "baseline": {
              "description": "Baseline rules",
              "rules": {
                "FIELD_REMOVED": "BREAKING",
                "FIELD_TYPE_CHANGED": "BREAKING",
                "REQUIRED_FIELD_ADDED": "BREAKING",
                "ENUM_VALUE_REMOVED": "BREAKING",
                "ENUM_VALUE_ADDED": "WARNING"
              }
            },
            "strict": {
              "description": "Strict pack",
              "rules": {
                "ENUM_VALUE_ADDED": "BREAKING"
              }
            }
          }
        }
        """
    );

    Path strictContract = contractsRoot.resolve("orders.created");
    Files.createDirectories(strictContract);
    Files.writeString(
        strictContract.resolve("metadata.yaml"),
        "ownerTeam: platform\ndomain: commerce\ncompatibilityMode: BACKWARD\npolicyPack: strict\n");
    Files.writeString(strictContract.resolve("v1.json"), "{}");

    Path defaultContract = contractsRoot.resolve("payments.completed");
    Files.createDirectories(defaultContract);
    Files.writeString(
        defaultContract.resolve("metadata.yaml"),
        "ownerTeam: platform\ndomain: commerce\ncompatibilityMode: BACKWARD\n");
    Files.writeString(defaultContract.resolve("v1.json"), "{}");

    PolicyPackRegistry registry = new PolicyPackRegistry("", contractsRoot.toString());
    ContractCatalogService catalogService = new ContractCatalogService(registry, contractsRoot.toString());

    List<ContractSummaryResponse> contracts = catalogService.listContracts();
    Map<String, ContractSummaryResponse> byId = contracts.stream()
        .collect(Collectors.toMap(ContractSummaryResponse::contractId, Function.identity()));

    ContractSummaryResponse strict = byId.get("orders.created");
    ContractSummaryResponse baseline = byId.get("payments.completed");

    assertNotNull(strict);
    assertNotNull(baseline);
    assertEquals("strict", strict.policyPack());
    assertEquals("baseline", baseline.policyPack());
  }
}
