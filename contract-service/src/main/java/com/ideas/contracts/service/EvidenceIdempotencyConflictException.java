package com.ideas.contracts.service;

public class EvidenceIdempotencyConflictException extends RuntimeException {
  public EvidenceIdempotencyConflictException(String idempotencyKey) {
    super("idempotencyKey has already been used with a different evidence payload: " + idempotencyKey);
  }
}
