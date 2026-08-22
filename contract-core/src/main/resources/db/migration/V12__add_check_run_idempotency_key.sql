ALTER TABLE check_runs ADD COLUMN idempotency_key TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS uq_check_runs_idempotency_key
  ON check_runs (idempotency_key);
