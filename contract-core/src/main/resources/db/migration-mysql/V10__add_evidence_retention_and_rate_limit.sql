ALTER TABLE check_evidence ADD COLUMN raw_evidence_archived_at VARCHAR(40) NULL;
ALTER TABLE check_evidence ADD COLUMN raw_evidence_archive_location VARCHAR(2048) NULL;
ALTER TABLE check_evidence ADD COLUMN raw_evidence_archive_sha256 VARCHAR(64) NULL;

CREATE TABLE IF NOT EXISTS evidence_legal_holds (
  hold_id VARCHAR(64) PRIMARY KEY,
  evidence_id VARCHAR(64) NULL,
  contract_id VARCHAR(255) NULL,
  repository VARCHAR(512) NULL,
  active BOOLEAN NOT NULL,
  reason TEXT NOT NULL,
  created_by VARCHAR(512) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  released_by VARCHAR(512) NULL,
  released_at VARCHAR(40) NULL,
  CONSTRAINT fk_evidence_hold_evidence FOREIGN KEY (evidence_id) REFERENCES check_evidence(evidence_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS evidence_hold_events (
  event_id VARCHAR(64) PRIMARY KEY,
  hold_id VARCHAR(64) NOT NULL,
  action VARCHAR(32) NOT NULL,
  actor VARCHAR(512) NOT NULL,
  reason TEXT NOT NULL,
  occurred_at VARCHAR(40) NOT NULL,
  CONSTRAINT fk_evidence_hold_event_hold FOREIGN KEY (hold_id) REFERENCES evidence_legal_holds(hold_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS evidence_retention_events (
  event_id VARCHAR(64) PRIMARY KEY,
  evidence_id VARCHAR(64) NOT NULL,
  action VARCHAR(32) NOT NULL,
  policy_version VARCHAR(255) NOT NULL,
  archive_location VARCHAR(2048) NULL,
  archive_sha256 VARCHAR(64) NULL,
  actor VARCHAR(512) NOT NULL,
  occurred_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS evidence_rate_limit_buckets (
  bucket_key VARCHAR(1024) PRIMARY KEY,
  window_type VARCHAR(32) NOT NULL,
  window_started_at VARCHAR(40) NOT NULL,
  request_count INT NOT NULL,
  updated_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB;

CREATE INDEX idx_check_evidence_retention ON check_evidence (import_status, imported_at, evidence_id);
CREATE INDEX idx_evidence_legal_holds_active ON evidence_legal_holds (active, evidence_id, contract_id, repository);
