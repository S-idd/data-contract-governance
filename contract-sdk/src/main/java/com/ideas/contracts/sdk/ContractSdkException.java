package com.ideas.contracts.sdk;

public class ContractSdkException extends RuntimeException {
  public ContractSdkException(String message) {
    super(message);
  }

  public ContractSdkException(String message, Throwable cause) {
    super(message, cause);
  }
}
