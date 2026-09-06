package com.ideas.contracts.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
class HttpShadowInferenceGateway implements ShadowInferenceGateway {
  private static final Set<String> EXPECTED_SEEDS =
      Set.of("20260826", "20260827", "20260828");
  private static final Set<String> LABELS = Set.of("SAFE", "WARNING", "BREAKING");
  private static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(500);

  private final ShadowInferenceProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  @Autowired
  HttpShadowInferenceGateway(
      ShadowInferenceProperties properties,
      ObjectMapper objectMapper) {
    this(
        properties,
        objectMapper,
        HttpClient.newBuilder()
            .connectTimeout(positiveTimeout(properties.getTimeout()))
            .build());
  }

  HttpShadowInferenceGateway(
      ShadowInferenceProperties properties,
      ObjectMapper objectMapper,
      HttpClient httpClient) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
  }

  @Override
  public ShadowInferenceResponse predict(ShadowInferenceRequest request) {
    HttpRequest httpRequest = HttpRequest.newBuilder(endpoint())
        .timeout(positiveTimeout(properties.getTimeout()))
        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .POST(HttpRequest.BodyPublishers.ofString(encodeRequest(request)))
        .build();
    try {
      HttpResponse<String> response = httpClient.send(
          httpRequest,
          HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new ShadowInferenceException(
            "HTTP_ERROR",
            "shadow inference returned HTTP status " + response.statusCode());
      }
      return parseResponse(response.body());
    } catch (HttpTimeoutException error) {
      throw new ShadowInferenceException("TIMEOUT", "shadow inference timed out", error);
    } catch (ConnectException error) {
      throw new ShadowInferenceException(
          "CONNECTION_REFUSED", "shadow inference connection was refused", error);
    } catch (IOException error) {
      throw new ShadowInferenceException(
          transportFailureStage(error), "shadow inference transport failed", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new ShadowInferenceException("INTERRUPTED", "shadow inference was interrupted", error);
    }
  }

  ShadowInferenceResponse parseResponse(String body) {
    final ShadowInferenceResponse response;
    try {
      response = objectMapper.readValue(body, ShadowInferenceResponse.class);
    } catch (JsonProcessingException error) {
      throw new ShadowInferenceException(
          "MALFORMED_RESPONSE", "shadow inference response was not valid JSON", error);
    }
    validateResponse(response);
    return response;
  }

  static String transportFailureStage(IOException error) {
    if (error instanceof HttpTimeoutException) {
      return "TIMEOUT";
    }
    if (error instanceof ConnectException) {
      return "CONNECTION_REFUSED";
    }
    return "TRANSPORT_ERROR";
  }

  private String encodeRequest(ShadowInferenceRequest request) {
    try {
      return objectMapper.writeValueAsString(request);
    } catch (JsonProcessingException error) {
      throw new ShadowInferenceException(
          "REQUEST_SERIALIZATION", "shadow inference request could not be serialized", error);
    }
  }

  private URI endpoint() {
    String endpoint = properties.getEndpoint();
    try {
      URI uri = URI.create(endpoint == null ? "" : endpoint.trim());
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      if (!List.of("http", "https").contains(scheme) || uri.getHost() == null) {
        throw new IllegalArgumentException("not an absolute HTTP(S) URI");
      }
      return uri;
    } catch (IllegalArgumentException error) {
      throw new ShadowInferenceException(
          "CONFIGURATION", "shadow inference endpoint is invalid", error);
    }
  }

  private void validateResponse(ShadowInferenceResponse response) {
    if (response == null || response.predictions() == null || response.predictions().size() != 3) {
      throw malformed("response must contain exactly three seed predictions");
    }
    Set<String> seeds = new HashSet<>();
    for (ShadowInferenceResponse.SeedPrediction prediction : response.predictions()) {
      if (prediction == null || !EXPECTED_SEEDS.contains(prediction.seed())
          || !seeds.add(prediction.seed())) {
        throw malformed("response contains a missing, duplicate, or unknown seed");
      }
      if (prediction.label() == null || !LABELS.contains(prediction.label())) {
        throw malformed("response contains an unsupported label");
      }
      validateProbabilities(prediction.probabilities());
    }
    if (!seeds.equals(EXPECTED_SEEDS)) {
      throw malformed("response does not contain all frozen seeds");
    }
  }

  private void validateProbabilities(ShadowInferenceResponse.Probabilities probabilities) {
    if (probabilities == null
        || !validProbability(probabilities.safe())
        || !validProbability(probabilities.warning())
        || !validProbability(probabilities.breaking())) {
      throw malformed("response contains an invalid probability");
    }
    double sum = probabilities.safe() + probabilities.warning() + probabilities.breaking();
    if (Math.abs(sum - 1.0d) > 1e-9) {
      throw malformed("response probabilities do not sum to one");
    }
  }

  private boolean validProbability(double value) {
    return Double.isFinite(value) && value >= 0.0d && value <= 1.0d;
  }

  private ShadowInferenceException malformed(String message) {
    return new ShadowInferenceException("MALFORMED_RESPONSE", message);
  }

  private static Duration positiveTimeout(Duration configured) {
    if (configured == null || configured.isZero() || configured.isNegative()) {
      return DEFAULT_TIMEOUT;
    }
    return configured;
  }
}
