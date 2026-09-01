package com.ideas.contracts.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForwardOptionalFieldCompatibilityTest {
  private final DefaultContractEngine engine = new DefaultContractEngine();

  @TempDir
  Path tempDir;

  @Test
  void forwardOptionalRootAdditionsVaryByOldConsumerProfileForAllSupportedVariants()
      throws IOException {
    Map<String, String> variants = Map.of(
        "string", "{\"type\":\"string\"}",
        "integer", "{\"type\":\"integer\"}",
        "boolean", "{\"type\":\"boolean\"}",
        "array", "{\"type\":\"array\",\"items\":{\"type\":\"string\"}}");

    for (Map.Entry<String, String> variant : variants.entrySet()) {
      for (String additionalProperties : new String[] {null, "true"}) {
        Path openBase = schema(
            "open-" + variant.getKey() + "-" + additionalProperties + ".json",
            additionalProperties,
            "\"id\":{\"type\":\"string\"}",
            "");
        CompatibilityResult open = engine.checkCompatibility(
            openBase,
            schema(
                "open-candidate-" + variant.getKey() + "-" + additionalProperties + ".json",
                additionalProperties,
                "\"id\":{\"type\":\"string\"},\"added\":" + variant.getValue(),
                ""),
            CompatibilityMode.FORWARD);
        assertEquals(CheckStatus.PASS, open.status(), () -> variant + ": " + open.breakingChanges());
      }

      Path closedBase = schema(
          "closed-" + variant.getKey() + ".json",
          "false",
          "\"id\":{\"type\":\"string\"}",
          "");
      CompatibilityResult closed = engine.checkCompatibility(
          closedBase,
          schema(
              "closed-candidate-" + variant.getKey() + ".json",
              "false",
              "\"id\":{\"type\":\"string\"},\"added\":" + variant.getValue(),
              ""),
          CompatibilityMode.FORWARD);
      assertEquals(CheckStatus.FAIL, closed.status());
      assertTrue(closed.breakingChanges().stream().anyMatch(
          message -> message.contains("Optional field added to closed consumer object: added")));
      assertEquals(1, closed.breakingChanges().size());
      assertFalse(closed.breakingChanges().stream().anyMatch(
          message -> message.contains("Field removed")));
    }
  }

  @Test
  void forwardOptionalNestedAdditionUsesAffectedConsumerObjectProfile() throws IOException {
    Path openBase = write(
        "nested-open.json",
        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
            + "\"profile\":{\"type\":\"object\",\"additionalProperties\":true,"
            + "\"properties\":{\"name\":{\"type\":\"string\"}}}}}");
    Path closedBase = write(
        "nested-closed.json",
        "{\"type\":\"object\",\"properties\":{"
            + "\"profile\":{\"type\":\"object\",\"additionalProperties\":false,"
            + "\"properties\":{\"name\":{\"type\":\"string\"}}}}}");
    Path omittedBase = write(
        "nested-omitted.json",
        "{\"type\":\"object\",\"properties\":{"
            + "\"profile\":{\"type\":\"object\","
            + "\"properties\":{\"name\":{\"type\":\"string\"}}}}}");
    Path openCandidate = write(
        "nested-open-candidate.json",
        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
            + "\"profile\":{\"type\":\"object\",\"additionalProperties\":true,"
            + "\"properties\":{\"name\":{\"type\":\"string\"},"
            + "\"age\":{\"type\":\"integer\"}}}}}");
    Path closedCandidate = write(
        "nested-closed-candidate.json",
        "{\"type\":\"object\",\"properties\":{"
            + "\"profile\":{\"type\":\"object\",\"additionalProperties\":false,"
            + "\"properties\":{\"name\":{\"type\":\"string\"},"
            + "\"age\":{\"type\":\"integer\"}}}}}");
    Path omittedCandidate = write(
        "nested-omitted-candidate.json",
        "{\"type\":\"object\",\"properties\":{"
            + "\"profile\":{\"type\":\"object\","
            + "\"properties\":{\"name\":{\"type\":\"string\"},"
            + "\"age\":{\"type\":\"integer\"}}}}}");

    assertEquals(CheckStatus.PASS,
        engine.checkCompatibility(openBase, openCandidate, CompatibilityMode.FORWARD).status());
    assertEquals(CheckStatus.PASS,
        engine.checkCompatibility(omittedBase, omittedCandidate, CompatibilityMode.FORWARD).status());
    CompatibilityResult closed = engine.checkCompatibility(
        closedBase, closedCandidate, CompatibilityMode.FORWARD);
    assertEquals(CheckStatus.FAIL, closed.status());
    assertTrue(closed.breakingChanges().stream().anyMatch(
        message -> message.contains("closed consumer object: profile.age")));
  }

  @Test
  void fullCombinesBackwardRequirednessWithProfileAwareForwardResult() throws IOException {
    Path openBase = schema("open.json", null, "\"id\":{\"type\":\"string\"}", "");
    Path closedBase = schema("closed.json", "false", "\"id\":{\"type\":\"string\"}", "");
    Path optionalCandidate = schema(
        "optional.json", null,
        "\"id\":{\"type\":\"string\"},\"added\":{\"type\":\"string\"}", "");
    Path requiredCandidate = schema(
        "required.json", null,
        "\"id\":{\"type\":\"string\"},\"added\":{\"type\":\"string\"}",
        ",\"required\":[\"added\"]");

    assertEquals(CheckStatus.PASS,
        engine.checkCompatibility(openBase, optionalCandidate, CompatibilityMode.FULL).status());
    assertEquals(CheckStatus.FAIL,
        engine.checkCompatibility(closedBase, optionalCandidate, CompatibilityMode.FULL).status());
    CompatibilityResult requiredForward = engine.checkCompatibility(
        openBase, requiredCandidate, CompatibilityMode.FORWARD);
    assertEquals(CheckStatus.FAIL, requiredForward.status());
    assertEquals(
        java.util.List.of("[FORWARD] Required field added: added"),
        requiredForward.breakingChanges());
    CompatibilityResult requiredFull = engine.checkCompatibility(
        openBase, requiredCandidate, CompatibilityMode.FULL);
    assertEquals(CheckStatus.FAIL, requiredFull.status());
    assertEquals(
        java.util.List.of(
            "Required field added: added",
            "[FORWARD] Required field added: added"),
        requiredFull.breakingChanges());
  }

  @Test
  void forwardGenuineRequiredFieldAdditionUsesRequiredFieldAddedRule() throws IOException {
    Path base = schema(
        "forward-required-addition-base.json", null,
        "\"id\":{\"type\":\"string\"}", "");
    Path candidate = schema(
        "forward-required-addition-candidate.json", null,
        "\"id\":{\"type\":\"string\"},\"added\":{\"type\":\"string\"}",
        ",\"required\":[\"added\"]");

    CompatibilityResult forward = engine.checkCompatibility(
        base, candidate, CompatibilityMode.FORWARD);

    assertEquals(CheckStatus.FAIL, forward.status());
    assertEquals(
        java.util.List.of("[FORWARD] Required field added: added"),
        forward.breakingChanges());
  }

  @Test
  void openConsumerSeverityCanBeConfiguredWithoutChangingClosedConsumerSafety() throws IOException {
    Path openBase = schema("open-policy.json", "true", "\"id\":{\"type\":\"string\"}", "");
    Path closedBase = schema("closed-policy.json", "false", "\"id\":{\"type\":\"string\"}", "");
    Path openCandidate = schema(
        "open-candidate-policy.json", "true",
        "\"id\":{\"type\":\"string\"},\"added\":{\"type\":\"string\"}", "");
    Path closedCandidate = schema(
        "closed-candidate-policy.json", "false",
        "\"id\":{\"type\":\"string\"},\"added\":{\"type\":\"string\"}", "");
    EnumMap<RuleId, RuleSeverity> rules = new EnumMap<>(PolicyPackDefaults.baselineRules());
    rules.put(RuleId.FORWARD_OPTIONAL_FIELD_ADDED, RuleSeverity.WARNING);
    PolicyPack warningPack = new PolicyPack("warn-forward-additions", rules);

    CompatibilityResult open = engine.checkCompatibility(
        openBase, openCandidate, CompatibilityMode.FORWARD, warningPack);
    assertEquals(CheckStatus.PASS, open.status());
    assertTrue(open.warnings().stream().anyMatch(
        message -> message.contains("Optional field added to open consumer object: added")));
    assertEquals(CheckStatus.FAIL,
        engine.checkCompatibility(
            closedBase, closedCandidate, CompatibilityMode.FORWARD, warningPack).status());

    rules.put(RuleId.FORWARD_OPTIONAL_FIELD_ADDED, RuleSeverity.BREAKING);
    PolicyPack breakingPack = new PolicyPack("break-forward-additions", rules);
    CompatibilityResult openBreaking = engine.checkCompatibility(
        openBase, openCandidate, CompatibilityMode.FORWARD, breakingPack);
    assertEquals(CheckStatus.FAIL, openBreaking.status());
    assertEquals(
        java.util.List.of("[FORWARD] Optional field added to open consumer object: added"),
        openBreaking.breakingChanges());
  }

  @Test
  void genuineFieldRemovalStillUsesBackwardFieldRemovedRule() throws IOException {
    Path base = schema(
        "removal-base.json", null,
        "\"id\":{\"type\":\"string\"},\"removed\":{\"type\":\"string\"}", "");
    Path candidate = schema(
        "removal-candidate.json", null, "\"id\":{\"type\":\"string\"}", "");

    CompatibilityResult backward = engine.checkCompatibility(
        base, candidate, CompatibilityMode.BACKWARD);
    assertEquals(CheckStatus.FAIL, backward.status());
    assertEquals(java.util.List.of("Field removed: removed"), backward.breakingChanges());
    assertTrue(backward.warnings().isEmpty());
  }

  @Test
  void forwardOptionalFieldRemovalUsesFieldRemovedRule() throws IOException {
    Path base = schema(
        "forward-optional-removal-base.json", null,
        "\"id\":{\"type\":\"string\"},\"removed\":{\"type\":\"string\"}", "");
    Path candidate = schema(
        "forward-optional-removal-candidate.json", null,
        "\"id\":{\"type\":\"string\"}", "");

    CompatibilityResult forward = engine.checkCompatibility(
        base, candidate, CompatibilityMode.FORWARD);

    assertEquals(
        java.util.List.of("[FORWARD] Field removed: removed"),
        forward.breakingChanges());
    assertEquals(CheckStatus.FAIL, forward.status());
  }

  @Test
  void forwardRequiredFieldRemovalUsesFieldRemovedRule() throws IOException {
    Path base = schema(
        "forward-required-removal-base.json", null,
        "\"id\":{\"type\":\"string\"},\"removed\":{\"type\":\"string\"}",
        ",\"required\":[\"removed\"]");
    Path candidate = schema(
        "forward-required-removal-candidate.json", null,
        "\"id\":{\"type\":\"string\"}", "");

    CompatibilityResult forward = engine.checkCompatibility(
        base, candidate, CompatibilityMode.FORWARD);

    assertEquals(CheckStatus.FAIL, forward.status());
    assertEquals(
        java.util.List.of("[FORWARD] Field removed: removed"),
        forward.breakingChanges());
  }

  private Path schema(
      String name,
      String additionalProperties,
      String properties,
      String suffix) throws IOException {
    String profile = additionalProperties == null
        ? ""
        : ",\"additionalProperties\":" + additionalProperties;
    return write(
        name,
        "{\"type\":\"object\"" + profile + ",\"properties\":{" + properties + "}" + suffix + "}");
  }

  private Path write(String name, String contents) throws IOException {
    Path path = tempDir.resolve(name);
    Files.writeString(path, contents);
    return path;
  }
}
