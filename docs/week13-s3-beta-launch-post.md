# Week 13: S3 Beta Launch Post

- Plan ID: `PLAN-2026-W13-S3-BETA`
- Status: `Published`
- Published date: `2026-05-23`
- Primary runbook: `docs/week13-s3-beta-runbook.md`
- Onboarding session: `docs/week13-s3-beta-onboarding-session.md`

## Short Announcement

```text
S3 beta support is now available for contract artifacts.

Teams can run contract-service with `CONTRACTS_ARTIFACT_BACKEND=s3` and store contract metadata, schema versions, and schema checksums in an S3 bucket while keeping the check history database unchanged.

What is included:
- S3 artifact backend behind the existing ArtifactStore port.
- Canonical object keys for metadata, versioned schemas, and checksums.
- Docker smoke flow for API checks plus S3 artifact verification.
- AWS bucket setup helper with Block Public Access, bucket-owner-enforced ownership, SSE-S3, and versioning.
- Failure-mode coverage for missing bucket config, S3 write failures, rollback behavior, and local fallback reads.

Beta requirements:
- Run the Week 13 S3 beta runbook before using this with a real team workflow.
- Use a dedicated beta bucket and least-privilege AWS credentials or an IAM role.
- Keep `CONTRACTS_ARTIFACT_S3_FALLBACK_ENABLED=false` for release smoke verification so S3 failures surface clearly.
- Do not commit AWS credentials, generated env files, or bucket-specific secrets.
```

## What Changed

S3 is now a first-class artifact backend for beta usage. Contract-service can write and read artifact objects from S3 while preserving the existing API surface and metadata database responsibilities.

The default backend remains filesystem, so existing local users do not need to change configuration. S3 is opt-in through environment variables documented in the runbook.

## Who Should Try It

- Platform engineers validating cloud artifact storage for contract governance.
- Data/API teams that need contract artifacts outside local disk.
- Operators preparing a shared beta environment for multiple teams.

## Beta Boundaries

- The beta validates artifact storage, not a full multi-tenant cloud control plane.
- S3 Object Lock and retention policies are intentionally out of scope.
- Reads and writes still go through contract-service; direct bucket editing is not supported.
- Metadata database backup, restore, and migration procedures remain separate from S3 artifact recovery.

## Quick Start

```bash
cd /path/to/data-contract-governance
scripts/aws/s3-artifact-demo.sh setup --profile dcg-s3 --region ap-south-1
source /tmp/dcg-s3-demo.env

export CONTRACTS_ROOT="/tmp/dcg-contracts-cache"
export CONTRACTS_ARTIFACT_BACKEND="s3"
export CONTRACTS_ARTIFACT_S3_BUCKET="$DCG_S3_BUCKET"
export CONTRACTS_ARTIFACT_S3_REGION="$AWS_REGION"
export CONTRACTS_ARTIFACT_S3_PREFIX="$DCG_S3_PREFIX"
export CONTRACTS_ARTIFACT_S3_FALLBACK_ENABLED="false"
export CONTRACTS_ARTIFACT_S3_LOCAL_CACHE_ROOT="/tmp/dcg-contracts-cache"
export CONTRACTS_ARTIFACT_S3_SERVER_SIDE_ENCRYPTION="AES256"

docker compose --env-file .env -f docker-compose.yml up --build -d
scripts/aws/s3-artifact-demo.sh seed-contract
scripts/aws/s3-artifact-demo.sh verify
```

Cleanup:

```bash
scripts/aws/s3-artifact-demo.sh cleanup --yes
```

## Verification Snapshot

Latest focused verification on `2026-05-23`:

```text
./mvnw -pl contract-service -am \
  -Dtest=ArtifactStoreBackendSelectionTest,S3ArtifactStoreTest,ArtifactKeyStrategyTest,CheckRunnerIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test

BUILD SUCCESS
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
```

## Feedback Request

When trying the beta, capture:

- Time to first successful health check.
- Time to first successful S3-backed contract write.
- Any AWS credential, bucket policy, or Docker environment confusion.
- Whether the expected S3 object keys match the runbook.
- Any error message that did not make the next action obvious.
