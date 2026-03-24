package com.ideas.contracts.starter;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ContractValidationExceptionHandler {
  @ExceptionHandler(ContractPayloadValidationException.class)
  public ResponseEntity<Map<String, Object>> handleValidationException(ContractPayloadValidationException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
        "timestamp", Instant.now().toString(),
        "status", 400,
        "error", "Bad Request",
        "code", "CONTRACT_PAYLOAD_INVALID",
        "message", ex.getMessage()));
  }
}
