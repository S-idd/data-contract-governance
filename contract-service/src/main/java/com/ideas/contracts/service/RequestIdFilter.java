package com.ideas.contracts.service;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestIdFilter extends OncePerRequestFilter {
  public static final String REQUEST_ID_HEADER = "X-Request-Id";
  public static final String REQUEST_ID_ATTRIBUTE = "requestId";
  private static final String MDC_KEY = "request_id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
    request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
    response.setHeader(REQUEST_ID_HEADER, requestId);

    MDC.put(MDC_KEY, requestId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  private String resolveRequestId(String incomingRequestId) {
    if (incomingRequestId == null || incomingRequestId.isBlank()) {
      return UUID.randomUUID().toString();
    }
    return incomingRequestId.trim();
  }
}
