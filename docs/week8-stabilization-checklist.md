# Week 8 Stabilization Checklist (Release Candidate)

This checklist is the Week 8 execution plan for Apr 27-May 1.

## Current Status (Updated: 2026-03-10)

- [x] Full repo regression passes locally with `mvn test`
- [x] Full service regression passes locally with `mvn -pl contract-service -am -Dsurefire.failIfNoSpecifiedTests=false test`
- [x] API error contract coverage verified for `400`, `404`, and `503`
- [x] UI/security smoke remains covered by integration tests (`/ui`, `/ui/contracts`, `/ui/checks/<runId>`, auth-required `/checks`)
- [x] Release-critical regression fixed: DB-failure integration test no longer depends on opening a random local port
- [x] Postgres-path suite now reports explicit skips when local PostgreSQL is unavailable instead of a misleading `0 tests`
- [x] Live local PostgreSQL success/auth/schema-mismatch verification completed on 2026-03-10 with `Skipped: 0`
- [x] Demo runner evidence (`bash scripts/demo/run-local-demo.sh`) completed on 2026-03-10
- [x] Release notes draft added at `docs/release-notes-week8-rc.md`

## Week 8 Exit Criteria

- [x] `BUILD SUCCESS` on full service test run
- [x] No open P0/P1 issues
- [x] Demo flow and docs complete and verified
- [x] Non-critical changes frozen
- [x] Release notes drafted

## Monday (Apr 27)

- [x] Freeze new feature intake
- [x] Re-run full test suites (SQLite + Postgres paths)
- [x] Open bug list with severity tags (P0/P1/P2)

## Tuesday (Apr 28)

- [x] Fix all P0 issues
- [x] Re-test fixed paths
- [x] Verify API error contract stability (`400`, `404`, `503`)

## Wednesday (Apr 29)

- [x] Fix all P1 issues
- [x] Run UI smoke pass (`/ui`, `/ui/contracts`, `/ui/checks/<runId>`)
- [x] Validate security-enabled smoke (`APP_SECURITY_ENABLED=true`)

## Thursday (Apr 30)

- [x] Docs hardening pass (`README.md`, `quickstart-local.md`, `docs/demo-walkthrough.md`)
- [x] Validate setup from clean terminal in <= 15 minutes
- [x] Capture final release demo evidence (commands + screenshots)

## Friday (May 1)

- [x] Final regression run
- [x] Confirm no P0/P1 open
- [x] Publish release notes draft
- [x] Mark v1 candidate ready

## Mandatory Verification Commands

```bash
cd /path/to/data-contract-governance
mvn -pl contract-service -am -Dsurefire.failIfNoSpecifiedTests=false test
```

```bash
cd /path/to/data-contract-governance
mvn -pl contract-service -am \
  -Dtest=CheckRunStorePostgresPathTest,CheckControllerPostgresSuccessIntegrationTest,CheckControllerPostgresAuthFailureIntegrationTest,CheckControllerPostgresNetworkFailureIntegrationTest,CheckControllerPostgresSchemaMismatchIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

```bash
cd /path/to/data-contract-governance/contract-service
APP_SECURITY_ENABLED=true \
APP_SECURITY_USERNAME=demo \
APP_SECURITY_PASSWORD=demo-secret \
mvn spring-boot:run
```

## Notes

- Local-only constraint is mandatory; do not introduce Docker/Testcontainers.
- Existing endpoint compatibility must remain intact.
- Treat non-zero skip counts in the Postgres-targeted suite as "Postgres not yet verified", not as release-complete.
