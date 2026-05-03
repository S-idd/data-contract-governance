CREATE TABLE IF NOT EXISTS check_run_logs (
  log_id VARCHAR(64) PRIMARY KEY,
  run_id VARCHAR(64) NOT NULL,
  level VARCHAR(16) NOT NULL,
  message LONGTEXT NOT NULL,
  created_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB;

CREATE INDEX idx_check_run_logs_run_id_created_at
  ON check_run_logs (run_id, created_at);
