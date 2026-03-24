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
      ORDER BY created_at ASC
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
