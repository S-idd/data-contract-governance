# Version 4 Recovery and Incident Runbook

- Plan ID: `PLAN-2026-V4-RECOVERY`
- Status: `In progress`
- Scope: recovery evidence and first-response guidance for supported V4 storage paths
- Related plans: `docs/version4-production-readiness-release-plan.md`, `docs/Architecture-v4.md`

## 1. Safety Rules

1. Treat check-run metadata and contract artifacts as separate recovery domains. Recover metadata before declaring a check history restored; recover artifacts before retrying a missing-schema diagnosis.
2. Stop writes before restoring a primary metadata database. Do not restore over a live multi-writer deployment.
3. Never paste database passwords, AWS keys, WebHook headers, or `.env` contents into tickets, chat, or commits.
4. Preserve the failed database, artifact key, migration output, and service log before changing state. These are evidence for the forward fix.
5. Prefer recovery to a separate target first. Promote it only after integrity and application-level validation pass.

## 2. SQLite Production-Lite Recovery Drill

The V4 drill uses a temporary database and starts a real `sqlite-prod-lite` service. It proves WAL mode, hot backup, backup integrity, simulated primary loss, restore, startup integrity validation, and retrieval of the original check run after restart.

Prerequisites: Java 21, `curl`, `sqlite3`, and `lsof`.

From the repository root:

```bash
./mvnw -pl contract-service -am package
bash scripts/demo/run-sqlite-recovery-drill.sh
```

Expected completion includes:

```text
PASS: restored check <run-id> is readable after service restart.
SQLite production-lite backup, restore, integrity, and post-restore read checks completed.
```

To retain the temporary database, backup, and two service logs for inspection:

```bash
DCG_SQLITE_RECOVERY_KEEP_WORK_DIR=true \
bash scripts/demo/run-sqlite-recovery-drill.sh
```

For an actual single-node SQLite recovery, stop the service, restore a known-good `.backup` output, run `PRAGMA integrity_check`, then restart with `SPRING_PROFILES_ACTIVE=sqlite-prod-lite`. The detailed operating policy remains in [sqlite-prod-lite-runbook.md](sqlite-prod-lite-runbook.md).

## 3. PostgreSQL Restore Drill

PostgreSQL is the V4 production-standard metadata path. Restore into a new database first, validate the expected schema, `check_runs`, and Flyway history, then decide whether to promote it.

Run the isolated Docker drill from the repository root:

```bash
./mvnw -pl contract-service -am package
bash scripts/demo/run-postgres-recovery-drill.sh
```

It creates a fresh `postgres:16` container, a disposable source schema, and a separate restore database. Successful cleanup removes the container and every drill database. Set `DCG_POSTGRES_RECOVERY_KEEP_CONTAINER=true` only when inspecting drill evidence.

Expected completion includes:

```text
PASS: restored check <run-id> is readable from <restore-database> after service restart.
PostgreSQL backup, restore, Flyway history, notification table, and application read checks completed.
```

Use the complete manual procedure in [postgres-backup-restore-runbook.md](postgres-backup-restore-runbook.md) for a managed PostgreSQL target. A successful drill has all of the following evidence:

1. A timestamped custom-format `pg_dump` exists.
2. `pg_restore` succeeds against a separate restore database.
3. The target schema contains `check_runs`, `check_run_logs`, `audit_logs`, and `notification_deliveries`.
4. `flyway_schema_history` includes the expected successful migrations.
5. A DCG service configured against the recovered target answers health and a known check-run query.

## 4. MySQL Restore Expectations

MySQL remains a V4 beta metadata backend. Run the isolated Docker drill from the repository root:

```bash
./mvnw -pl contract-service -am package
bash scripts/demo/run-mysql-recovery-drill.sh
```

It creates a fresh `mysql:8.4` container, a disposable source database, and a separate restore database. Successful cleanup removes the container and every drill database. Set `DCG_MYSQL_RECOVERY_KEEP_CONTAINER=true` only when inspecting drill evidence.

Expected completion includes:

```text
PASS: restored check <run-id> is readable from <restore-database> after service restart.
MySQL backup, restore, Flyway history, notification table, and application read checks completed.
```

Before any stronger support label, maintainers must also capture external adopter evidence with these controls:

1. Create a logical backup using the team's approved MySQL backup tool.
2. Restore it into a new database or schema, never directly over the live primary.
3. Verify `check_runs`, `check_run_logs`, `audit_logs`, `notification_deliveries`, indexes, and Flyway history.
4. Start DCG against the restored target and read a known check run.
5. Record the exact server version, backup command, restore command, elapsed time, and any unsupported features.

Until that additional evidence exists, do not call MySQL GA or use it as the only recovery path for a critical deployment.

## 5. S3 Artifact Restore From Versioning

S3 remains a beta-to-GA candidate. The bucket used by the S3 demo has versioning enabled. Restore both the schema and its checksum from matching known-good object versions; restoring only one creates an inconsistent artifact pair.

```bash
export BUCKET=<artifact-bucket>
export KEY="contracts/<contract-id>/versions/<version>/schema.json"
export CHECKSUM_KEY="${KEY%.json}.sha256"

aws s3api list-object-versions --bucket "$BUCKET" --prefix "$KEY"
aws s3api list-object-versions --bucket "$BUCKET" --prefix "$CHECKSUM_KEY"

aws s3api get-object \
  --bucket "$BUCKET" --key "$KEY" --version-id <known-good-schema-version-id> \
  /tmp/dcg-schema.json
aws s3api get-object \
  --bucket "$BUCKET" --key "$CHECKSUM_KEY" --version-id <known-good-checksum-version-id> \
  /tmp/dcg-schema.sha256

aws s3 cp /tmp/dcg-schema.json "s3://$BUCKET/$KEY"
aws s3 cp /tmp/dcg-schema.sha256 "s3://$BUCKET/$CHECKSUM_KEY"
```

Then restart or retry the affected service request with `CONTRACTS_ARTIFACT_S3_FALLBACK_ENABLED=false`, and verify the contract version through the API. The broader S3 setup, IAM, and smoke flow is in [week13-s3-beta-runbook.md](week13-s3-beta-runbook.md).

## 6. Incident Checklists

### Metadata Database Unavailable

1. Confirm `/actuator/health` and capture the sanitized failure message and request ID.
2. Confirm the configured backend, host, database path or URL, and referenced credential variable names without printing secret values.
3. Stop write traffic if the issue is a restore, corruption, or migration incident.
4. For SQLite, check disk availability and `PRAGMA integrity_check` only after the service is stopped. For PostgreSQL or MySQL, use the database platform's health checks.
5. Restore to a recovery target when the primary cannot be repaired quickly, validate a known check run, then promote through the deployment process.

### Migration Failure

1. Stop the rollout and retain the application log plus the current Flyway history.
2. Do not edit an already-applied versioned migration and do not delete Flyway history rows to force startup.
3. Compare the failed target with the last successful backup or recovery target.
4. Restore if the schema is unsafe or partial, then ship a forward-fix migration after review.
5. Re-run health, migration-history, and known check-run queries before reopening writes.

### Credential Or Permission Failure

1. Verify the secret reference is present without echoing its value, for example `test -n "${CHECKS_DB_PASSWORD:-}"`.
2. Verify the identity has the least privileges required for the selected metadata or artifact backend.
3. Check endpoint, region, TLS, and IAM/DB role configuration before rotating a credential.
4. Rotate through the team's secret manager or deployment environment, restart the affected service, and confirm health.
5. Search the incident material for accidental secret exposure and rotate immediately if one occurred.

### S3 Artifact Store Unavailable

1. Capture the contract ID, version, object key, region, and sanitized service error.
2. Verify bucket existence, region, IAM access, encryption/KMS permissions, and the configured prefix.
3. Keep local fallback disabled in production-like verification so a missing S3 object is not silently masked.
4. If a version was deleted or mutated, restore the schema and checksum pair from bucket versioning as described above.
5. Verify the restored contract through the API and record the new object version IDs.

### Partial Artifact Write

1. Stop or pause writes to the affected contract until the artifact pair is understood.
2. Compare the expected `metadata.yaml`, `schema.json`, and `schema.sha256` keys with the bucket or filesystem contents.
3. Preserve the partial objects and relevant audit/check logs before cleanup.
4. Restore the last known-good schema and checksum pair, or delete only the newly-created incomplete version after approval.
5. Retry the contract write with its original idempotency/commit context, then read the version and run a compatibility check.

## 7. Recovery Evidence Record

For every drill or real incident, record:

1. Backend and artifact store type.
2. Start time, end time, and operator.
3. Backup or source version identity.
4. Integrity, migration-history, object-pair, and application-level query results.
5. Recovery target and promotion decision.
6. Follow-up owner for any gap found during the drill.
