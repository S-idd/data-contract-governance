package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;

class HttpShadowInferenceGatewayTest {
  private final HttpShadowInferenceGateway gateway = new HttpShadowInferenceGateway(
      new ShadowInferenceProperties(),
      new ObjectMapper());

  @Test
  void parsesTheThreeFrozenSeedOutputs() {
    ShadowInferenceResponse response = gateway.parseResponse("""
        {"predictions":[
          {"seed":"20260826","label":"SAFE","probabilities":{"safe":0.8,"warning":0.1,"breaking":0.1}},
          {"seed":"20260827","label":"WARNING","probabilities":{"safe":0.1,"warning":0.8,"breaking":0.1}},
          {"seed":"20260828","label":"BREAKING","probabilities":{"safe":0.1,"warning":0.1,"breaking":0.8}}
        ]}
        """);

    assertEquals(3, response.predictions().size());
    assertEquals("20260826", response.predictions().get(0).seed());
  }

  @Test
  void rejectsMalformedOrIncompleteResponsesWithAnExplicitStage() {
    ShadowInferenceException invalidJson = assertThrows(
        ShadowInferenceException.class,
        () -> gateway.parseResponse("not-json"));
    assertEquals("MALFORMED_RESPONSE", invalidJson.failureStage());

    ShadowInferenceException missingSeeds = assertThrows(
        ShadowInferenceException.class,
        () -> gateway.parseResponse("{\"predictions\":[]}"));
    assertEquals("MALFORMED_RESPONSE", missingSeeds.failureStage());
  }

  @Test
  void classifiesTransportFailuresForStructuredFailureLogging() {
    assertEquals(
        "TIMEOUT",
        HttpShadowInferenceGateway.transportFailureStage(new HttpTimeoutException("slow")));
    assertEquals(
        "CONNECTION_REFUSED",
        HttpShadowInferenceGateway.transportFailureStage(new ConnectException("refused")));
    assertEquals(
        "TRANSPORT_ERROR",
        HttpShadowInferenceGateway.transportFailureStage(new IOException("network")));
  }
}
