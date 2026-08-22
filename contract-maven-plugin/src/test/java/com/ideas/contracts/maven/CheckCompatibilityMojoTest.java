package com.ideas.contracts.maven;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckCompatibilityMojoTest {
  @TempDir
  Path tempDir;

  @Test
  void runsOfflineAndWritesTheConfiguredEvidenceReport() throws Exception {
    CheckCompatibilityMojo mojo = configuredMojo("string", "string");

    mojo.execute();

    assertTrue(Files.readString(tempDir.resolve("report.json")).contains("\"localStatus\" : \"PASS\""));
  }

  @Test
  void failsTheBuildForAnIncompatibleLocalSchema() throws Exception {
    CheckCompatibilityMojo mojo = configuredMojo("string", "integer");

    assertThrows(MojoFailureException.class, mojo::execute);
    assertTrue(Files.readString(tempDir.resolve("report.json")).contains("\"localStatus\" : \"FAIL\""));
  }

  private CheckCompatibilityMojo configuredMojo(String baseType, String candidateType) throws Exception {
    Path base = writeSchema("base.json", baseType);
    Path candidate = writeSchema("candidate.json", candidateType);
    CheckCompatibilityMojo mojo = new CheckCompatibilityMojo();
    set(mojo, "baseSchema", base.toFile());
    set(mojo, "candidateSchema", candidate.toFile());
    set(mojo, "mode", "BACKWARD");
    set(mojo, "reportFile", tempDir.resolve("report.json").toFile());
    set(mojo, "remoteReportingMode", "DISABLED");
    set(mojo, "remoteTimeoutSeconds", 5L);
    set(mojo, "remoteMaxAttempts", 1);
    return mojo;
  }

  private Path writeSchema(String name, String type) throws IOException {
    Path schema = tempDir.resolve(name);
    Files.writeString(schema, "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"" + type + "\"}}}");
    return schema;
  }

  private void set(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
