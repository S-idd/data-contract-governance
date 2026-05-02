CREATE INDEX IF NOT EXISTS idx_check_runs_status_created_at_run_id
  ON check_runs (status, created_at ASC, run_id ASC);

CREATE INDEX IF NOT EXISTS idx_check_runs_commit_sha_created_at_run_id
  ON check_runs (commit_sha, created_at DESC, run_id DESC);

CREATE INDEX IF NOT EXISTS idx_check_runs_contract_status_created_at_run_id
  ON check_runs (contract_id, status, created_at DESC, run_id DESC);
