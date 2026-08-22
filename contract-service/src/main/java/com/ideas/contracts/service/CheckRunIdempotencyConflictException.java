package com.ideas.contracts.service;

public class CheckRunIdempotencyConflictException extends RuntimeException {
  public CheckRunIdempotencyConflictException(String idempotencyKey) {
    super("Idempotency-Key has already been used for a different check request: " + idempotencyKey);
  }
}
