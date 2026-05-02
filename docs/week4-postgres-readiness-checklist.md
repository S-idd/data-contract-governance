# Week 4: Postgres Hardening Readiness Checklist

- Week: `4`
- Date: `2026-03-31`
- Status: `Ready for review`
- Owner: `DCG maintainers`
- Exit target: `Postgres readiness checklist pass`

## 1. Production Config Baseline

Configured in `contract-service/src/main/resources/application-prod.properties`:

1. Secure transport defaults:
   - `checks.db.ssl.enabled=true`
   - `checks.db.ssl.mode=verify-full`
2. Startup safety defaults:
   - `checks.db.fail-fast-startup=true`
   - `checks.db.enforce-secure-postgres=true`
3. Pool and timeout defaults:
   - `checks.db.query-timeout=3s`
   - `checks.db.pool.minimum-idle=2`
   - `checks.db.pool.maximum-size=20`
   - `checks.db.pool.connection-timeout=3s`
   - `checks.db.pool.validation-timeout=2s`
   - `checks.db.pool.idle-timeout=5m`
   - `checks.db.pool.max-lifetime=30m`
   - `checks.db.pool.initialization-fail-timeout=1s`

## 2. Connection Pool Policy

Policy:

1. Use small stable pools by default (`min-idle=2`, `max-size=20`) for predictable memory and connection limits.
2. Keep connection timeout strict (`3s`) to fail fast under saturation.
3. Keep validation timeout strict (`2s`) to detect stale/broken connections quickly.
4. Keep max lifetime bounded (`30m`) to avoid long-lived connection drift.

## 3. Migration Safety Checks

Implemented safety controls:

1. Shared Flyway migration source for service and CLI (`contract-core/src/main/resources/db/migration`).
2. Service startup verifies migration resources exist before boot.
3. `checks.db.fail-fast-startup=true` causes startup failure when DB init/migrations fail.
4. Optional schema guard:
   - `CHECKS_DB_EXPECTED_SCHEMA` must match JDBC `currentSchema` when set.
5. Migrations are append-only and forward-fix based.

## 4. Indexing Review

Current migration-backed indexes:

1. Existing (V2):
   - `idx_check_runs_contract_id_created_at`
   - `idx_check_runs_commit_sha`
2. Added in Week 4 (V6):
   - `idx_check_runs_status_created_at_run_id`
   - `idx_check_runs_commit_sha_created_at_run_id`
   - `idx_check_runs_contract_status_created_at_run_id`

Query-path alignment:

1. Queue claim (`status=QUEUED`, oldest first): covered by `status, created_at, run_id`.
2. History by commit with ordering: covered by `commit_sha, created_at, run_id`.
3. Filtered page/list by contract+status with ordering: covered by `contract_id, status, created_at, run_id`.
4. Deterministic ordering in service layer: `ORDER BY created_at DESC, run_id DESC`.

## 5. Backup/Restore Runbook

See:

- `docs/postgres-backup-restore-runbook.md`

## 6. Verification Commands

Postgres suite verification:

```bash
export TEST_POSTGRES_JDBC_URL="jdbc:postgresql://localhost:5432/contracts?currentSchema=dcg_dev"
export TEST_POSTGRES_USERNAME="siddarthkanamadi"
export TEST_POSTGRES_PASSWORD=""
./mvnw clean test
```

Targeted Postgres test run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
mvn -pl contract-service -Dtest='*Postgres*' \
  -Dtest.postgres.jdbc-url='jdbc:postgresql://localhost:5432/contracts?currentSchema=dcg_dev' \
  -Dtest.postgres.username='siddarthkanamadi' \
  -Dtest.postgres.password='' test
```

Index inspection:

```bash
psql -h localhost -p 5432 -U siddarthkanamadi -d contracts -c \
  "select schemaname, tablename, indexname from pg_indexes where schemaname='dcg_dev' order by tablename, indexname;"
```

## 7. Checklist

- [x] Production Postgres security config defaults are defined.
- [x] Connection pool policy is defined and encoded in prod profile defaults.
- [x] Migration safety checks are implemented and documented.
- [x] Indexing review completed and new operational indexes added.
- [x] Backup/restore runbook published.
- [x] Postgres-focused tests execute with zero skips on local verified setup.

## 8. Local Verification Snapshot (2026-03-31)

1. `mvn -pl contract-service -Dtest='*Postgres*' ...` executed with:
   - `Tests run: 11`
   - `Failures: 0`
   - `Errors: 0`
   - `Skipped: 0`
2. `PostApiStrictIntegrationTest` and full project tests also passed in the same development cycle.
