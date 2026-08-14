CREATE TABLE IF NOT EXISTS notification_deliveries (
  delivery_id TEXT PRIMARY KEY,
  event_id TEXT NOT NULL,
  event_type TEXT NOT NULL,
  severity TEXT NOT NULL,
  occurred_at TEXT NOT NULL,
  contract_id TEXT,
  run_id TEXT,
  base_version TEXT,
  candidate_version TEXT,
  commit_sha TEXT,
  triggered_by TEXT,
  policy_pack TEXT,
  summary TEXT NOT NULL,
  breaking_changes TEXT NOT NULL,
  warnings TEXT NOT NULL,
  links TEXT NOT NULL,
  dedupe_key TEXT NOT NULL,
  sink_name TEXT NOT NULL,
  status TEXT NOT NULL,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  last_attempt_at TEXT,
  delivered_at TEXT,
  next_attempt_at TEXT,
  failure_message TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_notification_deliveries_dedupe_sink
  ON notification_deliveries (dedupe_key, sink_name);

CREATE INDEX IF NOT EXISTS idx_notification_deliveries_dispatch
  ON notification_deliveries (status, next_attempt_at, created_at);
