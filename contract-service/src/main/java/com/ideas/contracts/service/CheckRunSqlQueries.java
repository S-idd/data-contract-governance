package com.ideas.contracts.service;

final class CheckRunSqlQueries {
  static final String LIST_CHECK_RUNS_BASE = """
      SELECT run_id, contract_id, base_version, candidate_version, status,
             breaking_changes, warnings, commit_sha, created_at,
             triggered_by, started_at, finished_at
      FROM check_runs
      WHERE 1=1
      """;

  static final String FIND_CHECK_RUN_BY_ID = """
      SELECT run_id, contract_id, base_version, candidate_version, status,
             breaking_changes, warnings, commit_sha, created_at,
             triggered_by, started_at, finished_at
      FROM check_runs
      WHERE run_id = ?
      LIMIT 1
      """;

  static final String LIST_CHECK_RUN_LOGS = """
      SELECT log_id, run_id, level, message, created_at
      FROM check_run_logs
      WHERE run_id = ?
      ORDER BY created_at ASC
      """;

  static final String SELECT_NEXT_QUEUED_RUN = """
      SELECT run_id, contract_id, base_version, candidate_version, compatibility_mode,
             commit_sha, triggered_by
      FROM check_runs
      WHERE status = ?
      ORDER BY created_at ASC, run_id ASC
      LIMIT 1
      """;

  static final String UPDATE_RUN_TO_RUNNING = """
      UPDATE check_runs
      SET status = ?, started_at = ?
      WHERE run_id = ? AND status = ?
      """;

  static final String REQUEUE_RUN = """
      UPDATE check_runs
      SET status = ?, started_at = NULL, finished_at = NULL
      WHERE run_id = ? AND status = ?
      """;

  static final String INSERT_CHECK_RUN_LOG = """
      INSERT INTO check_run_logs (
        log_id, run_id, level, message, created_at
      ) VALUES (?, ?, ?, ?, ?)
      """;

  static final String UPDATE_RUN_RESULT = """
      UPDATE check_runs
      SET status = ?, breaking_changes = ?, warnings = ?, finished_at = ?
      WHERE run_id = ? AND status = ?
      """;

  static final String INSERT_CHECK_RUN = """
      INSERT INTO check_runs (
        run_id, contract_id, base_version, candidate_version, status,
        breaking_changes, warnings, commit_sha, created_at,
        triggered_by, compatibility_mode, input_hash, started_at, finished_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  static final String INSERT_AUDIT_LOG = """
      INSERT INTO audit_logs (
        audit_id, action, actor, actor_roles, source, request_id, http_method, path,
        resource_type, resource_id, status, detail, created_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  static final String INSERT_NOTIFICATION_DELIVERY = """
      INSERT INTO notification_deliveries (
        delivery_id, event_id, event_type, severity, occurred_at,
        contract_id, run_id, base_version, candidate_version, commit_sha, triggered_by,
        policy_pack, summary, breaking_changes, warnings, links, dedupe_key, sink_name,
        status, attempt_count, created_at, last_attempt_at, delivered_at, next_attempt_at,
        failure_message
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  static final String FIND_NOTIFICATION_DELIVERY_BY_DEDUPE = """
      SELECT delivery_id, event_id, event_type, severity, occurred_at,
             contract_id, run_id, base_version, candidate_version, commit_sha, triggered_by,
             policy_pack, summary, breaking_changes, warnings, links, dedupe_key, sink_name,
             status, attempt_count, created_at, last_attempt_at, delivered_at, next_attempt_at,
             failure_message
      FROM notification_deliveries
      WHERE dedupe_key = ? AND sink_name = ?
      LIMIT 1
      """;

  static final String FIND_NOTIFICATION_DELIVERY_BY_ID = """
      SELECT delivery_id, event_id, event_type, severity, occurred_at,
             contract_id, run_id, base_version, candidate_version, commit_sha, triggered_by,
             policy_pack, summary, breaking_changes, warnings, links, dedupe_key, sink_name,
             status, attempt_count, created_at, last_attempt_at, delivered_at, next_attempt_at,
             failure_message
      FROM notification_deliveries
      WHERE delivery_id = ?
      LIMIT 1
      """;

  static final String SELECT_NEXT_NOTIFICATION_DELIVERY = """
      SELECT delivery_id, event_id, event_type, severity, occurred_at,
             contract_id, run_id, base_version, candidate_version, commit_sha, triggered_by,
             policy_pack, summary, breaking_changes, warnings, links, dedupe_key, sink_name,
             status, attempt_count, created_at, last_attempt_at, delivered_at, next_attempt_at,
             failure_message
      FROM notification_deliveries
      WHERE ((status = ? OR status = ?)
          AND (next_attempt_at IS NULL OR next_attempt_at <= ?))
         OR (status = ? AND last_attempt_at <= ?)
      ORDER BY created_at ASC, delivery_id ASC
      LIMIT 1
      """;

  static final String CLAIM_NOTIFICATION_DELIVERY = """
      UPDATE notification_deliveries
      SET status = ?, attempt_count = attempt_count + 1, last_attempt_at = ?
      WHERE delivery_id = ? AND status = ?
      """;

  static final String MARK_NOTIFICATION_DELIVERY_DELIVERED = """
      UPDATE notification_deliveries
      SET status = ?, delivered_at = ?, next_attempt_at = NULL
      WHERE delivery_id = ? AND status = ?
      """;

  static final String MARK_NOTIFICATION_DELIVERY_FAILED = """
      UPDATE notification_deliveries
      SET status = ?, failure_message = ?, next_attempt_at = ?
      WHERE delivery_id = ? AND status = ?
      """;

  static final String REQUEUE_NOTIFICATION_DELIVERY = """
      UPDATE notification_deliveries
      SET status = ?, next_attempt_at = ?, delivered_at = NULL
      WHERE delivery_id = ? AND status IN (?, ?)
      """;

  static final String LIST_NOTIFICATION_DELIVERIES_BASE = """
      SELECT delivery_id, event_id, event_type, severity, occurred_at,
             contract_id, run_id, base_version, candidate_version, commit_sha, triggered_by,
             policy_pack, summary, breaking_changes, warnings, links, dedupe_key, sink_name,
             status, attempt_count, created_at, last_attempt_at, delivered_at, next_attempt_at,
             failure_message
      FROM notification_deliveries
      WHERE 1=1
      """;

  static final String SELECT_LEGACY_RUNS_FOR_BACKFILL = """
      SELECT run_id, contract_id, base_version, candidate_version, commit_sha, created_at, status,
             triggered_by, compatibility_mode, input_hash, started_at, finished_at
      FROM check_runs
      WHERE triggered_by IS NULL
         OR compatibility_mode IS NULL
         OR input_hash IS NULL
         OR started_at IS NULL
         OR finished_at IS NULL
      """;

  static final String UPDATE_LEGACY_RUN_BACKFILL = """
      UPDATE check_runs
      SET triggered_by = ?, compatibility_mode = ?, input_hash = ?, started_at = ?, finished_at = ?
      WHERE run_id = ?
      """;

  static final String CHECK_LOG_EXISTS = """
      SELECT 1
      FROM check_run_logs
      WHERE run_id = ?
      LIMIT 1
      """;

  static final String HEALTH_CHECK = "SELECT 1";

  private CheckRunSqlQueries() {}
}
