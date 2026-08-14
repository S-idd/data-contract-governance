package com.ideas.contracts.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideas.contracts.sdk.ContractValidationClient;
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

class DemoCheckSubmissionServiceTest {
  @Test
  void submitsBreakingScenarioThroughTheSdkClient() {
    RecordingHttpClient httpClient = new RecordingHttpClient();
    DemoDcgProperties properties = new DemoDcgProperties();
    properties.setServiceBaseUrl("http://sdk.test/");
    ContractValidationClient client = new ContractValidationClient(
        properties.getServiceBaseUrl(), httpClient, new ObjectMapper());
    DemoCheckSubmissionService service = new DemoCheckSubmissionService(client, properties);

    DemoCheckSubmission submission = service.submit(DemoCheckScenario.BREAKING);

    assertEquals("run-breaking", submission.runId());
    assertEquals("v2", submission.baseVersion());
    assertEquals("v3", submission.candidateVersion());
    assertEquals("http://sdk.test/ui/checks/run-breaking", submission.checkUrl());
    assertEquals("POST", httpClient.request.method());
    assertEquals("/checks", httpClient.request.uri().getPath());
  }

  private static final class RecordingHttpClient extends HttpClient {
    private HttpRequest request;

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
      this.request = request;
      @SuppressWarnings("unchecked")
      HttpResponse<T> response = (HttpResponse<T>) new StubHttpResponse(
          202,
          "{\"runId\":\"run-breaking\",\"status\":\"QUEUED\"}");
      return response;
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

  private record StubHttpResponse(int statusCode, String body) implements HttpResponse<String> {
    @Override
    public HttpRequest request() {
      return HttpRequest.newBuilder().uri(URI.create("http://sdk.test/checks")).build();
    }

    @Override
    public Optional<HttpResponse<String>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (key, value) -> true);
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return URI.create("http://sdk.test/checks");
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }
}
