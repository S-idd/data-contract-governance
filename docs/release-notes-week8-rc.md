# Release Notes Draft: Week 8 RC

Date: 2026-03-10
Candidate: `0.1.0-rc1` (draft)

## Summary

This release-candidate pass focused on stabilization only: full local regression, test-harness hardening, and release-readiness reporting. No feature work was added in this pass.

## What Changed

- Fixed the DB-unavailable integration test to use `MockMvc` instead of opening a random local port.
- Hardened PostgreSQL integration tests so a missing local PostgreSQL dependency now shows up as explicit skipped tests instead of a misleading `0 tests`.
- Clarified local quickstart guidance so PostgreSQL verification requires both `BUILD SUCCESS` and zero skips for the availability-dependent PostgreSQL tests.

## Verified

- `mvn test`
  - Result: `BUILD SUCCESS`
  - Observed summary: `Tests run: 36, Failures: 0, Errors: 0, Skipped: 6`
- `mvn -pl contract-service -am -Dsurefire.failIfNoSpecifiedTests=false test`
  - Result: `BUILD SUCCESS`
- `mvn -pl contract-service -am -Dtest=CheckRunStorePostgresPathTest,CheckControllerPostgresSuccessIntegrationTest,CheckControllerPostgresAuthFailureIntegrationTest,CheckControllerPostgresNetworkFailureIntegrationTest,CheckControllerPostgresSchemaMismatchIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - Result: `BUILD SUCCESS`
  - Observed summary (2026-03-10): `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`
- Local-only failure handling remains covered for:
  - structured `503` on unavailable stores
  - structured `404` on missing runs
  - structured `400` on invalid paging input
  - UI route coverage and security-enabled coverage
- Local demo script (`bash scripts/demo/run-local-demo.sh`) executed successfully on 2026-03-10 with screenshots captured.

## Known Gate Before Final Sign-Off

- Postgres-path suite verified on 2026-03-10 with zero skips.
- The local demo runner (`bash scripts/demo/run-local-demo.sh`) was not revalidated in this environment for the same reason.

## Release Recommendation

Hold final Week 8 sign-off until one clean local PostgreSQL run completes with:

- `TEST_POSTGRES_JDBC_URL`, `TEST_POSTGRES_USERNAME`, and `TEST_POSTGRES_PASSWORD` set
- zero skips in the PostgreSQL availability-dependent tests
- successful execution of `bash scripts/demo/run-local-demo.sh`
