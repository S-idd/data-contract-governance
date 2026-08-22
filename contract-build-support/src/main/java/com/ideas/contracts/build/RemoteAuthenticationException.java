package com.ideas.contracts.build;

/** A non-retryable remote evidence authentication or authorization failure. */
public class RemoteAuthenticationException extends RuntimeException {
  public RemoteAuthenticationException(String message) {
    super(message);
  }
}
