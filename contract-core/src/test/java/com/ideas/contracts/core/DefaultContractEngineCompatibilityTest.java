package com.ideas.contracts.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultContractEngineCompatibilityTest {
  private final DefaultContractEngine engine = new DefaultContractEngine();

  @TempDir
  Path tempDir;

  @Test
  void checkCompatibilityFailsOnBreakingChanges() throws IOException {
    Path base = writeSchema(
        "base.json",
        """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string"},
            "status": {"type": "string", "enum": ["NEW", "DONE"]}
          },
          "required": ["id"]
        }
        """
    );
    Path candidate = writeSchema(
        "candidate.json",
        """
        {
          "type": "object",
          "properties": {
            "id": {"type": "integer"},
            "status": {"type": "string", "enum": ["NEW"]},
            "region": {"type": "string"}
          },
          "required": ["id", "region"]
        }
        """
    );

    CompatibilityResult result = engine.checkCompatibility(base, candidate, CompatibilityMode.BACKWARD);

    assertEquals(CheckStatus.FAIL, result.status());
    assertTrue(result.breakingChanges().stream().anyMatch(m -> m.contains("Field type changed: id")));
    assertTrue(result.breakingChanges().stream().anyMatch(m -> m.contains("Enum value removed: status.DONE")));
    assertTrue(result.breakingChanges().stream().anyMatch(m -> m.contains("Required field added: region")));
  }

  @Test
  void checkCompatibilityPassesAndWarnsOnEnumAddition() throws IOException {
    Path base = writeSchema(
        "base.json",
        """
        {
          "type": "object",
          "properties": {
            "status": {"type": "string", "enum": ["NEW"]}
          }
        }
        """
    );
    Path candidate = writeSchema(
        "candidate.json",
        """
        {
          "type": "object",
          "properties": {
            "status": {"type": "string", "enum": ["NEW", "DONE"]}
          }
        }
        """
    );

    CompatibilityResult result = engine.checkCompatibility(base, candidate, CompatibilityMode.BACKWARD);

    assertEquals(CheckStatus.PASS, result.status());
    assertTrue(result.warnings().stream().anyMatch(m -> m.contains("Enum value added: status.DONE")));
  }

  @Test
  void checkCompatibilityRespectsPolicyPackOverrides() throws IOException {
    Path base = writeSchema(
        "base.json",
        """
        {
          "type": "object",
          "properties": {
            "status": {"type": "string", "enum": ["NEW"]}
          }
        }
        """
    );
    Path candidate = writeSchema(
        "candidate.json",
        """
        {
          "type": "object",
          "properties": {
            "status": {"type": "string", "enum": ["NEW", "DONE"]}
          }
        }
        """
    );

    EnumMap<RuleId, RuleSeverity> rules = new EnumMap<>(PolicyPackDefaults.baselineRules());
    rules.put(RuleId.ENUM_VALUE_ADDED, RuleSeverity.BREAKING);
    PolicyPack strictPack = new PolicyPack("strict", rules);

    CompatibilityResult result = engine.checkCompatibility(base, candidate, CompatibilityMode.BACKWARD, strictPack);

    assertEquals(CheckStatus.FAIL, result.status());
    assertTrue(result.breakingChanges().stream().anyMatch(m -> m.contains("Enum value added: status.DONE")));
  }

  @Test
  void diffShowsSemanticChanges() throws IOException {
    Path base = writeSchema(
        "base.json",
        """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string"},
            "status": {"type": "string", "enum": ["NEW"]}
          }
        }
        """
    );
    Path candidate = writeSchema(
        "candidate.json",
        """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string"},
            "status": {"type": "string", "enum": ["NEW", "DONE"]},
            "amount": {"type": "number"}
          },
          "required": ["amount"]
        }
        """
    );

    String diff = engine.diff(base, candidate);

    assertTrue(diff.contains("+ field added: amount"));
    assertTrue(diff.contains("! required added: amount"));
    assertTrue(diff.contains("~ enum value added: status.DONE"));
  }

  private Path writeSchema(String fileName, String json) throws IOException {
    Path path = tempDir.resolve(fileName);
    Files.writeString(path, json);
    return path;
  }
}
