package com.ideas.contracts.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class LogNotificationSink implements NotificationSink {
  private static final Logger LOGGER = LoggerFactory.getLogger(LogNotificationSink.class);

  @Override
  public String name() {
    return "log";
  }

  @Override
  public void deliver(NotificationEvent event) {
    if (event.severity() == NotificationSeverity.INFO) {
      LOGGER.info(logTemplate(),
          event.eventId(),
          event.eventType(),
          event.severity(),
          safe(event.contractId()),
          safe(event.runId()),
          safe(event.baseVersion()),
          safe(event.candidateVersion()),
          safe(event.commitSha()),
          safe(event.triggeredBy()),
          safe(event.policyPack()),
          event.breakingChanges().size(),
          event.warnings().size(),
          event.dedupeKey(),
          safe(event.summary()));
      return;
    }

    LOGGER.warn(logTemplate(),
        event.eventId(),
        event.eventType(),
        event.severity(),
        safe(event.contractId()),
        safe(event.runId()),
        safe(event.baseVersion()),
        safe(event.candidateVersion()),
        safe(event.commitSha()),
        safe(event.triggeredBy()),
        safe(event.policyPack()),
        event.breakingChanges().size(),
        event.warnings().size(),
        event.dedupeKey(),
        safe(event.summary()));
  }

  private String logTemplate() {
    return "event=notification_event component=notification_sink sink=log event_id={} event_type={} severity={} contract_id={} run_id={} base_version={} candidate_version={} commit_sha={} triggered_by={} policy_pack={} breaking_changes_count={} warnings_count={} dedupe_key={} summary={}";
  }

  private String safe(String value) {
    if (value == null || value.isBlank()) {
      return "-";
    }
    return value.replaceAll("\\s+", " ").trim();
  }
}
