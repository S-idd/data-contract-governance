package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ideas.contracts.service.model.CheckRunCreateRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Explicitly invoked P1 capacity harness. It is intentionally not named *Test so normal unit
 * builds do not substitute a local benchmark for the required container/managed-environment run.
 */
class DatabaseSideBySideBenchmarkHarness {
  private static final String CONTRACT_ID = "benchmark.orders";

  @Test
  void recordsComparableStoreWriteAndReadLatencyForPostgresAndMySql() throws Exception {
    int operations = positiveInteger("DCG_BENCHMARK_OPERATIONS", 500);
    int warmupOperations = positiveInteger("DCG_BENCHMARK_WARMUP_OPERATIONS", 25);
    List<BenchmarkResult> results = List.of(
        benchmark("PostgreSQL", properties("POSTGRES"), operations, warmupOperations),
        benchmark("MySQL", properties("MYSQL"), operations, warmupOperations));
    writeReport(results, operations, warmupOperations);
  }

  private BenchmarkResult benchmark(
      String label, CheckStoreProperties properties, int operations, int warmupOperations) {
    CheckRunStore store = new CheckRunStore(properties);
    try {
      store.initialize();
      for (int operation = 0; operation < warmupOperations; operation++) {
        write(store, "warmup-" + operation);
      }

      List<Long> writeLatencies = new ArrayList<>();
      long writesStartedAt = System.nanoTime();
      for (int operation = 0; operation < operations; operation++) {
        long startedAt = System.nanoTime();
        write(store, "write-" + operation);
        writeLatencies.add(System.nanoTime() - startedAt);
      }
      long writeElapsed = System.nanoTime() - writesStartedAt;

      List<Long> readLatencies = new ArrayList<>();
      long readsStartedAt = System.nanoTime();
      for (int operation = 0; operation < operations; operation++) {
        long startedAt = System.nanoTime();
        store.listPage(new CheckRunQuery(CONTRACT_ID, null, null, 200, 0));
        readLatencies.add(System.nanoTime() - startedAt);
      }
      long readElapsed = System.nanoTime() - readsStartedAt;

      CheckRunStore.PoolSnapshot pool = store.poolSnapshot();
      assertEquals(0, pool.activeConnections(), label + " leaked an active connection");
      assertEquals(0, pool.threadsAwaitingConnection(), label + " retained a pool waiter");
      return new BenchmarkResult(label, operations, writeElapsed, writeLatencies, readElapsed, readLatencies, pool);
    } finally {
      store.shutdown();
    }
  }

  private void write(CheckRunStore store, String suffix) {
    store.createQueuedRun(new CheckRunCreateRequest(
        CONTRACT_ID, "v1", "v2", "BACKWARD", "benchmark-" + suffix, "p1-benchmark"));
  }

  private CheckStoreProperties properties(String backend) {
    CheckStoreProperties properties = new CheckStoreProperties();
    properties.setUrl(required("DCG_BENCHMARK_" + backend + "_JDBC_URL"));
    properties.setUsername(required("DCG_BENCHMARK_" + backend + "_USERNAME"));
    properties.setPassword(required("DCG_BENCHMARK_" + backend + "_PASSWORD"));
    properties.setQueryTimeout(Duration.ofSeconds(3));
    properties.getPool().setMaximumSize(10);
    properties.getPool().setMinimumIdle(2);
    properties.getPool().setConnectionTimeout(Duration.ofSeconds(3));
    properties.getPool().setInitializationFailTimeout(Duration.ofSeconds(2));
    return properties;
  }

  private void writeReport(List<BenchmarkResult> results, int operations, int warmupOperations)
      throws IOException {
    Path report = Path.of(required("DCG_BENCHMARK_REPORT_FILE"));
    Files.createDirectories(report.getParent());
    StringBuilder markdown = new StringBuilder("# DCG PostgreSQL vs MySQL capacity baseline\n\n")
        .append("- Status: PASS\n")
        .append("- Recorded at (UTC): ").append(Instant.now()).append("\n")
        .append("- Workload: ").append(operations).append(" queued-run writes and ")
        .append(operations).append(" indexed paginated reads per database after ")
        .append(warmupOperations).append(" warmup writes.\n")
        .append("- Pool: max 10, minimum idle 2, connection timeout 3 seconds.\n\n")
        .append("| Backend | Write ops/s | Write p95/p99 ms | Read ops/s | Read p95/p99 ms | Pool after run |\n")
        .append("| --- | ---: | ---: | ---: | ---: | --- |\n");
    for (BenchmarkResult result : results) {
      markdown.append(String.format(Locale.ROOT,
          "| %s | %.1f | %.2f / %.2f | %.1f | %.2f / %.2f | active=%d, waiting=%d |%n",
          result.label(), result.writeOperationsPerSecond(), result.writePercentileMillis(0.95),
          result.writePercentileMillis(0.99), result.readOperationsPerSecond(),
          result.readPercentileMillis(0.95), result.readPercentileMillis(0.99),
          result.pool().activeConnections(), result.pool().threadsAwaitingConnection()));
    }
    markdown.append("\n## Scope\n\n")
        .append("This is a reproducible local baseline, not a production capacity claim. Repeat it against ")
        .append("the selected production-like topology and add CPU, I/O, lock/deadlock, query-plan, and ")
        .append("error-rate evidence before setting acceptance budgets.\n");
    Files.writeString(report, markdown);
    System.out.println(markdown);
  }

  private String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing required benchmark environment variable: " + name);
    }
    return value.trim();
  }

  private int positiveInteger(String name, int fallback) {
    String configured = System.getenv(name);
    if (configured == null || configured.isBlank()) {
      return fallback;
    }
    int value = Integer.parseInt(configured.trim());
    assertTrue(value > 0, name + " must be positive");
    return value;
  }

  private record BenchmarkResult(
      String label,
      int operations,
      long writeElapsedNanos,
      List<Long> writeLatencies,
      long readElapsedNanos,
      List<Long> readLatencies,
      CheckRunStore.PoolSnapshot pool) {
    double writeOperationsPerSecond() {
      return operations / (writeElapsedNanos / 1_000_000_000.0);
    }

    double readOperationsPerSecond() {
      return operations / (readElapsedNanos / 1_000_000_000.0);
    }

    double writePercentileMillis(double percentile) {
      return percentileMillis(writeLatencies, percentile);
    }

    double readPercentileMillis(double percentile) {
      return percentileMillis(readLatencies, percentile);
    }

    private static double percentileMillis(List<Long> values, double percentile) {
      List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
      int index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * percentile) - 1);
      return sorted.get(index) / 1_000_000.0;
    }
  }
}
