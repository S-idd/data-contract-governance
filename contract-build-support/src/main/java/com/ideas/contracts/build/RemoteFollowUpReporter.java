package com.ideas.contracts.build;

import java.io.IOException;
import java.nio.file.Files;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Sends the immutable local evidence artifact after local compatibility has completed.
 */
public class RemoteFollowUpReporter {
  private final HttpClient httpClient;

  public RemoteFollowUpReporter() {
    this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
  }

  RemoteFollowUpReporter(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  public void report(CompatibilityBuildRequest request, java.nio.file.Path evidenceFile) {
    reportArtifact(
        request.remoteServiceUrl(), request.remoteAuthorization(), request.remoteTimeout(),
        request.remoteMaxAttempts(), evidenceFile);
  }

  public void reportArtifact(
      String remoteServiceUrl,
      String remoteAuthorization,
      Duration remoteTimeout,
      int remoteMaxAttempts,
      java.nio.file.Path evidenceFile) {
    requireRemoteConfiguration(remoteServiceUrl, evidenceFile);
    Instant deadline = Instant.now().plus(remoteTimeout);
    RuntimeException lastFailure = null;
    for (int attempt = 1; attempt <= remoteMaxAttempts; attempt++) {
      try {
        send(remoteServiceUrl, remoteAuthorization, evidenceFile, remaining(deadline));
        return;
      } catch (RemoteAuthenticationException exception) {
        throw exception;
      } catch (RemoteRateLimitException exception) {
        lastFailure = exception;
        if (attempt < remoteMaxAttempts && Instant.now().isBefore(deadline)) {
          backoff(deadline, exception.retryAfter());
        }
      } catch (RuntimeException ex) {
        lastFailure = ex;
        if (attempt < remoteMaxAttempts && Instant.now().isBefore(deadline)) {
          backoff(deadline, Duration.ofMillis(100L * attempt));
        }
      }
    }
    throw new RemoteReportingException(
        "Remote DCG evidence was not accepted within " + remoteTimeout + ".", lastFailure);
  }

  private void send(
      String remoteServiceUrl,
      String remoteAuthorization,
      java.nio.file.Path evidenceFile,
      Duration timeout) {
    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder()
          .uri(URI.create(trimTrailingSlash(remoteServiceUrl) + "/checks/evidence"))
          .timeout(timeout)
          .header("Accept", "application/json")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(Files.readString(evidenceFile)));
      if (!isBlank(remoteAuthorization)) {
        if (remoteAuthorization.indexOf('\r') >= 0 || remoteAuthorization.indexOf('\n') >= 0) {
          throw new IllegalArgumentException("remoteAuthorization must not contain line breaks.");
        }
        builder.header("Authorization", remoteAuthorization.trim());
      }
      HttpRequest httpRequest = builder.build();
      HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 401) {
        throw new RemoteAuthenticationException(
            "DCG evidence authentication failed (HTTP 401). The OIDC token may be expired; obtain a fresh CI-issued token and replay the same evidence artifact.");
      }
      if (response.statusCode() == 403) {
        throw new RemoteAuthenticationException(
            "DCG evidence authorization failed (HTTP 403). Verify the contract, repository, and ref allow-list before replaying the same evidence artifact.");
      }
      if (response.statusCode() == 429) {
        throw new RemoteRateLimitException(retryAfter(response));
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("DCG service returned HTTP " + response.statusCode() + ".");
      }
    } catch (IOException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalStateException("Unable to contact the DCG service.", ex);
    } catch (Exception ex) {
      if (ex instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException("Unable to contact the DCG service.", ex);
    }
  }

  private void requireRemoteConfiguration(String remoteServiceUrl, java.nio.file.Path evidenceFile) {
    if (isBlank(remoteServiceUrl) || evidenceFile == null || !Files.isRegularFile(evidenceFile)) {
      throw new IllegalArgumentException(
          "remoteServiceUrl and an existing evidence artifact are required when remote reporting is enabled.");
    }
  }

  private Duration remaining(Instant deadline) {
    Duration remaining = Duration.between(Instant.now(), deadline);
    if (remaining.isNegative() || remaining.isZero()) {
      throw new IllegalStateException("Remote reporting deadline elapsed.");
    }
    return remaining;
  }

  private Duration retryAfter(HttpResponse<?> response) {
    String value = response.headers().firstValue("Retry-After").orElse("").trim();
    if (value.isEmpty()) {
      return Duration.ofSeconds(1);
    }
    try {
      long seconds = Long.parseLong(value);
      return seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ofSeconds(1);
    } catch (NumberFormatException ignored) {
      try {
        Duration delay = Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        return delay.isPositive() ? delay : Duration.ofSeconds(1);
      } catch (RuntimeException ignoredAgain) {
        return Duration.ofSeconds(1);
      }
    }
  }

  private void backoff(Instant deadline, Duration requestedDelay) {
    long delayMillis = Math.min(
        Math.max(0L, requestedDelay.toMillis()), Math.max(0L, Duration.between(Instant.now(), deadline).toMillis()));
    if (delayMillis <= 0) {
      return;
    }
    try {
      Thread.sleep(delayMillis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Remote reporting retry interrupted.", ex);
    }
  }

  private String trimTrailingSlash(String value) {
    String trimmed = value.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
