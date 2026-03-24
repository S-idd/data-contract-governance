package com.ideas.contracts.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class ContractValidationClient {
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  private final String baseUrl;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public ContractValidationClient(String baseUrl) {
    this(baseUrl, HttpClient.newHttpClient(), new ObjectMapper());
  }

  public ContractValidationClient(String baseUrl, HttpClient httpClient, ObjectMapper objectMapper) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl must not be blank.");
    }
    this.baseUrl = trimTrailingSlash(baseUrl.trim());
    this.httpClient = httpClient == null ? HttpClient.newHttpClient() : httpClient;
    this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
  }

  public SubmitCheckResponse submitCheck(SubmitCheckRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null.");
    }
    return send(
        "POST",
        "/checks",
        request,
        SubmitCheckResponse.class);
  }

  public CheckRun getCheck(String runId) {
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("runId must not be blank.");
    }
    return send(
        "GET",
        "/checks/" + runId.trim(),
        null,
        CheckRun.class);
  }

  public List<RunLog> getRunLogs(String runId) {
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("runId must not be blank.");
    }
    return send(
        "GET",
        "/runs/" + runId.trim() + "/logs",
        null,
        new TypeReference<>() {});
  }

  private <T> T send(String method, String path, Object body, Class<T> responseType) {
    try {
      HttpRequest request = buildRequest(method, path, body);
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 300) {
        throw new ContractSdkException(
            "SDK request failed with HTTP " + response.statusCode() + ": " + response.body());
      }
      return objectMapper.readValue(response.body(), responseType);
    } catch (ContractSdkException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ContractSdkException("SDK request failed for " + method + " " + path, ex);
    }
  }

  private <T> T send(String method, String path, Object body, TypeReference<T> responseType) {
    try {
      HttpRequest request = buildRequest(method, path, body);
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 300) {
        throw new ContractSdkException(
            "SDK request failed with HTTP " + response.statusCode() + ": " + response.body());
      }
      return objectMapper.readValue(response.body(), responseType);
    } catch (ContractSdkException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ContractSdkException("SDK request failed for " + method + " " + path, ex);
    }
  }

  private HttpRequest buildRequest(String method, String path, Object body) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + path))
        .timeout(DEFAULT_TIMEOUT)
        .header("Accept", "application/json");
    if ("POST".equalsIgnoreCase(method)) {
      String payload = objectMapper.writeValueAsString(body);
      builder.header("Content-Type", "application/json");
      builder.POST(HttpRequest.BodyPublishers.ofString(payload));
    } else {
      builder.GET();
    }
    return builder.build();
  }

  private String trimTrailingSlash(String value) {
    if (value.endsWith("/")) {
      return value.substring(0, value.length() - 1);
    }
    return value;
  }
}
