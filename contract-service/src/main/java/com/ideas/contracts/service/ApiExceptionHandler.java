package com.ideas.contracts.service;

import com.ideas.contracts.service.model.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private static final String CHECK_STORE_UNAVAILABLE = "CHECK_STORE_UNAVAILABLE";
  private static final String CHECK_RUN_NOT_FOUND = "CHECK_RUN_NOT_FOUND";
  private static final String INVALID_REQUEST = "INVALID_REQUEST";
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
