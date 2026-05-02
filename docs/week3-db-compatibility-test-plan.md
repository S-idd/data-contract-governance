# Week 3 Plan: DB Backend Compatibility Test Strategy

- Plan ID: `PLAN-2026-W3-DB-COMPAT`
- Date: `2026-03-26`
- Status: `Approved`
- Scope: `Postgres + SQLite metadata parity`
- Related:
  - `docs/week3-storage-interface-spec.md`
  - `contract-service/src/main/java/com/ideas/contracts/service/CheckRunRepository.java`
  - `contract-service/src/main/java/com/ideas/contracts/service/CheckRunStore.java`
  - `contract-service/src/test/java/com/ideas/contracts/service/CheckRunStoreTest.java`
  - `contract-service/src/test/java/com/ideas/contracts/service/CheckRunStorePostgresPathTest.java`
  - `contract-service/src/test/java/com/ideas/contracts/service/PostgresTestSupport.java`

## 1. Objective
Guarantee behavior parity for `MetadataStore` semantics across SQLite and Postgres in Phase 1.

## 2. Test Layers

### 2.1 Storage Contract Test Suite (Backend-Neutral)
A shared test suite must validate required semantics independent of database type:
1. queue creation, claim, and transition lifecycle.
2. claim concurrency behavior (single winner per queued run).
3. terminal transition idempotency (`completeRun` does not double-finalize).
4. pagination limit/offset behavior and ordering stability.
5. logs append and retrieval ordering.
6. audit log write/read visibility.
7. query filtering by `contractId`, `commitSha`, and `status`.

### 2.2 Backend Adapter Test Runs
Each contract suite test must execute against:
1. SQLite adapter path.
2. Postgres adapter path.

Execution policy:
1. SQLite tests run in default local CI path.
2. Postgres tests run when env vars are provided (local Postgres), no Docker requirement.

### 2.3 Migration Verification Tests
For each supported backend:
1. start from empty schema and run all Flyway migrations.
2. verify expected tables/indexes exist.
3. verify service can read/write immediately after migration.
4. verify startup failure path on schema mismatch where strict/fail-fast is enabled.

## 3. Test Matrix

| Capability | SQLite | Postgres | Notes |
|---|---|---|---|
| createQueuedRun | Required | Required | same response shape |
| claimNextQueuedRun | Required | Required | single-claimer guarantee |
| completeRun/requeueRun | Required | Required | idempotent transitions |
| list/listPage/findByRunId | Required | Required | ordering parity |
| appendLog/listLogs | Required | Required | message integrity |
| recordAuditLog | Required | Required | mandatory for writes |
| health/pool snapshots | Required | Required | backend-aware values |
| Flyway migration success | Required | Required | same version set |

## 4. Core Scenarios (Must Pass)
1. Happy path lifecycle:
   - create queued run
   - claim queued run
   - append progress logs
   - complete as PASS
   - read detail and logs
2. Retry path:
   - claim
   - failure + requeue
   - reclaim
   - complete as FAIL
3. Query path:
   - mixed contract IDs and commit SHAs
   - validate filters and paged results
4. Concurrency path:
   - concurrent claim attempts on same queued run set
   - exactly one claim per run
5. Failure path:
   - DB connection unavailable
   - verify clear `CheckRunStoreException` behavior

## 5. Data and Determinism Rules
1. Use fixed synthetic run IDs/commit SHAs where possible.
2. For timestamp-sensitive assertions, validate relative ordering and stable tie-breakers.
3. Avoid test dependence on real wall-clock timing outside bounded waits.

## 6. CI Strategy
1. Default pipeline:
   - run SQLite contract tests on every PR.
2. Optional gated pipeline (or nightly if infra constrained):
   - run Postgres contract tests + migration checks.
3. Release gate:
   - both SQLite and Postgres suites green before release tagging.

## 7. Exit Criteria (Week 3)
Week 3 test strategy is complete when:
1. shared storage contract test suite exists and is documented.
2. suite runs against both SQLite and Postgres paths.
3. migration verification tests are implemented for both backends.
4. CI wiring enforces parity checks before release.

## 8. Follow-Up for Week 4+
1. Extend matrix for MySQL only when Phase 2 begins.
2. Add performance regression checks for queue claim throughput.
