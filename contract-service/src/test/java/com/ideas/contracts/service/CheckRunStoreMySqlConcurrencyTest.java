package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ideas.contracts.service.model.CheckRunCreateRequest;
import com.ideas.contracts.service.model.CheckRunCreateResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MySqlDatabaseCleanupExtension.class)
class CheckRunStoreMySqlConcurrencyTest {
  private static final String BASE_JDBC_URL = MySqlTestSupport.localJdbcUrl();
  private static final String USERNAME = MySqlTestSupport.localUsername();
  private static final String PASSWORD = MySqlTestSupport.localPassword();

  @Test
  void sharedRateLimitDoesNotExceedLimitAcrossIndependentStores() throws Exception {
    MySqlTestSupport.assumeLocalMySqlAvailable();
    String database = MySqlTestSupport.randomDatabase("unit_rate_limit_concurrency");
    String jdbcUrl = MySqlTestSupport.withDatabase(BASE_JDBC_URL, database);
    MySqlTestSupport.createDatabase(BASE_JDBC_URL, USERNAME, PASSWORD, database);

    CheckRunStore first = newStore(jdbcUrl);
    CheckRunStore second = newStore(jdbcUrl);
    try {
      first.initialize();
      second.initialize();

      int limit = 4;
      int attempts = 8;
      Instant now = Instant.parse("2026-08-22T08:00:01Z");
      Instant windowStart = Instant.parse("2026-08-22T08:00:00Z");
      String bucketKey = "evidence:" + "a".repeat(64);
      CountDownLatch ready = new CountDownLatch(attempts);
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService executor = Executors.newFixedThreadPool(attempts);
      try {
        List<Future<EvidenceRateLimitDecision>> decisions = new ArrayList<>();
        for (int index = 0; index < attempts; index++) {
          CheckRunStore store = index % 2 == 0 ? first : second;
          decisions.add(executor.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return store.tryAcquireEvidenceRateLimit(
                bucketKey, "fixed-60s", windowStart, limit, now);
          }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();

        int allowed = 0;
        for (Future<EvidenceRateLimitDecision> decision : decisions) {
          if (decision.get(10, TimeUnit.SECONDS).allowed()) {
            allowed++;
          }
        }
        assertEquals(limit, allowed);
      } finally {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
      }
    } finally {
      first.shutdown();
      second.shutdown();
      MySqlTestSupport.dropDatabaseQuietly(BASE_JDBC_URL, USERNAME, PASSWORD, database);
    }
  }

  @Test
  void concurrentFailoverRetriesCreateExactlyOneRun() throws Exception {
    MySqlTestSupport.assumeLocalMySqlAvailable();
    String database = MySqlTestSupport.randomDatabase("unit_idempotency_concurrency");
    String jdbcUrl = MySqlTestSupport.withDatabase(BASE_JDBC_URL, database);
    MySqlTestSupport.createDatabase(BASE_JDBC_URL, USERNAME, PASSWORD, database);

    CheckRunStore first = newStore(jdbcUrl);
    CheckRunStore second = newStore(jdbcUrl);
    try {
      first.initialize();
      second.initialize();

      int attempts = 16;
      CheckRunCreateRequest request = new CheckRunCreateRequest(
          "orders.created", "v1", "v2", "BACKWARD", "failover-retry", "concurrency-test");
      CountDownLatch ready = new CountDownLatch(attempts);
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService executor = Executors.newFixedThreadPool(attempts);
      try {
        List<Future<CheckRunCreateResponse>> responses = new ArrayList<>();
        for (int index = 0; index < attempts; index++) {
          CheckRunStore store = index % 2 == 0 ? first : second;
          responses.add(executor.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return store.createQueuedRun(request, "managed-failover-retry-key");
          }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();

        String runId = null;
        for (Future<CheckRunCreateResponse> response : responses) {
          String returnedRunId = response.get(10, TimeUnit.SECONDS).runId();
          if (runId == null) {
            runId = returnedRunId;
          } else {
            assertEquals(runId, returnedRunId);
          }
        }
        assertEquals(1, first.list("orders.created", "failover-retry").size());
      } finally {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
      }
    } finally {
      first.shutdown();
      second.shutdown();
      MySqlTestSupport.dropDatabaseQuietly(BASE_JDBC_URL, USERNAME, PASSWORD, database);
    }
  }

  private CheckRunStore newStore(String jdbcUrl) {
    CheckStoreProperties properties = new CheckStoreProperties();
    properties.setUrl(jdbcUrl);
    properties.setUsername(USERNAME);
    properties.setPassword(PASSWORD);
    properties.setQueryTimeout(Duration.ofSeconds(3));
    properties.getPool().setMaximumSize(4);
    properties.getPool().setConnectionTimeout(Duration.ofSeconds(2));
    properties.getPool().setInitializationFailTimeout(Duration.ofMillis(-1));
    return new CheckRunStore(properties);
  }
}
