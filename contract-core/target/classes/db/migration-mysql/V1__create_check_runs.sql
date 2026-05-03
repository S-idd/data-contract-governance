CREATE TABLE IF NOT EXISTS check_runs (
  run_id VARCHAR(64) PRIMARY KEY,
  contract_id VARCHAR(255) NOT NULL,
  base_version VARCHAR(255) NOT NULL,
  candidate_version VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL,
  breaking_changes LONGTEXT,
  warnings LONGTEXT,
  commit_sha VARCHAR(255),
  created_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB;
