CREATE TABLE IF NOT EXISTS check_runs (
  run_id TEXT PRIMARY KEY,
  contract_id TEXT NOT NULL,
  base_version TEXT NOT NULL,
  candidate_version TEXT NOT NULL,
  status TEXT NOT NULL,
  breaking_changes TEXT,
  warnings TEXT,
  commit_sha TEXT,
  created_at TEXT NOT NULL
);
