package com.ideas.contracts.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class ContractValidationClientTest {
  @Test
  void sdkSubmitsChecksAndFetchesResults() {
    StubHttpClient httpClient = new StubHttpClient(Map.of(
        "POST /checks",
        new StubHttpResponse(
            202,
            """
            {"runId":"run-1","status":"QUEUED"}
            """),
        "GET /checks/run-1",
        new StubHttpResponse(
            200,
            """
            {
              "runId":"run-1",
              "contractId":"orders.created",
              "baseVersion":"v1",
              "candidateVersion":"v2",
              "status":"PASS",
              "breakingChanges":[],
              "warnings":[],
              "commitSha":"sha-1",
              "createdAt":"2026-03-24T10:00:00Z",
              "triggeredBy":"sdk-test",
              "startedAt":"2026-03-24T10:00:00Z",
              "finishedAt":"2026-03-24T10:00:01Z",
              "executionState":"SUCCESS"
            }
            """),
        "GET /runs/run-1/logs",
        new StubHttpResponse(
            200,
            """
            [
              {
                "logId":"log-1",
                "runId":"run-1",
                "level":"INFO",
                "message":"Check run completed.",
                "createdAt":"2026-03-24T10:00:01Z"
              }
            ]
            """)));
    ContractValidationClient client = new ContractValidationClient("http://sdk.test", httpClient, null);

    SubmitCheckResponse submit = client.submitCheck(new SubmitCheckRequest(
        "orders.created",
        "v1",
        "v2",
        "BACKWARD",
        "sha-1",
        "sdk-test"));
    assertEquals("run-1", submit.runId());
    assertEquals("QUEUED", submit.status());

    CheckRun checkRun = client.getCheck("run-1");
    assertEquals("orders.created", checkRun.contractId());
    assertEquals("SUCCESS", checkRun.executionState());

    List<RunLog> logs = client.getRunLogs("run-1");
    assertFalse(logs.isEmpty());
    assertEquals("INFO", logs.get(0).level());
  }

  private static final class StubHttpClient extends HttpClient {
    private final Map<String, StubHttpResponse> responses;

    private StubHttpClient(Map<String, StubHttpResponse> responses) {
      this.responses = responses;
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      return null;
    }

    @Override
    public SSLParameters sslParameters() {
      return new SSLParameters();
    }

    @Override
    public Optional<java.net.Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
      String key = request.method() + " " + request.uri().getPath();
      StubHttpResponse response = responses.get(key);
      if (response == null) {
        throw new IllegalStateException("No stubbed response for request: " + key);
      }
      @SuppressWarnings("unchecked")
      HttpResponse<T> cast = (HttpResponse<T>) response;
      return cast;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> responseBodyHandler) {
      return CompletableFuture.completedFuture(send(request, responseBodyHandler));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> responseBodyHandler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      return CompletableFuture.completedFuture(send(request, responseBodyHandler));
    }
  }

  private static final class StubHttpResponse implements HttpResponse<String> {
    private final int statusCode;
    private final String body;

    private StubHttpResponse(int statusCode, String body) {
      this.statusCode = statusCode;
      this.body = body;
    }

    @Override
    public int statusCode() {
      return statusCode;
    }

    @Override
    public HttpRequest request() {
      return HttpRequest.newBuilder().uri(URI.create("http://sdk.test")).build();
    }

    @Override
    public Optional<HttpResponse<String>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (k, v) -> true);
    }

    @Override
    public String body() {
      return body;
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return URI.create("http://sdk.test");
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }
}
