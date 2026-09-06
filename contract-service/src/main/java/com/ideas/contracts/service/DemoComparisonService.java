package com.ideas.contracts.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideas.contracts.core.CompatibilityMode;
import com.ideas.contracts.core.CompatibilityResult;
import com.ideas.contracts.core.ContractEngine;
import com.ideas.contracts.core.PolicyPack;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Presentation-only synchronous comparison flow. It deliberately does not use or alter the
 * asynchronous ShadowInferenceObserver.
 */
@Service
public class DemoComparisonService {
  private static final String RAW_SEEDS_ONLY = "RAW_SEEDS_ONLY";
  private static final String ALL_THREE = "ALL_THREE";

  private final ContractEngine contractEngine;
  private final PolicyPackRegistry policyPackRegistry;
  private final ShadowInferenceGateway inferenceGateway;
  private final ObjectMapper objectMapper;

  public DemoComparisonService(
      ContractEngine contractEngine,
      PolicyPackRegistry policyPackRegistry,
      ShadowInferenceGateway inferenceGateway,
      ObjectMapper objectMapper) {
    this.contractEngine = contractEngine;
    this.policyPackRegistry = policyPackRegistry;
    this.inferenceGateway = inferenceGateway;
    this.objectMapper = objectMapper;
  }

  /** Runs the rule engine and waits for the Rust inference response before returning both. */
  public DemoComparisonResponse compare(DemoComparisonRequest request) {
    validateRequest(request);
    CompatibilityMode mode = parseMode(request.mode());
    String policyPackName = resolvePolicyPackName(request.policyPack());
    PolicyPack policyPack = policyPackRegistry.resolve(policyPackName);

    try {
      Path workspace = Files.createTempDirectory("dcg-demo-comparison-");
      try {
        Path basePath = writeSchema(workspace, "base.json", request.baseSchema());
        Path candidatePath = writeSchema(workspace, "candidate.json", request.candidateSchema());
        CompatibilityResult authoritative = contractEngine.checkCompatibility(
            basePath, candidatePath, mode, policyPack);
        ShadowInferenceResponse model = inferenceGateway.predict(new ShadowInferenceRequest(
            request.baseSchema(), request.candidateSchema(), policyPackName));
        return response(authoritative, mode, policyPackName, model);
      } finally {
        deleteWorkspace(workspace);
      }
    } catch (IOException error) {
      throw new IllegalStateException("Unable to prepare the synchronous demo comparison", error);
    }
  }

  private DemoComparisonResponse response(
      CompatibilityResult authoritative,
      CompatibilityMode mode,
      String policyPackName,
      ShadowInferenceResponse model) {
    String authoritativeLabel = labelFor(authoritative);
    List<ShadowInferenceResponse.SeedPrediction> predictions = List.copyOf(model.predictions());
    boolean agrees = predictions.stream()
        .allMatch(prediction -> authoritativeLabel.equals(prediction.label()));
    return new DemoComparisonResponse(
        new DemoComparisonResponse.AuthoritativeVerdict(
            authoritativeLabel,
            authoritative.status().name(),
            mode.name(),
            policyPackName,
            List.copyOf(authoritative.breakingChanges()),
            List.copyOf(authoritative.warnings())),
        new DemoComparisonResponse.ModelVerdict(RAW_SEEDS_ONLY, predictions),
        new DemoComparisonResponse.Agreement(ALL_THREE, agrees));
  }

  private void validateRequest(DemoComparisonRequest request) {
    if (request == null || !objectNode(request.baseSchema()) || !objectNode(request.candidateSchema())) {
      throw new IllegalArgumentException("baseSchema and candidateSchema must be JSON objects.");
    }
  }

  private boolean objectNode(JsonNode value) {
    return value != null && value.isObject();
  }

  private CompatibilityMode parseMode(String requestedMode) {
    String rawMode = requestedMode == null || requestedMode.isBlank() ? "BACKWARD" : requestedMode;
    try {
      return CompatibilityMode.valueOf(rawMode.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("mode must be BACKWARD, FORWARD, or FULL.", error);
    }
  }

  private String resolvePolicyPackName(String requestedPolicyPack) {
    return requestedPolicyPack == null || requestedPolicyPack.isBlank()
        ? policyPackRegistry.defaultPackName()
        : policyPackRegistry.resolveName(requestedPolicyPack);
  }

  private Path writeSchema(Path workspace, String fileName, JsonNode schema) throws IOException {
    Path path = workspace.resolve(fileName);
    objectMapper.writeValue(path.toFile(), schema);
    return path;
  }

  private void deleteWorkspace(Path workspace) throws IOException {
    try (var paths = Files.walk(workspace)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException error) {
          throw new DemoWorkspaceCleanupException(error);
        }
      });
    } catch (DemoWorkspaceCleanupException error) {
      throw error.cause;
    }
  }

  private String labelFor(CompatibilityResult result) {
    if (!result.breakingChanges().isEmpty()) {
      return "BREAKING";
    }
    if (!result.warnings().isEmpty()) {
      return "WARNING";
    }
    return "SAFE";
  }

  private static final class DemoWorkspaceCleanupException extends RuntimeException {
    private final IOException cause;

    private DemoWorkspaceCleanupException(IOException cause) {
      this.cause = cause;
    }
  }
}
