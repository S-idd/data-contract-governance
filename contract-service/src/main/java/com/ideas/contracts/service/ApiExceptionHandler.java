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
  private static final String DEFAULT_UNAVAILABLE_MESSAGE = "Check history store is currently unavailable.";

  @ExceptionHandler(CheckRunStoreException.class)
  public ResponseEntity<ApiErrorResponse> handleCheckStoreException(
      CheckRunStoreException ex,
      HttpServletRequest request) {
    String requestId = resolveRequestId(request);
    String path = request.getRequestURI();

    LOGGER.warn(
        "event=request_failed component=api_exception_handler error_code={} path={} request_id={} error_type={} error_message={}",
        CHECK_STORE_UNAVAILABLE,
        path,
        requestId,
        ex.getClass().getSimpleName(),
        safeValue(ex.getMessage()));

    ApiErrorResponse payload = new ApiErrorResponse(
        Instant.now().toString(),
        HttpStatus.SERVICE_UNAVAILABLE.value(),
        HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(),
        CHECK_STORE_UNAVAILABLE,
        DEFAULT_UNAVAILABLE_MESSAGE,
        path,
        requestId);

    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(payload);
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
