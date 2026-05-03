CREATE INDEX idx_check_runs_status_created_at_run_id
  ON check_runs (status, created_at, run_id);

CREATE INDEX idx_check_runs_commit_sha_created_at_run_id
  ON check_runs (commit_sha, created_at, run_id);

CREATE INDEX idx_check_runs_contract_status_created_at_run_id
  ON check_runs (contract_id, status, created_at, run_id);
