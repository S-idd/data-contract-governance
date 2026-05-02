# Week 5: SQLite Production-Lite Hardening Checklist

- Week: `5`
- Date: `2026-03-31`
- Status: `Ready for review`
- Exit target: `SQLite prod-lite checklist pass`

## 1. Policy Coverage

- [x] WAL mode policy defined and encoded.
- [x] Busy timeout policy defined and encoded.
- [x] Single-node guardrails implemented.
- [x] Backup/restore guidance documented.
- [x] Integrity checks documented and startup-check option implemented.
- [x] "When not to use SQLite" guidance documented.

Primary references:

- `contract-service/src/main/resources/application-sqlite-prod-lite.properties`
- `contract-service/src/main/java/com/ideas/contracts/service/CheckRunStore.java`
- `docs/sqlite-prod-lite-runbook.md`

## 2. Runtime Guardrails Implemented

1. `checks.db.sqlite.enforce-single-node=true` requires:
   - `checks.db.pool.maximum-size=1`
   - `checks.db.pool.minimum-idle<=1`
2. Startup pragma baseline includes:
   - `journal_mode=WAL` (when enabled)
   - `busy_timeout`
   - `synchronous` mode
   - `foreign_keys=ON`
3. Optional startup integrity gate:
   - `checks.db.sqlite.integrity-check-on-startup=true` runs `PRAGMA quick_check`.

## 3. Verification Commands

Run focused unit tests:

```bash
./mvnw -pl contract-service -Dtest=CheckRunStoreTest test
```

Run service in SQLite prod-lite profile:

```bash
SPRING_PROFILES_ACTIVE=sqlite-prod-lite \
CHECKS_DB_PATH=/tmp/dcg/checks.db \
APP_SECURITY_ENABLED=true \
./mvnw -pl contract-service spring-boot:run
```

Validate pragmas and integrity:

```bash
sqlite3 /tmp/dcg/checks.db "PRAGMA journal_mode;"
sqlite3 /tmp/dcg/checks.db "PRAGMA quick_check;"
```

## 4. Exit Criteria Snapshot

- [x] Configuration profile for SQLite production-lite added.
- [x] Guardrails fail fast on invalid multi-connection SQLite settings.
- [x] WAL/busy timeout settings applied at runtime.
- [x] Backup/restore + integrity procedures documented.
- [x] Limitations documented to avoid misuse.
