package com.ideas.contracts.service;

import java.util.List;

record ShadowInferenceResponse(List<SeedPrediction> predictions) {
  record SeedPrediction(String seed, String label, Probabilities probabilities) {}

  record Probabilities(double safe, double warning, double breaking) {}
}
