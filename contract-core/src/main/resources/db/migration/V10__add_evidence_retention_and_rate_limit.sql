ALTER TABLE check_evidence ADD COLUMN raw_evidence_archived_at TEXT;
ALTER TABLE check_evidence ADD COLUMN raw_evidence_archive_location TEXT;
ALTER TABLE check_evidence ADD COLUMN raw_evidence_archive_sha256 TEXT;

CREATE TABLE IF NOT EXISTS evidence_legal_holds (
  hold_id TEXT PRIMARY KEY,
  evidence_id TEXT,
  contract_id TEXT,
  repository TEXT,
  active INTEGER NOT NULL,
  reason TEXT NOT NULL,
  created_by TEXT NOT NULL,
  created_at TEXT NOT NULL,
  released_by TEXT,
  released_at TEXT,
  FOREIGN KEY (evidence_id) REFERENCES check_evidence(evidence_id)
);

CREATE TABLE IF NOT EXISTS evidence_hold_events (
  event_id TEXT PRIMARY KEY,
  hold_id TEXT NOT NULL,
  action TEXT NOT NULL,
  actor TEXT NOT NULL,
  reason TEXT NOT NULL,
  occurred_at TEXT NOT NULL,
  FOREIGN KEY (hold_id) REFERENCES evidence_legal_holds(hold_id)
);

CREATE TABLE IF NOT EXISTS evidence_retention_events (
  event_id TEXT PRIMARY KEY,
  evidence_id TEXT NOT NULL,
  action TEXT NOT NULL,
  policy_version TEXT NOT NULL,
  archive_location TEXT,
  archive_sha256 TEXT,
  actor TEXT NOT NULL,
  occurred_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS evidence_rate_limit_buckets (
  bucket_key TEXT PRIMARY KEY,
  window_type TEXT NOT NULL,
  window_started_at TEXT NOT NULL,
  request_count INTEGER NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_check_evidence_retention
  ON check_evidence (import_status, imported_at, evidence_id);
CREATE INDEX IF NOT EXISTS idx_evidence_legal_holds_active
  ON evidence_legal_holds (active, evidence_id, contract_id, repository);
