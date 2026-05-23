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
