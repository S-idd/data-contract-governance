package com.ideas.contracts.core;

public class ExecutionException extends RuntimeException {
  public ExecutionException(String message) {
    super(message);
  }

  public ExecutionException(String message, Throwable cause) {
    super(message, cause);
  }
}
