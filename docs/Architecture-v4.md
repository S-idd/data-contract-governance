# Architecture v4

- Status: `Draft baseline`
- Version scope: V4 production-readiness implementation
- Primary plan: `docs/version4-production-readiness-release-plan.md`
- Decision log: `docs/version4-architecture-decisions.md`

## 1. V4 Architecture Thesis

V4 turns the existing broad platform into a trustworthy production-readiness baseline. The project already has the core product surfaces:

1. JSON Schema lint, diff, and compatibility engine.
2. CLI for local and CI checks.
3. Spring Boot service API.
4. Embedded operational UI.
5. Java SDK.
6. Spring Boot validation starter.
7. Metadata persistence through SQLite, PostgreSQL, and MySQL paths.
8. Artifact persistence through filesystem and S3 paths.

V4 does not expand the product by adding another backend or schema format. V4 proves that the current system can be operated, secured, recovered, and explained by a new adopter.

## 2. Component Model

```text
Developer / CI
  -> contract-cli
  -> contract-core
  -> optional check-run recording

Application / Integration
  -> contract-sdk or REST API
  -> contract-service
  -> MetadataStore
  -> ArtifactStore

Operator / Platform Engineer
  -> embedded UI
  -> actuator health and metrics
  -> audit logs
  -> notification events
  -> recovery runbooks
```

Primary service boundaries:

1. `ContractEngine`: schema lint, diff, compatibility, and policy evaluation.
2. `MetadataStore`: check runs, queue state, check logs, audit logs, and query indexes.
3. `ArtifactStore`: contract metadata and immutable schema artifacts.
4. `NotificationService`: operational event publication after persisted or rejected domain decisions.
5. `UiController`: operator and developer trust surface over API/service state.

## 3. Metadata Backend Support Matrix

| Backend | V4 Label | Intended Use | V4 Requirement Before Stronger Label |
|---|---|---|---|
| PostgreSQL | Production standard | Shared production-like metadata store | Fresh targeted verification, backup/restore drill, secure profile evidence |
| SQLite | Production-lite | Local development and single-node low-cost deployments | Clear single-node limits, backup/restore drill, startup integrity guidance |
| MySQL | Beta | MySQL adopters that accept a validation period | External adopter verification and support notes; isolated backup/restore drill is available |
| MongoDB | Decision-gated | Not implemented | At least two credible adopter requests and a data-model RFC |
| Cassandra | Research-gated | Not implemented | Research gate outcome and correctness/recovery proposal |

## 4. Artifact Backend Support Matrix

| Backend | V4 Label | Intended Use | V4 Requirement Before Stronger Label |
|---|---|---|---|
| Filesystem | Supported local/default | Local development, demos, simple deployments | Clear backup guidance and artifact path documentation |
| S3 | Beta to GA candidate | Production artifact storage | Clean smoke evidence, versioning restore guidance, fallback decision, IAM/cost guidance |

## 5. Supported Backend Combinations

| Metadata Store | Artifact Store | V4 Status | Notes |
|---|---|---|---|
| SQLite | Filesystem | Supported local and production-lite | Single-node only; not an HA claim |
| PostgreSQL | Filesystem | Supported production-leaning | Good first shared deployment path |
| PostgreSQL | S3 | V4 target production path | Requires S3 GA readiness evidence |
| MySQL | Filesystem | Beta | Keep label until focused MySQL evidence is fresh |
| MySQL | S3 | Beta | Requires both MySQL and S3 evidence |

## 6. Security Baseline

V4 keeps local development low-friction while making shared profiles explicit.

Required baseline:

1. Write routes require the configured writer role when security is enabled.
2. Production-like profiles must not normalize default credentials.
3. Secrets are supplied through environment variables, secret env references, or provider chains.
4. Audit logs cover contract writes and check submissions.
5. Documentation must not ask users to paste cloud secrets into chat, screenshots, or tracked files.

## 7. Recovery Baseline

A backend is not production-ready only because the tests pass. It needs recovery evidence.

Required V4 recovery areas:

1. PostgreSQL metadata backup and restore.
2. SQLite production-lite backup and restore.
3. MySQL restore expectations before any GA label.
4. S3 artifact restore using bucket versioning.
5. Migration failure triage.
6. Credential/authentication failure triage.
7. Partial artifact write cleanup guidance.

## 8. Notification Baseline

Notifications are operational signals. They do not replace check-run state, audit logs, or REST APIs.

V4 starts with:

1. A typed `NotificationEvent` envelope.
2. `NotificationService` as the publication boundary.
3. Log sink delivery for local and smoke verification.
4. Generic webhook sink delivery for team-owned automation endpoints.
5. Disabled-by-default configuration.
6. Failure isolation so notification delivery cannot corrupt check-run state.
7. Metadata-store outbox records with per-sink dedupe, retry state, and bounded backoff.
8. Authenticated delivery-history API and dashboard readiness derived from persisted delivery state.

Initial event types:

1. `CONTRACT_CHECK_FAILED`
2. `CONTRACT_VERSION_REJECTED`
3. `CONTRACT_REGISTERED`
4. `SCHEMA_VERSION_PUBLISHED`
5. `POLICY_PACK_RESOLUTION_FAILED`
6. `POLICY_PACK_CONFIG_INVALID`
7. `NOTIFICATION_DELIVERY_FAILED`

Outbox persistence, bounded retry state, delivery history, and UI exposure are follow-on work after the event and webhook contract are stable.

## 9. UI Trust Surface

The embedded UI remains an operational surface, not a marketing page.

V4 UI direction:

1. Show current risk: failed checks, queued checks, running checks, and policy rejections.
2. Show backend health: metadata store, artifact store, notification delivery, and security mode.
3. Make check failures actionable with root cause, impact, next action, and rerun guidance.
4. Surface recovery guidance for database, migration, artifact, credential, and notification failures.
5. Avoid exposing secrets, database URLs, webhook auth headers, or cloud credentials.

## 10. Release Evidence

V4 can move from draft to accepted only when:

1. The Maven reactor is green.
2. Production-like focused slices for PostgreSQL, SQLite, MySQL, filesystem, and S3 have fresh verification notes or explicit skips.
3. Compose demo starts from a clean setup.
4. Recovery runbooks cover the supported storage profiles.
5. Security baseline tests and docs are reviewed.
6. Notification event contract has tests for trigger conditions and delivery failure isolation.
7. UI trust-surface gaps are either implemented or carried forward with owners.
8. The separate Spring Boot demo project validates CLI, REST/SDK, starter, UI, and notification paths.

## 11. Carried Open Questions

1. Should S3 become the recommended production artifact backend after V4?
2. Should MySQL remain beta until external validation exists?
3. What exact public promise is acceptable for SQLite production-lite?
4. Should webhook delivery use a metadata DB outbox in V4 or after V4?
5. Should notification delivery history appear in the UI before webhook/outbox semantics are stable?
6. What is the smallest Spring Boot demo that still proves real adoption?
