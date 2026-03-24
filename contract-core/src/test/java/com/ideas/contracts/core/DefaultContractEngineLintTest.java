package com.ideas.contracts.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultContractEngineLintTest {
  private final DefaultContractEngine engine = new DefaultContractEngine();

  @TempDir
  Path tempDir;

  @Test
  void lintPassesForValidContractFolder() throws IOException {
    Path contractDir = tempDir.resolve("orders.created");
    Files.createDirectories(contractDir);
    Files.writeString(
        contractDir.resolve("metadata.yaml"),
        "ownerTeam: platform\ndomain: commerce\ncompatibilityMode: BACKWARD\n");
    Files.writeString(contractDir.resolve("v1.json"), "{\"type\":\"object\",\"properties\":{}}");

    assertDoesNotThrow(() -> engine.lint(contractDir));
  }

  @Test
  void lintFailsWhenMetadataMissing() throws IOException {
    Path contractDir = tempDir.resolve("orders.created");
    Files.createDirectories(contractDir);
    Files.writeString(contractDir.resolve("v1.json"), "{\"type\":\"object\"}");

    assertThrows(SchemaValidationException.class, () -> engine.lint(contractDir));
  }

  @Test
  void lintFailsWhenNoVersionedSchemaExists() throws IOException {
    Path contractDir = tempDir.resolve("orders.created");
    Files.createDirectories(contractDir);
    Files.writeString(
        contractDir.resolve("metadata.yaml"),
        "ownerTeam: platform\ndomain: commerce\ncompatibilityMode: BACKWARD\n");
    Files.writeString(contractDir.resolve("schema.json"), "{\"type\":\"object\"}");

    assertThrows(SchemaValidationException.class, () -> engine.lint(contractDir));
  }

  @Test
  void lintFailsWhenSchemaIsInvalidJson() throws IOException {
    Path contractDir = tempDir.resolve("orders.created");
    Files.createDirectories(contractDir);
    Files.writeString(
        contractDir.resolve("metadata.yaml"),
        "ownerTeam: platform\ndomain: commerce\ncompatibilityMode: BACKWARD\n");
    Files.writeString(contractDir.resolve("v1.json"), "{invalid-json}");

    assertThrows(SchemaValidationException.class, () -> engine.lint(contractDir));
  }

  @Test
  void lintFailsForInvalidContractDirectoryName() throws IOException {
    Path contractDir = tempDir.resolve("Orders-Created");
    Files.createDirectories(contractDir);
    Files.writeString(
        contractDir.resolve("metadata.yaml"),
        "ownerTeam: platform\ndomain: commerce\ncompatibilityMode: BACKWARD\n");
    Files.writeString(contractDir.resolve("v1.json"), "{\"type\":\"object\"}");

    assertThrows(SchemaValidationException.class, () -> engine.lint(contractDir));
  }

  @Test
  void lintFailsWhenMetadataIsMissingRequiredFields() throws IOException {
    Path contractDir = tempDir.resolve("orders.created");
    Files.createDirectories(contractDir);
    Files.writeString(contractDir.resolve("metadata.yaml"), "ownerTeam: platform\ncompatibilityMode: BACKWARD\n");
    Files.writeString(contractDir.resolve("v1.json"), "{\"type\":\"object\",\"properties\":{}}");

    assertThrows(SchemaValidationException.class, () -> engine.lint(contractDir));
  }

  @Test
  void lintFailsWhenCompatibilityModeIsInvalid() throws IOException {
    Path contractDir = tempDir.resolve("orders.created");
    Files.createDirectories(contractDir);
    Files.writeString(
        contractDir.resolve("metadata.yaml"),
        "ownerTeam: platform\ndomain: commerce\ncompatibilityMode: SIDEWAYS\n");
    Files.writeString(contractDir.resolve("v1.json"), "{\"type\":\"object\",\"properties\":{}}");

    assertThrows(SchemaValidationException.class, () -> engine.lint(contractDir));
  }
}
