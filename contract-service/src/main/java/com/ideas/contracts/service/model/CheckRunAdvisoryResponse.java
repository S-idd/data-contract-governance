package com.ideas.contracts.service.model;

import java.util.List;
import java.util.Map;

/** Optional, non-authoritative inference attached to a completed check run. */
public record CheckRunAdvisoryResponse(
    String runId,
    boolean advisoryOnly,
    boolean testOnlyAdapter,
    String status,
    String modelVersion,
    String modelArtifactSha256,
    String featureSchemaVersion,
    String inputHash,
    String predictionLabel,
    Map<String, Double> probabilities,
    List<SeedPrediction> seedPredictions,
    long inferenceDurationMs,
    String agreement,
    String createdAt,
    String completedAt) {
  public record SeedPrediction(String seed, String label, Map<String, Double> probabilities) {}
}
