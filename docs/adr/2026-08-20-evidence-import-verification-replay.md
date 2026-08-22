# ADR: Evidence import, verification, and replay

**Status:** Accepted and implemented for evidence format 1.0
**Date:** 2026-08-20

## Decision

The build gate remains local and offline-first. `contract-service` accepts an immutable evidence document at `POST /checks/evidence`, stores it separately from `check_runs`, and independently verifies it. Imported `localStatus` is never rewritten and is never treated as an authoritative service-run result.

The evidence table has a nullable foreign key for a future authoritative run but does not create one during import. This keeps local evidence, server verification, and queued server checks visibly distinct.

## Verification and trust

- Production evidence import requires a signed CI-issued OIDC JWT. DCG validates signature, issuer, audience, and an explicit contract/repository/ref allow-list. Basic auth is permitted only in an explicit local/demo profile.
- The authenticated workload provenance (scheme, issuer, subject, audience, repository, and ref) and the submitted CI identity are persisted separately.
- The service verifies registered base/candidate schemas by SHA-256, compatibility protocol, policy pack fingerprint, and a local recheck using the registered artifacts.
- Exact matching results become `VERIFIED`.
- A protocol or policy mismatch becomes `VERSION_SKEW`; the service never loads a historical client JAR dynamically.
- Missing/mismatched schemas or a differing recomputation become `REJECTED`. A temporary artifact-store failure remains `UNVERIFIED` with a reason.
- Regulated approval flows must require `VERIFIED`, not merely imported evidence.

## Replay and idempotency

The build tool writes the document before making any network call. Reporting submits those exact bytes. `idempotencyKey` is unique: exact-byte retries return the existing row; a different payload using the same key returns `409 Conflict`. Maven provides `replay-evidence` and Gradle provides `dcgReplayEvidence` for CI artifact backfill.

## Deferred operational controls

Retention policy, mTLS for internal runners, and rate limiting remain deployment concerns. The endpoint imposes `checks.evidence.max-payload-bytes` (default 1 MiB).
