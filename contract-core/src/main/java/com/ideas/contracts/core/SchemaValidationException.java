package com.ideas.contracts.core;

public class SchemaValidationException extends RuntimeException {
  public SchemaValidationException(String message) {
    super(message);
  }

  public SchemaValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
