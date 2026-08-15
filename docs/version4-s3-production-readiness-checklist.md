# Version 4 S3 Production-Readiness Checklist

- Plan ID: `PLAN-2026-V4-S3`
- Status: `In progress`
- Current support label: `Beta`
- Related plan: `docs/version4-production-readiness-release-plan.md`

## 1. Current Baseline

The S3 artifact store writes `metadata.yaml`, `schema.json`, and `schema.sha256` under the configured prefix. The local MinIO drill in `scripts/demo/run-s3-recovery-drill.sh` verifies versioned object-pair restore and confirms that `contracts.artifact.s3.fallback-enabled=false` does not silently serve a deleted S3 schema from the local cache.

This evidence is necessary, but it is not an AWS production validation. S3 remains Beta until the promotion criteria below are recorded.

## 2. Production Configuration

1. Enable bucket versioning before writing DCG artifacts.
2. Block all public access and enforce bucket-owner object ownership.
3. Use SSE-S3 or an approved KMS key. Include KMS permissions when `aws:kms` encryption is selected.
4. Supply credentials through a workload role or secret manager, never a committed file or command history.
5. Set `CONTRACTS_ARTIFACT_S3_FALLBACK_ENABLED=false` in production-like profiles.
6. Keep `CONTRACTS_ARTIFACT_S3_ENDPOINT` empty for AWS. Use an endpoint plus `CONTRACTS_ARTIFACT_S3_PATH_STYLE=true` only for MinIO or another S3-compatible local target.
7. Configure lifecycle rules for noncurrent object versions only after the required recovery window is agreed.

## 3. Required Evidence Before Promotion

- [x] S3 object key and checksum behavior covered by focused service tests.
- [x] Missing S3 schema returns `404` rather than falling back to the local cache when fallback is disabled.
- [x] `prod` and `sqlite-prod-lite` profiles default S3 fallback reads to disabled, so production-like deployments do not silently mask S3 failures with a local cache.
- [x] Local S3-compatible versioning and object-pair restore drill is scripted.
- [x] Clean AWS smoke run records bucket, region, prefix, object keys, and sanitized service logs.
- [ ] AWS IAM denial checks distinguish missing object, bad credentials, wrong region, missing bucket, and denied permission.
- [x] Restore of a schema and matching checksum is performed in an AWS versioned bucket and verified through the API.
- [ ] Lifecycle, retention, encryption, KMS, and cost ownership are approved by the operating team.
- [ ] At least two adopter feedback sessions confirm setup and recovery documentation are usable without maintainer intervention.
- [ ] Maintainers record the Beta, RC, or GA support decision in `docs/Architecture-v4.md` and the release plan.

### AWS evidence: 2026-08-15

- Bucket: `dcg-demo`; region: `eu-north-1`; prefix: `contracts`.
- The service ran with `CONTRACTS_ARTIFACT_BACKEND=s3` and fallback reads disabled.
- A clean `s3.smoke.20260815` contract prefix produced `metadata.yaml`, v1/v2 `schema.json`, and matching `schema.sha256` objects.
- Bucket versioning was enabled. The v2 schema was delete-marked, restored from its prior version, and read successfully through the API after the service was recreated, proving the restored artifact was not served from a process cache.
- Evidence intentionally excludes credential values, account identifiers, and service secrets.

## 4. Promotion Decision

Promote S3 from Beta only when every required evidence item is complete and an owner accepts the recovery window and operating cost. Until then, PostgreSQL plus filesystem artifacts remains the conservative production path, and an S3 deployment must use the documented recovery procedure in `docs/version4-recovery-and-incident-runbook.md`.
