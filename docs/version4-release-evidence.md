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

## 3. Spring Boot Rehearsal

The separate `examples/spring-boot-realworld-demo` was run on isolated ports `8090` and `8091` while the Compose service continued on `8080`.

1. The demo application built as an executable Spring Boot jar from clean Java 21 sources.
2. The isolated contract service started with a 1000 ms check-runner polling interval.
3. `orders.created` v1, v2, and v3 were registered.
4. The CLI and SDK compatible flow completed with `PASS`.
5. The CLI and SDK breaking flow completed with `FAIL` as expected.
6. The webhook receiver recorded `CONTRACT_CHECK_FAILED`.
7. Temporary demo processes and `.runtime/` state were removed after the rehearsal.

## 4. Gates Still Requiring Maintainer or Operating-Team Input

1. Run IAM-denial checks with a deliberately restricted AWS profile or role; do not weaken or replace the working profile to create those failures.
2. Approve S3 lifecycle/retention window, encryption/KMS posture, and cost owner.
3. Decide whether S3 stays Beta, advances to RC, or advances to GA after the missing operational evidence is accepted.
4. Capture two adopter/onboarding feedback records, or formally carry the postponed sessions into the next release plan.
5. Record the final V4 acceptance or carry-forward decision.
