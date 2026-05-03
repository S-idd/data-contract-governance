ALTER TABLE check_runs ADD COLUMN triggered_by VARCHAR(255);
ALTER TABLE check_runs ADD COLUMN compatibility_mode VARCHAR(32);
ALTER TABLE check_runs ADD COLUMN input_hash VARCHAR(128);
ALTER TABLE check_runs ADD COLUMN started_at VARCHAR(40);
ALTER TABLE check_runs ADD COLUMN finished_at VARCHAR(40);
