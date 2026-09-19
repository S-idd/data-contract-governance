CREATE TABLE check_run_advisories (
  run_id TEXT PRIMARY KEY REFERENCES check_runs(run_id),
  status TEXT NOT NULL,
  advisory_only BOOLEAN NOT NULL,
  test_only_adapter BOOLEAN NOT NULL,
  model_version TEXT,
  model_artifact_sha256 TEXT,
  feature_schema_version TEXT,
  input_hash TEXT,
  prediction_label TEXT,
  probabilities_json TEXT,
  seed_predictions_json TEXT,
  inference_duration_ms BIGINT NOT NULL,
  agreement TEXT NOT NULL,
  created_at TEXT NOT NULL,
  completed_at TEXT NOT NULL
);
