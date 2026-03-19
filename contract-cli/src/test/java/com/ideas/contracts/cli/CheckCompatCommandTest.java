package com.ideas.contracts.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ideas.contracts.core.CompatibilityResult;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CheckCompatCommandTest {
  @TempDir
  Path tempDir;

  @Test
  void jdbcRecordUsesEnvCredentialsWhenOptionNamesProvided() throws Exception {
    Path baseSchema = writeSchema("v1.json");
    Path candidateSchema = writeSchema("v2.json");
    RecordingCheckRunRecorder recorder = new RecordingCheckRunRecorder();
    TestableCheckCompatCommand command =
        new TestableCheckCompatCommand(
            Map.of("DB_USER_ENV", "env-user", "DB_PASSWORD_ENV", "env-password"),
            recorder);

    int exitCode = new CommandLine(command).execute(
        "--base",
        baseSchema.toString(),
        "--candidate",
        candidateSchema.toString(),
        "--mode",
        "BACKWARD",
        "--record-jdbc-url",
        "jdbc:postgresql://localhost:5432/contracts",
        "--record-db-user-env",
        "DB_USER_ENV",
        "--record-db-password-env",
        "DB_PASSWORD_ENV",
        "--contract-id",
        "orders.created",
        "--commit-sha",
        "sha-env");

    assertEquals(0, exitCode);
    assertTrue(recorder.invoked);
    assertEquals("env-user", recorder.username);
    assertEquals("env-password", recorder.password);
    assertEquals("orders.created", recorder.contractId);
    assertEquals("sha-env", recorder.commitSha);
  }

  @Test
  void explicitCredentialsOverrideEnvironmentVariableOptions() throws Exception {
    Path baseSchema = writeSchema("v1.json");
    Path candidateSchema = writeSchema("v2.json");
    RecordingCheckRunRecorder recorder = new RecordingCheckRunRecorder();
    TestableCheckCompatCommand command =
        new TestableCheckCompatCommand(
            Map.of("DB_USER_ENV", "env-user", "DB_PASSWORD_ENV", "env-password"),
            recorder);

    int exitCode = new CommandLine(command).execute(
        "--base",
        baseSchema.toString(),
        "--candidate",
        candidateSchema.toString(),
        "--mode",
        "BACKWARD",
        "--record-jdbc-url",
        "jdbc:postgresql://localhost:5432/contracts",
        "--record-db-user",
        "direct-user",
        "--record-db-password",
        "direct-password",
        "--record-db-user-env",
        "DB_USER_ENV",
        "--record-db-password-env",
        "DB_PASSWORD_ENV",
        "--contract-id",
        "orders.created");

    assertEquals(0, exitCode);
    assertTrue(recorder.invoked);
    assertEquals("direct-user", recorder.username);
    assertEquals("direct-password", recorder.password);
  }

  @Test
  void missingEnvCredentialReturnsExitCodeTwoWithClearMessage() throws Exception {
    Path baseSchema = writeSchema("v1.json");
    Path candidateSchema = writeSchema("v2.json");
    RecordingCheckRunRecorder recorder = new RecordingCheckRunRecorder();
    TestableCheckCompatCommand command = new TestableCheckCompatCommand(Map.of(), recorder);

    ByteArrayOutputStream errCapture = new ByteArrayOutputStream();
    PrintStream originalErr = System.err;
    int exitCode;
    try (PrintStream replacementErr = new PrintStream(errCapture, true, StandardCharsets.UTF_8)) {
      System.setErr(replacementErr);
      exitCode = new CommandLine(command).execute(
          "--base",
          baseSchema.toString(),
          "--candidate",
          candidateSchema.toString(),
          "--mode",
          "BACKWARD",
          "--record-jdbc-url",
          "jdbc:postgresql://localhost:5432/contracts",
          "--record-db-password-env",
          "MISSING_DB_PASSWORD",
          "--contract-id",
          "orders.created");
    } finally {
      System.setErr(originalErr);
    }

    assertEquals(2, exitCode);
    assertTrue(!recorder.invoked);
    assertTrue(
        errCapture.toString(StandardCharsets.UTF_8)
            .contains("Environment variable 'MISSING_DB_PASSWORD' configured via --record-db-password-env is not set or blank."));
  }

  private Path writeSchema(String fileName) throws Exception {
    Path path = tempDir.resolve(fileName);
    Files.writeString(path, """
        {
          "type": "object",
          "properties": {
            "orderId": {"type": "string"}
          }
        }
        """);
    return path;
  }

  private static final class RecordingCheckRunRecorder extends CheckRunRecorder {
    private boolean invoked;
    private String username;
    private String password;
    private String contractId;
    private String commitSha;

    @Override
    public void record(
        String jdbcUrl,
        String username,
        String password,
        String contractId,
        String baseVersion,
        String candidateVersion,
        CompatibilityResult result,
        String commitSha,
        com.ideas.contracts.core.CompatibilityMode mode) {
      this.invoked = true;
      this.username = username;
      this.password = password;
      this.contractId = contractId;
      this.commitSha = commitSha;
    }
  }

  private static final class TestableCheckCompatCommand extends CheckCompatCommand {
    private final Map<String, String> environment;
    private final RecordingCheckRunRecorder recorder;

    private TestableCheckCompatCommand(
        Map<String, String> environment,
        RecordingCheckRunRecorder recorder) {
      this.environment = new HashMap<>(environment);
      this.recorder = recorder;
    }

    @Override
    protected CheckRunRecorder createRecorder() {
      return recorder;
    }

    @Override
    protected String readEnvironmentVariable(String key) {
      return environment.get(key);
    }
  }
}
