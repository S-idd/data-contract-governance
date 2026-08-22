package com.ideas.contracts.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineCompatibilityWorkflowTest {
  @TempDir
  Path tempDir;

  @Test
  void runsOfflineWritesEvidenceAndDoesNotRequireTheService() throws IOException {
    CompatibilityBuildResult result = new OfflineCompatibilityWorkflow().run(request(
        validSchema("base.json", "string"), validSchema("candidate.json", "string"),
        RemoteReportingMode.DISABLED, null));

    assertTrue(result.compatible());
    assertEquals(RemoteReportStatus.NOT_REQUESTED, result.remoteReportStatus());
    assertTrue(Files.readString(result.reportFile()).contains("\"localStatus\" : \"PASS\""));
  }

  @Test
  void writesEvidenceBeforeAnOptionalRemoteFailure() throws IOException {
    CompatibilityBuildResult result = new OfflineCompatibilityWorkflow().run(request(
        validSchema("base.json", "string"), validSchema("candidate.json", "integer"),
        RemoteReportingMode.OPTIONAL, "http://127.0.0.1:1"));

    assertFalse(result.compatible());
    assertEquals(RemoteReportStatus.FAILED_OPTIONAL, result.remoteReportStatus());
    assertTrue(Files.readString(result.reportFile()).contains("\"localStatus\" : \"FAIL\""));
  }

  @Test
  void failsWhenRequiredRemoteFollowUpCannotBeReportedButLeavesEvidence() throws IOException {
    Path report = tempDir.resolve("required.json");
    CompatibilityBuildRequest request = new CompatibilityBuildRequest(
        validSchema("base.json", "string"), validSchema("candidate.json", "string"),
        com.ideas.contracts.core.CompatibilityMode.BACKWARD, report, "orders.created", "abc", "test",
        "http://127.0.0.1:1", RemoteReportingMode.REQUIRED, Duration.ofMillis(200), 1,
        "test-ci", null, null);

    assertThrows(RuntimeException.class, () -> new OfflineCompatibilityWorkflow().run(request));
    assertTrue(Files.exists(report));
    assertTrue(Files.readString(report).contains("\"localStatus\""));
  }

  @Test
  void submitsRemoteFollowUpOnlyAfterTheLocalCheckAndRecordsAcceptance() throws Exception {
    try (TestServer server = new TestServer()) {
      CompatibilityBuildResult result = new OfflineCompatibilityWorkflow().run(request(
          validSchema("v1.json", "string"), validSchema("v2.json", "string"),
          RemoteReportingMode.OPTIONAL, server.url()));

      assertEquals(RemoteReportStatus.ACCEPTED, result.remoteReportStatus());
      assertEquals("POST", server.method);
      assertTrue(server.body.contains("\"contractId\" : \"orders.created\""));
      assertTrue(server.body.contains("\"baseVersion\" : \"v1\""));
    }
  }

  @Test
  void replaysTheExactArtifactWithTheConfiguredAuthenticationHeader() throws Exception {
    Path evidence = tempDir.resolve("replay.json");
    String original = "{\"evidenceFormatVersion\":\"1.0\",\"idempotencyKey\":\"replay-1\"}";
    Files.writeString(evidence, original);

    try (TestServer server = new TestServer()) {
      new EvidenceReplayWorkflow().replay(
          evidence, server.url(), "Bearer replay-token", Duration.ofMillis(300), 1);

      assertEquals(original, server.body);
      assertEquals("Bearer replay-token", server.authorization);
    }
  }

  @Test
  void expiredOrInvalidReplayCredentialsFailClearlyWithoutRetryingTheArtifact() throws Exception {
    Path evidence = tempDir.resolve("expired-replay.json");
    Files.writeString(evidence, "{\"evidenceFormatVersion\":\"1.0\",\"idempotencyKey\":\"replay-expired\"}");

    try (TestServer server = new TestServer(401)) {
      RemoteAuthenticationException exception = assertThrows(RemoteAuthenticationException.class,
          () -> new EvidenceReplayWorkflow().replay(
              evidence, server.url(), "Bearer expired-token", Duration.ofMillis(300), 3));

      assertTrue(exception.getMessage().contains("fresh CI-issued token"));
      assertEquals(1, server.requestCount);
      assertEquals("Bearer expired-token", server.authorization);
    }
  }

  @Test
  void honorsRetryAfterBeforeRetryingA429Response() throws Exception {
    Path evidence = tempDir.resolve("rate-limited-replay.json");
    Files.writeString(evidence, "{\"evidenceFormatVersion\":\"1.0\",\"idempotencyKey\":\"replay-limited\"}");

    try (TestServer server = new TestServer(429, 202, "1")) {
      long startedAt = System.nanoTime();
      new EvidenceReplayWorkflow().replay(
          evidence, server.url(), "Bearer fresh-token", Duration.ofSeconds(3), 2);
      long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

      assertEquals(2, server.requestCount);
      assertTrue(elapsedMillis >= 900, "client must honor Retry-After rather than its short generic backoff");
    }
  }

  private CompatibilityBuildRequest request(
      Path base, Path candidate, RemoteReportingMode reportingMode, String remoteUrl) {
    return new CompatibilityBuildRequest(
        base, candidate, com.ideas.contracts.core.CompatibilityMode.BACKWARD,
        tempDir.resolve(base.getFileName() + "-report.json"), "orders.created", "abc", "test",
        remoteUrl, reportingMode, Duration.ofMillis(300), 1, "test-ci", null, null);
  }

  private Path validSchema(String fileName, String type) throws IOException {
    Path file = tempDir.resolve(fileName);
    Files.writeString(file, "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"" + type + "\"}}}");
    return file;
  }

  private static final class TestServer implements AutoCloseable {
    private final HttpServer server;
    private final int firstStatus;
    private final int subsequentStatus;
    private final String retryAfter;
    private volatile String method;
    private volatile String body;
    private volatile String authorization;
    private volatile int requestCount;

    private TestServer() throws IOException {
      this(202);
    }

    private TestServer(int status) throws IOException {
      this(status, status, null);
    }

    private TestServer(int firstStatus, int subsequentStatus, String retryAfter) throws IOException {
      this.firstStatus = firstStatus;
      this.subsequentStatus = subsequentStatus;
      this.retryAfter = retryAfter;
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/checks/evidence", exchange -> {
        requestCount++;
        method = exchange.getRequestMethod();
        body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        authorization = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] response = "{\"evidenceId\":\"evidence-1\",\"importStatus\":\"UNVERIFIED\"}".getBytes(StandardCharsets.UTF_8);
        int status = requestCount == 1 ? firstStatus : subsequentStatus;
        if (status == 429 && retryAfter != null) {
          exchange.getResponseHeaders().set("Retry-After", retryAfter);
        }
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
      });
      server.start();
    }

    private String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
