# SQLite Production-Lite Runbook (DCG)

This runbook defines how to run SQLite safely for **single-node production-lite** deployments.

## 1) Intended Use

SQLite is supported only when all of the following are true:

1. One service instance (single writer process).
2. Local persistent disk on the same node.
3. Low/steady throughput and no HA/failover requirement.

Use profile:

```bash
SPRING_PROFILES_ACTIVE=sqlite-prod-lite
```

## 2) Required Runtime Policy

Policy baseline:

1. `WAL` mode enabled (`checks.db.sqlite.wal-enabled=true`).
2. Busy timeout configured (`checks.db.sqlite.busy-timeout=5s` default).
3. Single-node guardrail enabled (`checks.db.sqlite.enforce-single-node=true`).
4. Startup integrity check enabled (`checks.db.sqlite.integrity-check-on-startup=true`).
5. Pool constrained to one connection (`checks.db.pool.maximum-size=1`).

## 3) Verify SQLite Hardening

After startup:

```bash
sqlite3 "$CHECKS_DB_PATH" "PRAGMA journal_mode;"
sqlite3 "$CHECKS_DB_PATH" "PRAGMA synchronous;"
sqlite3 "$CHECKS_DB_PATH" "PRAGMA quick_check;"
```

Expected:

1. `journal_mode` is `wal`.
2. `synchronous` is `2` (FULL) or your configured mode.
3. `quick_check` returns `ok`.

## 4) Backup Guidance

Hot backup (preferred):

```bash
STAMP="$(date +%Y%m%d-%H%M%S)"
sqlite3 "$CHECKS_DB_PATH" ".backup '/var/backups/dcg/checks-${STAMP}.db'"
```

Also copy WAL/SHM only when doing filesystem-level backups:

```bash
cp "$CHECKS_DB_PATH" "/var/backups/dcg/checks-${STAMP}.db"
cp "${CHECKS_DB_PATH}-wal" "/var/backups/dcg/checks-${STAMP}.db-wal" 2>/dev/null || true
cp "${CHECKS_DB_PATH}-shm" "/var/backups/dcg/checks-${STAMP}.db-shm" 2>/dev/null || true
```

## 5) Restore Guidance

1. Stop the service process.
2. Restore DB file from known-good backup.
3. Run integrity validation before restart.

```bash
cp "/var/backups/dcg/checks-${STAMP}.db" "$CHECKS_DB_PATH"
sqlite3 "$CHECKS_DB_PATH" "PRAGMA integrity_check;"
```

Only restart if result is `ok`.

## 6) Integrity Checks

Daily recommended check:

```bash
sqlite3 "$CHECKS_DB_PATH" "PRAGMA quick_check;"
```

Weekly deep check:

```bash
sqlite3 "$CHECKS_DB_PATH" "PRAGMA integrity_check;"
```

## 7) When Not To Use SQLite

Do **not** use SQLite when you need:

1. Multi-instance API/runner deployment.
2. Cross-node writes or active-active topology.
3. High sustained write throughput.
4. Managed failover or strict uptime SLA.
5. Online schema operations coordinated across replicas.

In those cases, use PostgreSQL Tier A.
