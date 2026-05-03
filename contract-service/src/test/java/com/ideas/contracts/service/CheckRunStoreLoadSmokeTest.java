package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ideas.contracts.service.model.CheckRunCreateRequest;
import com.ideas.contracts.service.model.CheckRunPageResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckRunStoreLoadSmokeTest {
  private static final int RUN_COUNT = 120;

  @TempDir
  Path tempDir;

  @Test
  void sqliteCheckStoreHandlesBetaLoadSmokeLifecycle() {
    CheckRunStore store = new CheckRunStore(sqliteProperties(tempDir.resolve("checks-load-smoke.db")));

    try {
      store.initialize();
      for (int index = 0; index < RUN_COUNT; index++) {
        store.createQueuedRun(new CheckRunCreateRequest(
            "orders.created",
            "v1",
            "v2",
            "BACKWARD",
            "load-smoke-" + index,
            "week10-load-smoke"));
      }

      int completed = 0;
      while (true) {
        MetadataStore.QueuedCheckRun claimed = store.claimNextQueuedRun().orElse(null);
        if (claimed == null) {
          break;
        }
        store.appendLog(claimed.runId(), "INFO", "load smoke claimed");
        assertTrue(store.completeRun(claimed.runId(), "PASS", List.of(), List.of()));
        completed++;
      }

      assertEquals(RUN_COUNT, completed);
      CheckRunPageResponse page = store.listPage(CheckRunQuery.from("orders.created", null, "PASS", 25, 0));
      assertEquals(25, page.items().size());
      assertTrue(page.hasMore());
      assertEquals(RUN_COUNT, store.list("orders.created", null).stream()
          .filter(run -> "PASS".equals(run.status()))
          .count());
    } finally {
      store.shutdown();
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
    return properties;
  }
}
