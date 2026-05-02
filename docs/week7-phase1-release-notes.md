# Release Notes: Phase 1 GA Candidate

Date: `2026-04-24`  
Candidate: `0.1.0-ga-candidate.1`  
Scope: `Week 1 -> Week 7`

## Summary

Phase 1 focuses on local-first data contract governance with production-aware storage paths:

1. Contract compatibility checks (CLI + API)
2. Asynchronous check execution and run history
3. PostgreSQL hardening and migration safety path
4. SQLite production-lite guardrails
5. Docker Compose baseline for fast local onboarding

## Highlights

1. Storage compatibility foundation:
   - Shared migration path in `contract-core` Flyway resources.
   - Metadata store contract tests across SQLite and PostgreSQL.
2. PostgreSQL readiness:
   - SSL and secure-mode controls.
   - auth/network/schema-mismatch failure-path integration tests.
   - operational indexing migration (`V6`).
3. SQLite production-lite hardening:
   - WAL + `busy_timeout` policy.
   - startup integrity checks (`PRAGMA quick_check`).
   - single-node guardrails and profile defaults.
4. Compose baseline:
   - Service + Postgres stack with health checks.
   - Non-root service image and hardened runtime flags.
   - One-command demo runner for fresh-machine setup.

## Operator-Facing Changes

1. New compose quickstart:
   - `bash scripts/demo/run-compose-demo.sh`
2. New SQLite production-lite profile:
   - `SPRING_PROFILES_ACTIVE=sqlite-prod-lite`
3. Updated docs for backup/restore and readiness checklists:
   - Week 4, Week 5, Week 6 runbooks/checklists

## Validation Snapshot

Latest validation run (Java 21 + local Postgres):

- `./mvnw clean test`
- Result: `BUILD SUCCESS`
- `contract-service`: `Tests run: 74, Failures: 0, Errors: 0, Skipped: 0`

## Known Warnings / Follow-Ups

1. Flyway warns PostgreSQL 18 is newer than the currently tested max in tool metadata.
2. For external production rollout:
   - enforce TLS + strict SSL modes
   - move secrets to secret manager
   - publish signed images to registry

## Recommended GA Gate

Ship GA after:

1. One external user dry run (no ad-hoc help) with feedback captured.
2. Compose quickstart median setup time <= 10 minutes.
3. Final docs proofread + release tag cut.
