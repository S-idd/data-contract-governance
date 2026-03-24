package com.ideas.contracts.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class InitCiCommandTest {
  @TempDir
  Path tempDir;

  @Test
  void initCiCreatesWorkflowFile() throws Exception {
    Path output = tempDir.resolve(".github/workflows/contract-validation.yml");

    int exitCode = new CommandLine(new InitCiCommand()).execute("--output", output.toString());

    assertEquals(0, exitCode);
    assertTrue(Files.exists(output));
    String content = Files.readString(output);
    assertTrue(content.contains("name: Contract Validation"));
    assertTrue(content.contains("Validate Changed Contracts"));
  }
}
