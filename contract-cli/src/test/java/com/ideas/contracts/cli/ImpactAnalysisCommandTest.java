package com.ideas.contracts.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ImpactAnalysisCommandTest {
  @TempDir
  Path tempDir;

  @Test
  void impactAnalysisUsesConsumersAndLatestBreakingChange() throws Exception {
    Path contractsRoot = tempDir.resolve("contracts");
    Path contractDir = contractsRoot.resolve("orders.created");
    Files.createDirectories(contractDir);
    Files.writeString(
        contractDir.resolve("metadata.yaml"),
        """
        ownerTeam: platform
        domain: commerce
        compatibilityMode: BACKWARD
        consumers:
          - payment-service
          - analytics-service
        """
    );

    Path dbPath = tempDir.resolve("checks.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath;
    Flyway.configure()
        .dataSource(jdbcUrl, null, null)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion(MigrationVersion.fromVersion("0"))
        .load()
        .migrate();

    try (Connection connection = DriverManager.getConnection(jdbcUrl);
         PreparedStatement insert = connection.prepareStatement("""
             INSERT INTO check_runs (
               run_id, contract_id, base_version, candidate_version, status,
               breaking_changes, warnings, commit_sha, created_at,
               triggered_by, compatibility_mode, input_hash, started_at, finished_at
             ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
             """)) {
      insert.setString(1, "run-impact-1");
      insert.setString(2, "orders.created");
      insert.setString(3, "v1");
      insert.setString(4, "v2");
      insert.setString(5, "FAIL");
      insert.setString(6, "[\"Field removed: userId\"]");
      insert.setString(7, "[]");
      insert.setString(8, "sha-test");
      insert.setString(9, "2026-03-24T10:00:00Z");
      insert.setString(10, "cli");
      insert.setString(11, "BACKWARD");
      insert.setString(12, "hash");
      insert.setString(13, "2026-03-24T10:00:00Z");
      insert.setString(14, "2026-03-24T10:00:01Z");
      insert.executeUpdate();
    }

    ByteArrayOutputStream outCapture = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    int exitCode;
    try (PrintStream replacementOut = new PrintStream(outCapture, true, StandardCharsets.UTF_8)) {
      System.setOut(replacementOut);
      exitCode = new CommandLine(new ImpactAnalysisCommand()).execute(
          "--contract",
          "orders.created",
          "--version",
          "v2",
          "--contracts-root",
          contractsRoot.toString(),
          "--db",
          dbPath.toString());
    } finally {
      System.setOut(originalOut);
    }

    String output = outCapture.toString(StandardCharsets.UTF_8);
    assertEquals(1, exitCode);
    assertTrue(output.contains("payment-service"));
    assertTrue(output.contains("analytics-service"));
    assertTrue(output.contains("Risk Level: HIGH"));
    assertTrue(output.contains("Field removed: userId"));
  }
}
