package com.ideas.contracts.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideas.contracts.service.model.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Enforces the evidence cap before MVC parses or buffers an unbounded request body. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class EvidencePayloadLimitFilter extends OncePerRequestFilter {
  private static final String EVIDENCE_PATH = "/checks/evidence";
  private final int maxPayloadBytes;
  private final ObjectMapper objectMapper;

  public EvidencePayloadLimitFilter(
      @Value("${checks.evidence.max-payload-bytes:1048576}") int maxPayloadBytes,
      ObjectMapper objectMapper) {
    this.maxPayloadBytes = Math.max(1, maxPayloadBytes);
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equalsIgnoreCase(request.getMethod()) || !EVIDENCE_PATH.equals(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > maxPayloadBytes) {
      writeTooLarge(response, request);
      return;
    }
    try {
      filterChain.doFilter(new LimitedRequest(request, maxPayloadBytes), response);
    } catch (EvidencePayloadLimitExceededException exception) {
      if (!response.isCommitted()) {
        writeTooLarge(response, request);
        return;
      }
      throw exception;
    }
  }

  private void writeTooLarge(HttpServletResponse response, HttpServletRequest request) throws IOException {
    response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
        Instant.now().toString(), HttpStatus.PAYLOAD_TOO_LARGE.value(), HttpStatus.PAYLOAD_TOO_LARGE.getReasonPhrase(),
        "EVIDENCE_PAYLOAD_TOO_LARGE", "Evidence payload exceeds the configured size limit.",
        request.getRequestURI(), requestId(request)));
  }

  private String requestId(HttpServletRequest request) {
    Object value = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    return value instanceof String id && !id.isBlank() ? id : "-";
  }

  private static final class LimitedRequest extends HttpServletRequestWrapper {
    private final int maximum;
    private ServletInputStream inputStream;

    private LimitedRequest(HttpServletRequest request, int maximum) {
      super(request);
      this.maximum = maximum;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (inputStream == null) {
        inputStream = new CountingInputStream(super.getInputStream(), maximum);
      }
      return inputStream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
      String encoding = getCharacterEncoding();
      return new BufferedReader(new InputStreamReader(
          getInputStream(), encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding)));
    }
  }

  private static final class CountingInputStream extends ServletInputStream {
    private final ServletInputStream delegate;
    private final int maximum;
    private long consumed;

    private CountingInputStream(ServletInputStream delegate, int maximum) {
      this.delegate = delegate;
      this.maximum = maximum;
    }

    @Override
    public int read() throws IOException {
      int value = delegate.read();
      count(value < 0 ? 0 : 1);
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int read = delegate.read(bytes, offset, length);
      count(Math.max(0, read));
      return read;
    }

    @Override
    public boolean isFinished() { return delegate.isFinished(); }

    @Override
    public boolean isReady() { return delegate.isReady(); }

    @Override
    public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }

    private void count(int read) {
      consumed += read;
      if (consumed > maximum) {
        throw new EvidencePayloadLimitExceededException();
      }
    }
  }
}
