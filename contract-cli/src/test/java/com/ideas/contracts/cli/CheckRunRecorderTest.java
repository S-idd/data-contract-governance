package com.ideas.contracts.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideas.contracts.core.CheckStatus;
import com.ideas.contracts.core.CompatibilityResult;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckRunRecorderTest {
  @TempDir
  Path tempDir;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void recordEmitsStructuredLogWhenDbWriteFails() throws Exception {
    Path unavailableDbPath = tempDir.resolve("checks-directory");
    Files.createDirectories(unavailableDbPath);

    CompatibilityResult result = new CompatibilityResult(CheckStatus.PASS, List.of(), List.of());
    CheckRunRecorder recorder = new CheckRunRecorder();

    ByteArrayOutputStream errCapture = new ByteArrayOutputStream();
    PrintStream originalErr = System.err;

    try (PrintStream replacementErr = new PrintStream(errCapture, true, StandardCharsets.UTF_8)) {
      System.setErr(replacementErr);
      IllegalStateException exception = assertThrows(
          IllegalStateException.class,
          () -> recorder.record(unavailableDbPath, "orders.created", "v1", "v2", result, "sha-123"));
      assertTrue(exception.getMessage().contains("Failed to record check run in database target"));
    } finally {
      System.setErr(originalErr);
    }

    String rawLog = errCapture.toString(StandardCharsets.UTF_8);
    Optional<String> jsonLog = rawLog.lines()
        .filter(line -> line.contains("\"event\":\"db_operation_failed\""))
        .findFirst();
    assertTrue(jsonLog.isPresent());

    JsonNode logNode = objectMapper.readTree(jsonLog.orElseThrow());
    assertEquals("db_operation_failed", logNode.get("event").asText());
    assertEquals("check_run_recorder", logNode.get("component").asText());
    assertEquals("record_check_run", logNode.get("operation").asText());
    assertEquals(unavailableDbPath.toAbsolutePath().toString(), logNode.get("dbTarget").asText());
    assertEquals(unavailableDbPath.toAbsolutePath().toString(), logNode.get("dbPath").asText());
    assertEquals("orders.created", logNode.get("contractId").asText());
    assertEquals("v1", logNode.get("baseVersion").asText());
    assertEquals("v2", logNode.get("candidateVersion").asText());
    assertEquals("sha-123", logNode.get("commitSha").asText());
    assertTrue(logNode.has("sqlState"));
    assertTrue(logNode.has("sqlVendorCode"));
    assertTrue(logNode.get("errorType").asText().endsWith("Exception"));
    assertTrue(!logNode.get("timestamp").asText().isBlank());
  }
}
