CREATE INDEX IF NOT EXISTS idx_check_runs_contract_id_created_at
  ON check_runs (contract_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_check_runs_commit_sha
  ON check_runs (commit_sha);
