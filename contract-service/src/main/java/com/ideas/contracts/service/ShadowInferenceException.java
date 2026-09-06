package com.ideas.contracts.service;

final class ShadowInferenceException extends RuntimeException {
  private final String failureStage;

  ShadowInferenceException(String failureStage, String message) {
    super(message);
    this.failureStage = failureStage;
  }

  ShadowInferenceException(String failureStage, String message, Throwable cause) {
    super(message, cause);
    this.failureStage = failureStage;
  }

  String failureStage() {
    return failureStage;
  }
}
