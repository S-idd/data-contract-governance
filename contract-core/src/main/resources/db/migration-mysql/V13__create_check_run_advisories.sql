CREATE TABLE check_run_advisories (
  run_id VARCHAR(64) PRIMARY KEY,
  status VARCHAR(32) NOT NULL,
  advisory_only BOOLEAN NOT NULL,
  test_only_adapter BOOLEAN NOT NULL,
  model_version VARCHAR(128),
  model_artifact_sha256 VARCHAR(64),
  feature_schema_version VARCHAR(128),
  input_hash VARCHAR(64),
  prediction_label VARCHAR(32),
  probabilities_json LONGTEXT,
  seed_predictions_json LONGTEXT,
  inference_duration_ms BIGINT NOT NULL,
  agreement VARCHAR(32) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  completed_at VARCHAR(40) NOT NULL,
  CONSTRAINT fk_advisory_run FOREIGN KEY (run_id) REFERENCES check_runs(run_id)
) ENGINE=InnoDB;
