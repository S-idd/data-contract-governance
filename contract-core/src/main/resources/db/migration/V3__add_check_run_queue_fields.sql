ALTER TABLE check_runs ADD COLUMN triggered_by TEXT;
ALTER TABLE check_runs ADD COLUMN compatibility_mode TEXT;
ALTER TABLE check_runs ADD COLUMN input_hash TEXT;
ALTER TABLE check_runs ADD COLUMN started_at TEXT;
ALTER TABLE check_runs ADD COLUMN finished_at TEXT;
