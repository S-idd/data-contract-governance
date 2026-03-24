package com.ideas.contracts.starter;

public class ContractPayloadValidationException extends RuntimeException {
  public ContractPayloadValidationException(String message) {
    super(message);
  }

  public ContractPayloadValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
