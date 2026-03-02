package com.ideas.contracts.service;

public class CheckRunStoreException extends RuntimeException {
  public CheckRunStoreException(String message) {
    super(message);
  }

  public CheckRunStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
