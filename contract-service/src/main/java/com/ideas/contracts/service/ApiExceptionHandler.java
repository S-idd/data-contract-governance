package com.ideas.contracts.service;

import com.ideas.contracts.core.CompatibilityException;
import com.ideas.contracts.core.ExecutionException;
import com.ideas.contracts.core.SchemaValidationException;
import com.ideas.contracts.service.model.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private static final String CHECK_STORE_UNAVAILABLE = "CHECK_STORE_UNAVAILABLE";
  private static final String CHECK_RUN_NOT_FOUND = "CHECK_RUN_NOT_FOUND";
  private static final String INVALID_REQUEST = "INVALID_REQUEST";
  private static final String SCHEMA_VALIDATION_FAILED = "SCHEMA_VALIDATION_FAILED";
  private static final String COMPATIBILITY_FAILED = "COMPATIBILITY_FAILED";
  private static final String EXECUTION_FAILED = "EXECUTION_FAILED";
  private static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
  private static final String CONFLICT = "RESOURCE_CONFLICT";
  private static final String INTERNAL_ERROR = "INTERNAL_ERROR";
  private static final String DEFAULT_UNAVAILABLE_MESSAGE = "Check history store is currently unavailable.";

  @ExceptionHandler(CheckRunStoreException.class)
  public ResponseEntity<ApiErrorResponse> handleCheckStoreException(
      CheckRunStoreException ex,
      HttpServletRequest request) {
    return buildErrorResponse(
        ex,
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        CHECK_STORE_UNAVAILABLE,
        DEFAULT_UNAVAILABLE_MESSAGE);
  }

  @ExceptionHandler(CheckRunNotFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleCheckRunNotFound(
      CheckRunNotFoundException ex,
      HttpServletRequest request) {
    return buildErrorResponse(
        ex,
        request,
        HttpStatus.NOT_FOUND,
        CHECK_RUN_NOT_FOUND,
        ex.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiErrorResponse> handleInvalidRequest(
      IllegalArgumentException ex,
      HttpServletRequest request) {
    return buildErrorResponse(
        ex,
        request,
        HttpStatus.BAD_REQUEST,
        INVALID_REQUEST,
        ex.getMessage());
  }

  @ExceptionHandler(SchemaValidationException.class)
  public ResponseEntity<ApiErrorResponse> handleSchemaValidation(
      SchemaValidationException ex,
      HttpServletRequest request) {
    String message = safeValue(ex.getMessage());
    HttpStatus status;
    String code;
    if (message.toLowerCase().contains("already exists")) {
      status = HttpStatus.CONFLICT;
      code = CONFLICT;
    } else if (message.toLowerCase().contains("not found")) {
      status = HttpStatus.NOT_FOUND;
      code = RESOURCE_NOT_FOUND;
    } else {
      status = HttpStatus.BAD_REQUEST;
      code = SCHEMA_VALIDATION_FAILED;
    }
    return buildErrorResponse(ex, request, status, code, ex.getMessage());
  }

  @ExceptionHandler(CompatibilityException.class)
  public ResponseEntity<ApiErrorResponse> handleCompatibility(
      CompatibilityException ex,
      HttpServletRequest request) {
    return buildErrorResponse(
        ex,
        request,
        HttpStatus.UNPROCESSABLE_ENTITY,
        COMPATIBILITY_FAILED,
        ex.getMessage());
  }

  @ExceptionHandler(ExecutionException.class)
  public ResponseEntity<ApiErrorResponse> handleExecution(
      ExecutionException ex,
      HttpServletRequest request) {
    return buildErrorResponse(
        ex,
        request,
        HttpStatus.INTERNAL_SERVER_ERROR,
        EXECUTION_FAILED,
        ex.getMessage());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiErrorResponse> handleJsonParseError(
      HttpMessageNotReadableException ex,
      HttpServletRequest request) {
    return buildErrorResponse(
        ex,
        request,
        HttpStatus.BAD_REQUEST,
        INVALID_REQUEST,
        "Malformed JSON request body.");
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiErrorResponse> handleResponseStatus(
      ResponseStatusException ex,
      HttpServletRequest request) {
    HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
    String code = switch (status) {
      case NOT_FOUND -> RESOURCE_NOT_FOUND;
      case CONFLICT -> CONFLICT;
      case BAD_REQUEST -> INVALID_REQUEST;
      default -> status.name();
    };
    return buildErrorResponse(ex, request, status, code, ex.getReason());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> handleUnexpectedException(
      Exception ex,
      HttpServletRequest request) {
    return buildErrorResponse(
        ex,
        request,
        HttpStatus.INTERNAL_SERVER_ERROR,
        INTERNAL_ERROR,
        "Unexpected server error.");
  }

  private ResponseEntity<ApiErrorResponse> buildErrorResponse(
      Exception ex,
      HttpServletRequest request,
      HttpStatus status,
      String errorCode,
      String message) {
    String requestId = resolveRequestId(request);
    String path = request.getRequestURI();

    LOGGER.warn(
        "event=request_failed component=api_exception_handler error_code={} path={} request_id={} error_type={} error_message={}",
        errorCode,
        path,
        requestId,
        ex.getClass().getSimpleName(),
        safeValue(ex.getMessage()));

    ApiErrorResponse payload = new ApiErrorResponse(
        Instant.now().toString(),
        status.value(),
        status.getReasonPhrase(),
        errorCode,
        message,
        path,
        requestId);

    return ResponseEntity.status(status).body(payload);
  }

  private String resolveRequestId(HttpServletRequest request) {
    Object attributeValue = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    if (attributeValue instanceof String requestId && !requestId.isBlank()) {
      return requestId;
    }
    String headerValue = request.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
    return safeValue(headerValue);
  }

  private String safeValue(String value) {
    return value == null || value.isBlank() ? "-" : value;
  }
}
