CREATE TABLE IF NOT EXISTS check_run_logs (
  log_id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  level TEXT NOT NULL,
  message TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_check_run_logs_run_id_created_at
  ON check_run_logs (run_id, created_at);
