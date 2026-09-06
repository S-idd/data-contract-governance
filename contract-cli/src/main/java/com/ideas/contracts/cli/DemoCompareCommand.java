package com.ideas.contracts.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/** Calls the synchronous Spring demo endpoint and renders both verdicts for presentation. */
@CommandLine.Command(
    name = "demo-compare",
    description = "Compare rule-engine and model verdicts through the synchronous demo endpoint",
    mixinStandardHelpOptions = true)
public class DemoCompareCommand implements Callable<Integer> {
  private static final ObjectMapper JSON = new ObjectMapper();

  @CommandLine.Option(names = "--base", description = "Base schema JSON file")
  private Path baseFile;

  @CommandLine.Option(names = "--base-json", description = "Base schema as pasted JSON")
  private String baseJson;

  @CommandLine.Option(names = "--candidate", description = "Candidate schema JSON file")
  private Path candidateFile;

  @CommandLine.Option(names = "--candidate-json", description = "Candidate schema as pasted JSON")
  private String candidateJson;

  @CommandLine.Option(
      names = "--endpoint",
      defaultValue = "http://127.0.0.1:8081/demo/compare",
      description = "Synchronous demo endpoint")
  private String endpoint;

  @CommandLine.Option(names = "--policy-pack", defaultValue = "baseline")
  private String policyPack;

  @CommandLine.Option(names = "--mode", defaultValue = "BACKWARD")
  private String mode;

  @Override
  public Integer call() {
    try {
      JsonNode baseSchema = readSchema("base", baseFile, baseJson);
      JsonNode candidateSchema = readSchema("candidate", candidateFile, candidateJson);
      JsonNode response = post(baseSchema, candidateSchema);
      printVerdicts(response);
      return 0;
    } catch (IllegalArgumentException error) {
      System.err.println("Demo comparison input error: " + error.getMessage());
      return 2;
    } catch (IOException | InterruptedException error) {
      if (error instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      System.err.println("Demo comparison request failed: " + error.getMessage());
      return 2;
    }
  }

  private JsonNode readSchema(String role, Path file, String pastedJson) throws IOException {
    if ((file == null) == (pastedJson == null || pastedJson.isBlank())) {
      throw new IllegalArgumentException(
          "provide exactly one of --" + role + " or --" + role + "-json.");
    }
    JsonNode schema = file != null ? JSON.readTree(Files.readString(file)) : JSON.readTree(pastedJson);
    if (schema == null || !schema.isObject()) {
      throw new IllegalArgumentException(role + " schema must be a JSON object.");
    }
    return schema;
  }

  private JsonNode post(JsonNode baseSchema, JsonNode candidateSchema)
      throws IOException, InterruptedException {
    ObjectNode requestBody = JSON.createObjectNode();
    requestBody.set("base_schema", baseSchema);
    requestBody.set("candidate_schema", candidateSchema);
    requestBody.put("policy_pack", policyPack);
    requestBody.put("mode", mode);
    HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
        .timeout(Duration.ofSeconds(5))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(requestBody)))
        .build();
    HttpResponse<String> response = HttpClient.newHttpClient().send(
        request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("endpoint returned HTTP " + response.statusCode() + ": " + response.body());
    }
    return JSON.readTree(response.body());
  }

  private void printVerdicts(JsonNode response) {
    JsonNode authoritative = response.path("authoritative");
    JsonNode model = response.path("model");
    JsonNode agreement = response.path("agreement");

    System.out.println("=== DATA CONTRACT COMPATIBILITY DEMO ===");
    System.out.printf("Rule engine: %s (%s) | mode=%s | policy=%s%n",
        authoritative.path("label").asText(),
        authoritative.path("status").asText(),
        authoritative.path("mode").asText(),
        authoritative.path("policy_pack").asText());
    printFindings("Breaking changes", authoritative.path("breaking_changes"));
    printFindings("Warnings", authoritative.path("warnings"));

    System.out.println("Model: raw frozen seed outputs (no aggregation)");
    for (JsonNode prediction : model.path("predictions")) {
      JsonNode probabilities = prediction.path("probabilities");
      System.out.printf("  seed %s: %-8s safe=%.6f warning=%.6f breaking=%.6f%n",
          prediction.path("seed").asText(),
          prediction.path("label").asText(),
          probabilities.path("safe").asDouble(),
          probabilities.path("warning").asDouble(),
          probabilities.path("breaking").asDouble());
    }
    System.out.printf("Agreement (%s): %s%n",
        agreement.path("basis").asText(),
        agreement.path("agrees").asBoolean() ? "AGREE" : "DISAGREE");
  }

  private void printFindings(String label, JsonNode findings) {
    if (!findings.isArray() || findings.isEmpty()) {
      System.out.println(label + ": none");
      return;
    }
    System.out.println(label + ":");
    for (JsonNode finding : findings) {
      System.out.println("  - " + finding.asText());
    }
  }
}
