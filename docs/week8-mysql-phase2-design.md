# Week 8: Phase 2 MySQL Design

- Plan ID: `PLAN-2026-W8-MYSQL-DESIGN`
- Date: `2026-05-02`
- Status: `Approved`
- Scope: `MySQL phase-2 architecture + migration + concurrency strategy`
- Exit target: `MySQL implementation plan approved`

## 1. Goals

1. Add MySQL as a third metadata backend without changing caller contracts.
2. Keep PostgreSQL and SQLite behavior stable.
3. Define a migration strategy that works safely with Flyway on MySQL.

## 2. SQL Dialect Gap Analysis

| Area | PostgreSQL/SQLite path | MySQL gap | Decision |
|---|---|---|---|
| Primary key column type | `TEXT PRIMARY KEY` in shared migrations | MySQL does not allow `TEXT` primary key without prefix | Use `VARCHAR` primary keys in MySQL-specific migrations |
| Conditional index DDL | `CREATE INDEX IF NOT EXISTS` in shared migrations | Not portable across MySQL versions | Use plain `CREATE INDEX` in versioned MySQL migrations |
| Large detail payload columns | `TEXT` fields for warnings/breaking changes/detail | Needs explicit MySQL-compatible large text type | Use `LONGTEXT` in MySQL migrations |
| Backend migration path | Single `db/migration` location | Shared SQL is not fully MySQL-compatible | Route MySQL to `db/migration-mysql` |
| Queue claim SQL | `SELECT queued` + conditional `UPDATE ... WHERE status=?` | Potential contention under concurrent claimers | Keep optimistic update pattern, validate with contract tests |

## 3. Flyway Strategy for MySQL

1. Keep existing shared migration chain (`db/migration`) unchanged for PostgreSQL + SQLite.
2. Add a parallel MySQL migration chain at `contract-core/src/main/resources/db/migration-mysql` with matching versions `V1..V6`.
3. Runtime selection rule:
   - `jdbc:mysql:*` -> `classpath:db/migration-mysql`
   - all other current backends -> `classpath:db/migration`
4. Keep `baselineOnMigrate=true` and `baselineVersion=0` for consistent behavior across all DBs.

## 4. Transaction and Locking Review

1. `claimNextQueuedRun` remains a two-step optimistic pattern:
   - read one queued row by oldest `created_at, run_id`
   - transition with `UPDATE ... WHERE run_id=? AND status='QUEUED'`
2. Concurrency behavior is enforced by status predicate, so only one transaction wins each run claim.
3. Retry loop (`3` attempts) remains in place for benign races.
4. No `FOR UPDATE SKIP LOCKED` dependency is introduced in this phase, keeping SQL portable and predictable.

## 5. Compatibility Test Strategy (3-DB Matrix)

1. Continue existing SQLite contract suite as always-on local baseline.
2. Continue PostgreSQL path + contract suites under existing `TEST_POSTGRES_*` env vars.
3. Add MySQL contract + path suites under new `TEST_MYSQL_*` env vars.
4. Skip logic is explicit when local MySQL is unavailable; release readiness requires zero skips for targeted MySQL verification runs.

## 6. Week 8 Exit Check

- [x] SQL dialect gaps identified and decisions captured.
- [x] Flyway strategy for MySQL finalized.
- [x] Transaction/locking semantics reviewed.
- [x] Test strategy for 3-DB matrix documented.

## 7. Week 9 Implementation Scope

1. Add MySQL JDBC and Flyway modules to CLI and service.
2. Add MySQL migrations (`V1..V6`) in `migration-mysql`.
3. Implement MySQL backend routing in service + CLI Flyway bootstrap.
4. Add MySQL contract/path tests while preserving PostgreSQL/SQLite behavior.
