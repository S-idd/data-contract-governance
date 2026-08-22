ALTER TABLE check_runs
  ADD COLUMN idempotency_key VARCHAR(191) CHARACTER SET ascii NULL;

CREATE UNIQUE INDEX uq_check_runs_idempotency_key
  ON check_runs (idempotency_key);
