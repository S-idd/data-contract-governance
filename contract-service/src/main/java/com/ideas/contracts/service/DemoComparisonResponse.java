package com.ideas.contracts.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Combined authoritative and model result returned by the synchronous demo path. */
public record DemoComparisonResponse(
    AuthoritativeVerdict authoritative,
    ModelVerdict model,
    Agreement agreement) {

  public record AuthoritativeVerdict(
      String label,
      String status,
      String mode,
      @JsonProperty("policy_pack") String policyPack,
      @JsonProperty("breaking_changes") List<String> breakingChanges,
      List<String> warnings) {
  }

  public record ModelVerdict(
      String aggregation,
      List<ShadowInferenceResponse.SeedPrediction> predictions) {
  }

  public record Agreement(String basis, boolean agrees) {
  }
}
