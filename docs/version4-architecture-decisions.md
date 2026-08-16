# Version 4 Architecture Decisions

- Plan ID: `PLAN-2026-V4`
- Status: `Draft for review`
- Created date: `2026-05-23`
- Purpose: lock the Version 4 direction around robustness, security, recovery, and responsible backend support

## Decision V4-001: Feature Freeze for Trust Work

Status: `Proposed`

Context:

The project now has several surfaces: CLI, service API, UI, SDK, Spring Boot starter, multiple metadata backends, and multiple artifact backends. Continuing to add features before hardening would increase the chance of brittle behavior, unclear recovery, and weak security boundaries.

Decision:

Version 4 enters a feature freeze. New work must directly improve at least one of:

1. Correctness.
2. Robustness.
3. Security.
4. Recovery.
5. Observability.
6. Developer/operator trust.
7. Documentation required to operate the system safely.

Consequences:

1. MongoDB, Cassandra, Avro, Protobuf, hosted demos, and broad workflow features do not enter implementation by default.
2. Bug fixes, tests, runbooks, authz improvements, failure handling, metrics, and architecture documentation are in scope.
3. Any exception needs an explicit decision and owner.

## Decision V4-002: Recovery Is a Release Requirement

Status: `Proposed`

Context:

The service stores governance decisions, contract artifacts, audit logs, and operational state. If database migrations fail, object storage writes partially fail, credentials rotate, or local state is corrupted, operators need a known recovery path.

Decision:

Version 4 is not accepted until recovery paths are documented and verified for the supported storage profiles.

Minimum recovery areas:

1. PostgreSQL metadata backup and restore.
2. SQLite production-lite backup and restore.
3. MySQL recovery expectations before any GA label.
4. S3 artifact recovery using bucket versioning.
5. Migration failure triage.
6. Credential/authentication failure triage.
7. Partial artifact write cleanup behavior.

Consequences:

1. A backend cannot move to GA without backup, restore, and failure-mode documentation.
2. Recovery runbooks must include validation commands, not only theory.
3. Tests should cover partial-write and startup-failure behavior where practical.

## Decision V4-003: Backend Support Requires an Operational Contract

Status: `Proposed`

Context:

The code already supports multiple persistence paths. Every new backend multiplies test, security, docs, migration, and recovery responsibilities.

Decision:

No backend is accepted as supported unless it satisfies an operational contract:

1. Storage adapter is behind the existing `MetadataStore` or `ArtifactStore` boundary.
2. Contract tests cover create, read, list, query, queue, pagination, and failure behavior as applicable.
3. Schema or migration strategy is documented.
4. Backup and restore runbook exists.
5. Health indicators and useful metrics exist.
6. Credentials and secret handling are documented.
7. Local or containerized verification path exists.
8. Known limits and non-goals are documented.
9. Partial failure and recovery behavior is documented.

Consequences:

1. Backend support is treated as an operational commitment, not a code-only feature.
2. Cassandra and MongoDB must pass the same bar before implementation.
3. The support matrix must label each backend as GA, beta, production-lite, local-only, or research-gated.

## Decision V4-004: Cassandra Is Research-Gated

Status: `Proposed`

Context:

Cassandra could be useful for high-write, distributed, time-series-like workloads, but DCG currently depends on correctness-heavy metadata operations such as queue claims, check-run state transitions, audit logs, pagination, and recovery. Cassandra's query model and consistency tradeoffs need careful review before implementation.

Decision:

Cassandra is not implemented in V4. It is allowed only as a research track using `docs/version4-cassandra-research-gate.md`.

Research must answer:

1. Which exact DCG workload needs Cassandra instead of Postgres or MySQL?
2. Which data model would be stored in Cassandra?
3. How would queue claims and state transitions stay correct?
4. What consistency level is required for check results and audit logs?
5. How would schema evolution, backup, restore, and local testing work?
6. What operational cost does Cassandra add?

Possible outcomes:

1. `No-go`: no clear fit.
2. `Research spike`: more evidence needed.
3. `Projection-only`: Cassandra may store read-optimized history/projections, while relational DB remains source of truth.
4. `Metadata backend proposal`: only if correctness and recovery requirements are satisfied.

Consequences:

1. Cassandra discussion stays disciplined and evidence-based.
2. The team avoids weakening correctness for backend breadth.
3. If Cassandra later proceeds, it starts with a design RFC and test plan.

## Decision V4-005: Security Baseline Before Broader Adoption

Status: `Proposed`

Context:

The service now has write routes, auth toggles, Docker profiles, database credentials, and optional AWS credentials. Shared environments need intentional security defaults.

Decision:

Version 4 must review and document the security baseline before broader adoption.

Required checks:

1. Write routes require a writer role in shared or production-like profiles.
2. Unauthorized and unauthenticated write attempts are tested.
3. Secrets are loaded through environment variables, secret env references, or SDK provider chains.
4. Docs do not ask users to paste cloud secrets into chat, commits, screenshots, or tracked files.
5. Audit logs cover contract writes and check submissions.
6. Production profiles fail closed where possible.

Consequences:

1. Security configuration becomes part of the release gate.
2. Local development can stay low-friction, but shared/prod profiles must be explicit.
3. Documentation must separate local convenience from production guidance.

## Decision V4-006: Architecture v4 Must Replace the V3 Support Matrix

Status: `Proposed`

Context:

Architecture v3 described a phased plan. Implementation has now moved through Postgres, SQLite production-lite, MySQL, and S3 beta. The current architecture docs must match reality.

Decision:

Publish `docs/Architecture-v4.md` with an updated support matrix and resolved open questions.

Required sections:

1. Current component architecture.
2. Metadata backend support labels.
3. Artifact backend support labels.
4. Supported backend combinations.
5. Recovery and security assumptions.
6. MongoDB and Cassandra decision gates.
7. API compatibility policy or explicit carry-forward decision.

Consequences:

1. New adopters get one current architecture entry point.
2. Future implementation work must cite the V4 matrix.
3. README should point to Architecture v4 once accepted.

## Decision V4-007: Notifications Are Durable Operational Signals

Status: `Proposed`

Context:

Contract and policy failures should not be discovered only by manually refreshing the UI or reading CLI output. Shared environments need reliable notification when a contract breaks, a policy rule blocks a version, policy configuration fails, a new contract is registered, or a schema version is published.

Decision:

Version 4 includes a notification baseline for contract and policy failures. Notifications are treated as operational signals and must be implemented through a small notification boundary, not as direct controller side effects.

Initial scope:

1. Check run completes with `FAIL` because breaking changes are detected.
2. Contract version write is rejected because compatibility or policy rules fail.
3. New contract is registered.
4. New schema version is published.
5. Policy pack configuration or resolution fails and requires operator action.
6. Notification delivery fails and needs retry or operator visibility.

Delivery approach:

1. Store notification events before delivery when possible.
2. Deliver asynchronously so notification failure does not corrupt check-run state.
3. Start with log/audit visibility and a generic webhook sink.
4. Keep provider-specific Slack, Teams, PagerDuty, and email integrations out of the first slice unless explicitly approved.
5. Keep REST for CLI checks, SDK submissions, and dashboard reads; use WebHooks only for asynchronous events.

Required controls:

1. Redact secrets from payloads and logs.
2. Configure webhook URLs and credentials through env vars or secret references.
3. Use timeouts, bounded retries, and dedupe keys.
4. Record delivery attempts and failures.
5. Test trigger conditions and failed delivery behavior.

Consequences:

1. Notification reliability becomes part of V4 trust work.
2. The first implementation remains open and provider-neutral.
3. Provider-specific integrations can be added later behind the same notification contract.

## Decision V4-008: UI Is an Operations and Trust Surface

Status: `Proposed`

Context:

The current UI already supports dashboard, contract list, contract detail, check detail, runner logs, and rerun snippets. Version 4 needs the UI to support trust and recovery, not just browsing. Users should be able to understand system risk without reading server logs or guessing which backend failed.

Decision:

Version 4 treats the UI as an operations and trust surface.

Required UI direction:

1. Show current risk: failed checks, queued/running checks, and recent policy rejections.
2. Show backend health: metadata store, artifact store, and notification delivery status.
3. Show actionable recovery guidance when stores, migrations, credentials, artifacts, or notifications fail.
4. Keep command/query flows on REST and event flows on WebHooks.
5. Keep the UI dense, clear, and operational rather than decorative.
6. Link failures to runbooks, rerun commands, API commands, and notification delivery state.

Consequences:

1. V4 UI work is allowed when it improves diagnosis, recovery, security clarity, or notification visibility.
2. Pure visual polish is not enough to break the feature-freeze rule.
3. The first UI plan should be documented in `docs/version4-ui-hardening-plan.md`.

## Decision V4-009: V4 Requires Real Spring Boot Demo Validation

Status: `Proposed`

Context:

Version 4 is meant to prove production readiness. A service that only works in its own repository can still fail when used by a realistic application. The team also plans to validate V4 in real time in front of `56` people, so the release needs a deterministic demo path and rehearsed recovery story.

Decision:

Version 4 must include a separate Spring Boot demo project as a release validation target.

The demo must exercise:

1. CLI contract checks.
2. SDK or REST check-run submission.
3. Spring Boot starter validation.
4. Contract-service UI triage.
5. WebHook notification for breaking contract or policy failure.
6. At least one documented recovery path.

Consequences:

1. The demo project becomes part of the V4 release gate.
2. The final-week live validation must have rehearsal evidence.
3. Production-readiness claims must be backed by a realistic integration, not only module tests.
4. Fallback materials are allowed only as backup and must not replace the live validation path.

## Decision V4-010: V4 Backend Scope Is Fixed

Status: `Accepted`

Context:

V4 is preparing for an open-source release. Broadening backend support now would dilute the recovery, security, support, and onboarding work required for a trustworthy release.

Decision:

1. PostgreSQL, SQLite, and MySQL remain the V4 metadata-store scope.
2. Filesystem and S3 remain the V4 artifact-store scope.
3. MongoDB and Cassandra are not implemented, evaluated, or supported in V4.
4. A future version may reconsider either backend only through a new decision record with adopter demand, correctness, recovery, and maintenance evidence.

Consequences:

1. V4 implementation effort remains focused on production readiness and open-source adoption.
2. MongoDB and Cassandra are not release blockers beyond this recorded no-go decision.
3. Support labels for MySQL and S3 remain evidence-based; this decision does not promote a beta backend to GA.
