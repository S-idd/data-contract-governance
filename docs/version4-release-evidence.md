# Version 4 Release Evidence

- Plan ID: `PLAN-2026-V4`
- Status: In progress
- Last updated: `2026-08-15`

This record captures reproducible V4 verification without including credentials, account identifiers, database passwords, or webhook secrets.

## 1. S3 Artifact Evidence

- AWS bucket: `dcg-demo`; region: `eu-north-1`; artifact prefix: `contracts`.
- The service ran with the S3 artifact backend and fallback reads disabled.
- The smoke contract `s3.smoke.20260815` created `metadata.yaml`, v1/v2 `schema.json`, and corresponding `schema.sha256` objects.
- Bucket versioning was enabled. The v2 schema was delete-marked, restored from its prior version, and read through the API after a service recreation.
- The S3 demo script was hardened to pass the resolved AWS profile and region to every AWS command, including commands loaded from a saved environment file.

## 2. Focused Backend Verification

Command:

```bash
./mvnw -pl contract-service -am \
  -Dtest=ArtifactStoreBackendSelectionTest,S3ArtifactStoreTest,S3ArtifactStoreConfigTest,S3ProductionProfileConfigurationTest,CheckRunStoreSqliteContractTest,CheckRunStorePostgresContractTest,CheckRunStorePostgresPathTest,CheckRunStoreMySqlContractTest,CheckRunStoreMySqlPathTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

Result: `34` tests passed; `0` failures; `0` errors; `12` explicit environment-dependent PostgreSQL/MySQL skips. SQLite, filesystem/backend selection, S3 behavior, and S3 production-profile defaults completed locally.

## 3. AWS Operational Evidence

The working `dcg-demo` profile was not altered.

1. Wrong-region endpoint: an unauthenticated request to the bucket's `ap-south-1` endpoint returned `301 Moved Permanently` and `x-amz-bucket-region: eu-north-1`.
2. Missing bucket: an S3 `HeadBucket` request against a unique non-existent bucket returned `404 Not Found`.
3. Invalid credentials: an STS request with deliberately invalid temporary values returned `InvalidClientTokenId`.
4. Permission denied: the separate, deliberately restricted `dcg-demo-denied` profile authenticated successfully but received `403 Forbidden` for both `HeadBucket` and `HeadObject`. The working `dcg-demo` profile was not changed or weakened.
5. Current bucket controls: versioning is enabled; public access block is fully enabled; default encryption is SSE-S3 (`AES256`); and SSE-C uploads are blocked.
6. Approved operating policy: current objects have no expiration rule; noncurrent versions under `contracts/` are retained for `90` days; incomplete multipart uploads under that prefix are aborted after `7` days.
7. Cost ownership: bucket tags record `Project=data-contract-governance` and `CostOwner=DCG-maintainer`. The DCG maintainer/account owner owns AWS cost monitoring.

## 4. Database Recovery Drills

All drills used disposable state and a separate recovery target. They did not alter the running Compose PostgreSQL database or the AWS S3 bucket.

1. SQLite production-lite: hot backup, `PRAGMA integrity_check`, simulated primary loss, restore, restart, and persisted check-run retrieval passed.
2. PostgreSQL: custom-format source-schema backup, restore into a separate database, Flyway-history and `notification_deliveries` verification, restart, and persisted check-run retrieval passed.
3. MySQL: consistent logical source-database backup, restore into a separate database, Flyway-history and `notification_deliveries` verification, restart, and persisted check-run retrieval passed.

## 5. Clean-Checkout Compose Rehearsal

1. A detached clean checkout at the reviewed V4 commit used the Compose template to create its local environment. The current equivalent is `config/compose.live-demo.env.example` copied to `.env.live-demo`.
2. `scripts/demo/run-compose-demo.sh` built the service image, started PostgreSQL and contract-service, and reached `/actuator/health`.
3. The authenticated sample check submission succeeded and returned a check-run identifier.
4. The disposable Compose stack, temporary checkout, and its named PostgreSQL volume were removed. The original Compose stack was restored and returned healthy.

## 6. Spring Boot Rehearsals

The separate `examples/spring-boot-realworld-demo` completed two rehearsals. The first used isolated ports `8090` and `8091` while Compose continued on `8080`. The second was run from the clean checkout on ports `8080` and `8081` after temporarily stopping Compose.

1. The demo application built as an executable Spring Boot jar from clean Java 21 sources.
2. The isolated contract service started with a 1000 ms check-runner polling interval.
3. `orders.created` v1, v2, and v3 were registered.
4. The CLI and SDK compatible flow completed with `PASS`.
5. The CLI and SDK breaking flow completed with `FAIL` as expected.
6. The webhook receiver recorded `CONTRACT_CHECK_FAILED`.
7. During the second rehearsal, a valid starter-validated order returned `201`, an invalid order returned `400`, the failed-check UI returned `200`, and the webhook inbox returned `200` with the expected event.
8. Temporary demo processes, the clean checkout, its generated runtime state, and its Compose volume were removed after the rehearsal. The original Compose stack was restored healthy.

## 7. Gates Still Requiring Maintainer or Operating-Team Input

1. Support decisions recorded: PostgreSQL is `Production standard`; SQLite is `Production-lite`; MySQL is `GA`; and S3 remains `Beta` for V4.
2. Capture two adopter/onboarding feedback records, or formally carry the postponed sessions into the next release plan.
3. Record the final V4 acceptance or carry-forward decision.
