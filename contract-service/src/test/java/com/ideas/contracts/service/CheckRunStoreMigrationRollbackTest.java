package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ideas.contracts.service.model.CheckRunCreateRequest;
import com.ideas.contracts.service.model.CheckRunCreateResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckRunStoreMigrationRollbackTest {

  @TempDir
  Path tempDir;

  @Test
  void restoredBackupReturnsStoreToLastKnownGoodMigrationState() throws Exception {
    Path dbPath = tempDir.resolve("checks-rollback.db");
    Path backupPath = tempDir.resolve("checks-rollback.backup.db");
    String runId;

    CheckRunStore original = new CheckRunStore(sqliteProperties(dbPath));
    try {
      original.initialize();
      CheckRunCreateResponse created = original.createQueuedRun(new CheckRunCreateRequest(
          "orders.created",
          "v1",
          "v2",
          "BACKWARD",
          "rollback-smoke",
          "week10-rollback-smoke"));
      runId = created.runId();
      MetadataStore.QueuedCheckRun claimed = original.claimNextQueuedRun().orElseThrow();
      original.appendLog(claimed.runId(), "INFO", "rollback smoke checkpoint");
      original.completeRun(claimed.runId(), "PASS", List.of(), List.of("checkpoint ready"));
    } finally {
      original.shutdown();
    }

    Files.copy(dbPath, backupPath);
    dropTable(dbPath, "check_run_logs");

    CheckRunStore damaged = new CheckRunStore(sqliteProperties(dbPath));
    try {
      damaged.initialize();
      assertThrows(CheckRunStoreException.class, () -> damaged.listLogs(runId));
    } finally {
      damaged.shutdown();
    }

    Files.copy(backupPath, dbPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

    CheckRunStore restored = new CheckRunStore(sqliteProperties(dbPath));
    try {
      restored.initialize();
      assertEquals(1, restored.listLogs(runId).size());
      assertEquals("PASS", restored.findByRunId(runId).orElseThrow().status());
    } finally {
      restored.shutdown();
    }
  }

  private void dropTable(Path dbPath, String tableName) throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
         Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE " + tableName);
    }
  }

  private CheckStoreProperties sqliteProperties(Path dbPath) {
    CheckStoreProperties properties = new CheckStoreProperties();
    properties.setPath(dbPath.toString());
    properties.setQueryTimeout(Duration.ofSeconds(2));
    properties.getPool().setMaximumSize(1);
    properties.getPool().setMinimumIdle(1);
    properties.getPool().setConnectionTimeout(Duration.ofMillis(500));
    properties.getSqlite().setEnforceSingleNode(true);
    properties.getSqlite().setWalEnabled(false);
    return properties;
  }
}
