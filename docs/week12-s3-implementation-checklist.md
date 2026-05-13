# Week 12: S3 Implementation Checklist

- Plan ID: `PLAN-2026-W12-S3-IMPLEMENTATION`
- Date: `2026-05-13`
- Status: `Implemented and locally verified`
- Scope: `S3 artifact backend, IAM/default credential chain, filesystem fallback, integration/unit coverage`
- Exit target: `S3 feature complete`

## 1. Implementation Tasks

- [x] Add S3 runtime dependency (`software.amazon.awssdk:s3`).
- [x] Add S3 client config with AWS SDK default credential provider chain.
- [x] Support optional static key override for local/non-IAM testing.
- [x] Add `S3ArtifactStore` implementation behind `ArtifactStore`.
- [x] Keep filesystem backend as default path.
- [x] Add backend switch with `contracts.artifact.backend` config.
- [x] Add fallback-to-filesystem behavior for S3 runtime failures.
- [x] Keep `CheckRunner` compatible by resolving schema paths through `ArtifactStore`.
- [x] Add S3-focused tests and backend wiring integration tests, then verify they pass.

## 2. Config Surface

```properties
contracts.artifact.backend=filesystem|s3
contracts.artifact.s3.bucket=
contracts.artifact.s3.prefix=contracts
contracts.artifact.s3.region=us-east-1
contracts.artifact.s3.endpoint=
contracts.artifact.s3.path-style=false
contracts.artifact.s3.access-key=
contracts.artifact.s3.secret-key=
contracts.artifact.s3.fallback-enabled=true
```

Environment aliases in `application.properties`:

```bash
CONTRACTS_ARTIFACT_BACKEND
CONTRACTS_ARTIFACT_S3_BUCKET
CONTRACTS_ARTIFACT_S3_PREFIX
CONTRACTS_ARTIFACT_S3_REGION
CONTRACTS_ARTIFACT_S3_ENDPOINT
CONTRACTS_ARTIFACT_S3_PATH_STYLE
CONTRACTS_ARTIFACT_S3_ACCESS_KEY
CONTRACTS_ARTIFACT_S3_SECRET_KEY
CONTRACTS_ARTIFACT_S3_FALLBACK_ENABLED
```

## 3. Verification Commands

Module validation:

```bash
cd /path/to/data-contract-governance
./mvnw -pl contract-service -am test -Dsurefire.failIfNoSpecifiedTests=false
```

S3-focused tests:

```bash
cd /path/to/data-contract-governance
./mvnw -pl contract-service -am \
  -Dtest=ArtifactStoreBackendSelectionTest,S3ArtifactStoreTest,ArtifactKeyStrategyTest,CheckRunnerIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

Full reactor validation:

```bash
cd /path/to/data-contract-governance
./mvnw clean test -Dsurefire.failIfNoSpecifiedTests=false
```

## 4. Validation Snapshot

Latest local run on 2026-05-13:

1. `./mvnw -pl contract-service -am test -Dsurefire.failIfNoSpecifiedTests=false` -> `BUILD SUCCESS`
2. `./mvnw -pl contract-service -am -Dtest=ArtifactStoreBackendSelectionTest,S3ArtifactStoreTest,ArtifactKeyStrategyTest,CheckRunnerIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` -> `BUILD SUCCESS`, `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`
3. `./mvnw clean test -Dsurefire.failIfNoSpecifiedTests=false` -> `BUILD SUCCESS`

## 5. Exit Checklist

- [x] S3 adapter added without breaking existing filesystem behavior.
- [x] IAM-ready default credential chain wired.
- [x] Local fallback path behavior implemented.
- [x] S3 key strategy reused from Week 11.
- [x] New S3 tests pass.
- [x] Full project tests pass with Java 21.
