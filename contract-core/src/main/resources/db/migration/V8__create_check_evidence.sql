CREATE TABLE IF NOT EXISTS check_evidence (
  evidence_id TEXT PRIMARY KEY,
  idempotency_key TEXT NOT NULL,
  payload_sha256 TEXT NOT NULL,
  contract_id TEXT NOT NULL,
  base_version TEXT NOT NULL,
  candidate_version TEXT NOT NULL,
  compatibility_mode TEXT NOT NULL,
  commit_sha TEXT,
  base_schema_sha256 TEXT NOT NULL,
  candidate_schema_sha256 TEXT NOT NULL,
  engine_version TEXT NOT NULL,
  engine_compatibility_protocol TEXT NOT NULL,
  policy_pack_name TEXT NOT NULL,
  policy_pack_sha256 TEXT NOT NULL,
  local_status TEXT NOT NULL,
  breaking_changes TEXT NOT NULL,
  warnings TEXT NOT NULL,
  executed_at TEXT NOT NULL,
  ci_identity TEXT NOT NULL,
  build_url TEXT,
  raw_evidence TEXT NOT NULL,
  authenticated_identity TEXT NOT NULL,
  import_status TEXT NOT NULL,
  verification_reason TEXT,
  authoritative_run_id TEXT,
  imported_at TEXT NOT NULL,
  CONSTRAINT uq_check_evidence_idempotency UNIQUE (idempotency_key),
  FOREIGN KEY (authoritative_run_id) REFERENCES check_runs(run_id)
);

CREATE INDEX IF NOT EXISTS idx_check_evidence_contract_imported
  ON check_evidence (contract_id, imported_at DESC, evidence_id DESC);

CREATE INDEX IF NOT EXISTS idx_check_evidence_status_imported
  ON check_evidence (import_status, imported_at DESC, evidence_id DESC);
