package com.ideas.contracts.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class CheckMetrics {
  private final MeterRegistry meterRegistry;

  public CheckMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void recordQueued(String contractId) {
    Counter.builder("checks_total")
        .description("Total number of check runs observed")
        .tag("phase", "queued")
        .tag("contract_id", safeContract(contractId))
        .register(meterRegistry)
        .increment();
  }

  public void recordCompleted(String contractId, String outcome, Duration duration) {
    Counter.builder("checks_total")
        .description("Total number of check runs observed")
        .tag("phase", "completed")
        .tag("contract_id", safeContract(contractId))
        .tag("outcome", safeOutcome(outcome))
        .register(meterRegistry)
        .increment();

    Timer.builder("checks_latency")
        .description("Latency for end-to-end compatibility check execution")
        .tag("contract_id", safeContract(contractId))
        .register(meterRegistry)
        .record(duration == null ? Duration.ZERO : duration);
  }

  public void recordFailed(String contractId, String category) {
    Counter.builder("checks_failed_total")
        .description("Total number of failed check runs")
        .tag("contract_id", safeContract(contractId))
        .tag("category", safeCategory(category))
        .register(meterRegistry)
        .increment();
  }

  private String safeContract(String contractId) {
    if (contractId == null || contractId.isBlank()) {
      return "unknown";
    }
    return contractId.trim();
  }

  private String safeOutcome(String outcome) {
    if (outcome == null || outcome.isBlank()) {
      return "unknown";
    }
    return outcome.trim().toUpperCase();
  }

  private String safeCategory(String category) {
    if (category == null || category.isBlank()) {
      return "unknown";
    }
    return category.trim().toLowerCase();
  }
}
