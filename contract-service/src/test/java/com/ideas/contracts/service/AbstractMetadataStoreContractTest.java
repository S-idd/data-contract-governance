package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ideas.contracts.service.model.CheckRunCreateRequest;
import com.ideas.contracts.service.model.CheckRunCreateResponse;
import com.ideas.contracts.service.model.CheckRunLogResponse;
import com.ideas.contracts.service.model.CheckRunPageResponse;
import com.ideas.contracts.service.model.CheckRunResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

abstract class AbstractMetadataStoreContractTest {

  @Test
  void queueLifecycleAndLogsAreConsistentAcrossBackends() throws Exception {
    try (MetadataStoreFixture fixture = createFixture()) {
      MetadataStore store = fixture.store();
      CheckRunCreateResponse created = store.createQueuedRun(new CheckRunCreateRequest(
          "orders.created",
          "v1",
          "v2",
          "BACKWARD",
          "commit-lifecycle",
          "contract-test"));

      MetadataStore.QueuedCheckRun claimed = store.claimNextQueuedRun().orElseThrow();
      assertEquals(created.runId(), claimed.runId());
      assertEquals("orders.created", claimed.contractId());

      store.appendLog(created.runId(), "INFO", "claimed for execution");
      store.appendLog(created.runId(), "WARN", "warning recorded");

      assertTrue(store.completeRun(
          created.runId(),
          "PASS",
          List.of(),
          List.of("Enum value added: status.SHIPPED")));

      CheckRunResponse completed = store.findByRunId(created.runId()).orElseThrow();
      assertEquals("PASS", completed.status());
      assertEquals(List.of("Enum value added: status.SHIPPED"), completed.warnings());

      List<CheckRunLogResponse> logs = store.listLogs(created.runId());
      assertEquals(2, logs.size());
      assertEquals("claimed for execution", logs.get(0).message());
      assertEquals("warning recorded", logs.get(1).message());
    }
  }

  @Test
  void paginationAndStatusFiltersRemainConsistentAcrossBackends() throws Exception {
    try (MetadataStoreFixture fixture = createFixture()) {
      MetadataStore store = fixture.store();

      String passRun1 = createAndComplete(store, "PASS", "sha-1");
      String failRun = createAndComplete(store, "FAIL", "sha-2");
      String passRun2 = createAndComplete(store, "PASS", "sha-3");

      CheckRunPageResponse firstPage = store.listPage(CheckRunQuery.from("orders.created", null, null, 2, 0));
      assertEquals(2, firstPage.items().size());
      assertTrue(firstPage.hasMore());

      CheckRunPageResponse passOnly = store.listPage(CheckRunQuery.from("orders.created", null, "PASS", 10, 0));
      assertEquals(2, passOnly.items().size());
      assertTrue(passOnly.items().stream().allMatch(item -> "PASS".equals(item.status())));

      List<CheckRunResponse> commitFiltered = store.list("orders.created", "sha-2");
      assertEquals(1, commitFiltered.size());
      assertEquals(failRun, commitFiltered.get(0).runId());

      List<String> runIds = store.list("orders.created", null).stream().map(CheckRunResponse::runId).toList();
      assertTrue(runIds.contains(passRun1));
      assertTrue(runIds.contains(failRun));
      assertTrue(runIds.contains(passRun2));
    }
  }

  @Test
  void requeueAndTerminalTransitionsAreConsistentAcrossBackends() throws Exception {
    try (MetadataStoreFixture fixture = createFixture()) {
      MetadataStore store = fixture.store();
      CheckRunCreateResponse created = store.createQueuedRun(new CheckRunCreateRequest(
          "orders.created",
          "v1",
          "v2",
          "BACKWARD",
          "commit-requeue",
          "contract-test"));

      MetadataStore.QueuedCheckRun firstClaim = store.claimNextQueuedRun().orElseThrow();
      assertEquals(created.runId(), firstClaim.runId());

      assertTrue(store.requeueRun(created.runId()));

      MetadataStore.QueuedCheckRun secondClaim = store.claimNextQueuedRun().orElseThrow();
      assertEquals(created.runId(), secondClaim.runId());

      assertTrue(store.completeRun(created.runId(), "FAIL", List.of("Field removed: amount"), List.of()));
      assertFalse(store.completeRun(created.runId(), "PASS", List.of(), List.of()));
    }
  }

  protected abstract MetadataStoreFixture createFixture() throws Exception;

  private String createAndComplete(MetadataStore store, String status, String commitSha) {
    CheckRunCreateResponse created = store.createQueuedRun(new CheckRunCreateRequest(
        "orders.created",
        "v1",
        "v2",
        "BACKWARD",
        commitSha,
        "contract-test"));
    store.claimNextQueuedRun().orElseThrow();
    boolean updated = store.completeRun(created.runId(), status, List.of(), List.of());
    if (!updated) {
      throw new IllegalStateException("Unable to complete run in contract test: " + created.runId());
    }
    return created.runId();
  }

  protected interface MetadataStoreFixture extends AutoCloseable {
    MetadataStore store();

    @Override
    void close() throws Exception;
  }
}
