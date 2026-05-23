# Version 4 Planning: Robust, Secure, Recoverable Platform

- Plan ID: `PLAN-2026-V4`
- Status: `Draft for review`
- Created date: `2026-05-23`
- Planning start: `2026-05-25`
- Planning horizon: `4 weeks`
- Owner: `DCG maintainers`
- Scope: feature freeze, architecture decisions, robustness hardening, security baseline, recovery drills, backend support policy, and Cassandra research gate

## 1. Version 4 Thesis

Version 4 should convert the current broad technical foundation into a robust, secure, and recoverable platform.

The project already has:

1. Core JSON Schema compatibility checks.
2. CLI, service API, embedded UI, SDK, and Spring Boot starter surfaces.
3. SQLite, Postgres, and MySQL metadata-store paths.
4. Filesystem and S3 artifact-store paths.
5. Docker compose smoke flow, S3 beta runbook, launch post, and onboarding guide.

Version 4 should not start by adding another feature or backend. It should first prove that the existing system can survive expected failures, protect shared environments, and be recovered by an operator without maintainer guesswork.

## 2. Product Goal

By the end of Version 4, a new platform engineer should be able to:

1. Run the compose demo without ad-hoc help.
2. Configure a real metadata backend and artifact backend using documented profiles.
3. Submit and inspect a check run through CLI, API, and UI.
4. Recover from database, artifact-store, migration, credential, and partial-write failures using documented runbooks.
5. Understand storage support levels, beta/GA boundaries, and the requirements for any future backend.
6. Trust that security defaults, auth behavior, secret handling, and audit logs are intentional.

## 3. Strategic Decisions

### 3.1 Robust Platform Before New Features

V4 prioritizes correctness, reliability, recovery, security, docs, onboarding, and support boundaries over expanding to MongoDB, Cassandra, or any other backend immediately.

New feature work should be accepted only when it directly improves robustness, security, recovery, observability, or operator/developer trust.

### 3.2 S3 Beta Moves Toward GA

S3 is already implemented and beta-published. V4 should focus on evidence:

1. Real or simulated onboarding sessions.
2. Failure-mode clarity.
3. Cost and IAM guidance.
4. Recovery story for accidentally deleted or mutated artifacts.
5. Clear recommendation for production default artifact backend.

### 3.3 MongoDB Remains a Decision Gate

MongoDB should stay demand-gated unless Version 4 feedback proves a real use case that Postgres/MySQL plus S3 cannot satisfy.

Go criteria:

1. At least two credible user requests from target adopters.
2. A concrete workload description, not just "we use Mongo."
3. A maintenance owner and test-matrix budget.
4. Clear data-model fit that does not duplicate existing metadata-store behavior.

### 3.4 Cassandra Is Research-Only in V4

Cassandra may become relevant for very high write volume, distributed deployments, or event-style historical queries, but it has different consistency, query modeling, migration, and operational tradeoffs than the current relational metadata stores.

V4 should research Cassandra before committing to it. Use `docs/version4-cassandra-research-gate.md` as the evaluation template.

Research questions:

1. What exact DCG workload needs Cassandra instead of Postgres or MySQL?
2. Which data would live in Cassandra: check-run history, audit logs, projections, or something else?
3. Can queue-claim semantics be implemented safely without weakening correctness?
4. What consistency model is acceptable for check status, logs, and audits?
5. How would backup, restore, schema migration, and local development work?
6. What new tests and operational runbooks would be required?

### 3.5 Backend Support Requires an Operational Contract

No backend should be considered supported unless it has:

1. Contract tests for create, read, list, query, queue, pagination, and failure cases.
2. Migration or schema-management policy.
3. Backup and restore runbook.
4. Health indicators and metrics.
5. Clear security and credential handling.
6. Local or containerized verification path.
7. Documented limits and non-goals.
8. Recovery procedure for partial writes and corrupted state.

## 4. Non-Goals

1. No runtime broker interception.
2. No Kubernetes-first rewrite.
3. No Avro or Protobuf expansion in this planning cycle.
4. No MongoDB implementation unless the decision gate passes.
5. No Cassandra implementation in V4.
6. No new backend implementation without the backend support contract above.
7. No breaking API changes without an explicit API versioning proposal.
8. No paid SaaS dependency as a required path.

## 5. Workstreams

### 5.1 Architecture Decision Reset

Objective: make the system explicit instead of relying on accidental design.

P0 tasks:

1. Publish a V4 architecture decision log.
2. Define the feature-freeze rule for V4.
3. Define backend support requirements.
4. Decide which existing backends are GA, beta, production-lite, or local-only.
5. Record MongoDB and Cassandra as research gates unless evidence changes.

Exit criteria:

1. Every major storage, security, and recovery choice has an explicit decision.
2. New backend work has a written acceptance bar before implementation starts.
3. V4 architecture choices can be reviewed without reading the whole codebase.

### 5.2 S3 Beta to GA Readiness

Objective: decide whether S3 can move from beta to GA for artifact storage.

P0 tasks:

1. Run the Week 13 S3 beta flow from a clean Docker path.
2. Verify S3 object keys, checksum files, and fallback-disabled behavior.
3. Document artifact recovery steps for bucket versioning restore.
4. Add a concise S3 production-readiness checklist.
5. Decide whether fallback reads should be disabled by default in production profiles.

Exit criteria:

1. S3 smoke run is reproducible from docs.
2. Error messages identify missing bucket, credential, region, and permission failures clearly.
3. Recovery guidance exists for metadata DB restore plus S3 artifact restore.
4. Maintainers agree on beta, RC, or GA support label.

### 5.3 Security and Abuse-Resistance Baseline

Objective: make shared environments safe by default.

P0 tasks:

1. Review all write routes and confirm authz requirements are covered by tests.
2. Confirm secrets are never logged, committed, echoed in docs, or requested in chat.
3. Review default profiles for local, compose, prod, and sqlite-prod-lite.
4. Add explicit guidance for rotating app credentials and database credentials.
5. Review audit logs for contract writes, check submissions, and operational failures.

Exit criteria:

1. Write endpoints require an intentional writer role in shared/prod-like profiles.
2. Secret handling is documented through env vars, profile names, or SDK credential chain.
3. Security tests cover unauthenticated, unauthorized, and authorized write paths.
4. README and runbooks do not normalize unsafe credential handling.

### 5.4 Recovery and Failure Drills

Objective: prove the system can be recovered when something goes wrong.

P0 tasks:

1. Run and document database backup/restore drills for Postgres and SQLite production-lite.
2. Define MySQL restore expectations before GA labeling.
3. Document S3 artifact restore from bucket versioning.
4. Add incident checklists for DB unavailable, S3 unavailable, migration failure, bad credentials, and partial artifact write.
5. Verify app behavior for startup failure, read failure, write failure, and rollback paths.

Exit criteria:

1. Recovery runbooks include commands, expected output, and validation checks.
2. Failure modes have operator next steps.
3. Partial failure behavior is tested or explicitly documented as a known gap.

### 5.5 Storage Support Matrix and Architecture v4

Objective: publish a V4 architecture baseline that reflects what is now implemented.

P0 tasks:

1. Create `docs/Architecture-v4.md`.
2. Update support levels for SQLite, Postgres, MySQL, filesystem, and S3.
3. Separate `supported`, `beta`, `production-lite`, and `decision-gated` labels.
4. Add compatibility expectations for metadata and artifact store combinations.
5. Record the MongoDB decision gate outcome.

Exit criteria:

1. Storage matrix is clear enough for a new adopter to choose a backend.
2. V3 open questions are resolved or carried forward with owners.
3. README can point users to a single current architecture document.

### 5.6 First-User Adoption Loop

Objective: test whether the project can be adopted without maintainer hand-holding.

P0 tasks:

1. Run at least two onboarding sessions using `docs/week13-s3-beta-onboarding-session.md`.
2. Capture feedback using the existing feedback log format.
3. Measure time to first health check and first successful check run.
4. Convert friction into GitHub-ready issues or local backlog items.

Exit criteria:

1. At least two feedback records exist.
2. Top five blockers are ranked by adoption impact.
3. At least three documentation or error-message fixes are merged.

### 5.7 Developer Experience and CI Workflow

Objective: make the daily developer workflow obvious and repeatable.

P0 tasks:

1. Validate changed-contract CI script against realistic changed files.
2. Add or refresh GitHub Actions example docs.
3. Confirm CLI exit codes and messages remain stable.
4. Add copy-ready commands for local, CI, compose, and S3-backed checks.

Exit criteria:

1. A developer can copy one CI example and block a breaking schema change.
2. CLI output explains breaking changes without requiring UI lookup.
3. Docs distinguish local checks, recorded checks, and service-submitted checks.

### 5.8 Operational Trust

Objective: ensure operators can run, monitor, and recover the service.

P0 tasks:

1. Re-run focused test slices for Postgres, MySQL, SQLite, filesystem, and S3.
2. Review health and metrics output for metadata store and artifact store.
3. Add a basic incident checklist for DB unavailable, S3 unavailable, and migration failure.
4. Confirm secrets are referenced through env vars or SDK credential chain only.

Exit criteria:

1. Smoke verification commands pass or known local prerequisites are documented.
2. Failure modes have next-action guidance.
3. No docs ask users to paste cloud secrets into chat, commits, or screenshots.

## 6. Four-Week Plan

### Week 14: V4 Discovery and Support Matrix

Dates: `2026-05-25` to `2026-05-29`

Primary outcome: current-state truth and architecture decisions are captured.

Tasks:

1. Audit existing docs and implementation against V3 architecture claims.
2. Publish the first V4 architecture decision log.
3. Draft the V4 storage support matrix.
4. Define backend support requirements, including Cassandra research criteria.
5. Run clean compose smoke path.
6. Run focused S3 beta smoke path.
7. List all known robustness, security, and recovery gaps.

Deliverables:

1. `docs/version4-planning.md` reviewed.
2. `docs/version4-architecture-decisions.md` reviewed.
3. Draft support matrix for `docs/Architecture-v4.md`.
4. First robustness/security/recovery backlog.

### Week 15: S3 GA Readiness and Recovery

Dates: `2026-06-01` to `2026-06-05`

Primary outcome: S3 support level can be decided with evidence.

Tasks:

1. Validate S3 write/read/checksum behavior.
2. Document bucket versioning restore and cleanup paths.
3. Tighten S3 error-message tests where needed.
4. Decide production fallback behavior.
5. Update S3 runbook from beta feedback.

Deliverables:

1. S3 production-readiness checklist.
2. S3 recovery notes.
3. Support label recommendation: beta, RC, or GA.

### Week 16: Security, Recovery, and Developer Workflow

Dates: `2026-06-08` to `2026-06-12`

Primary outcome: shared deployments are safer and failure handling is clearer.

Tasks:

1. Review write-route security tests and fill gaps.
2. Add or update incident checklists for DB, migration, credential, and artifact-store failures.
3. Run two onboarding sessions.
4. Convert feedback into fixes.
5. Refresh CI example docs.
6. Improve CLI/API/UI wording where feedback shows confusion.

Deliverables:

1. Security baseline checklist.
2. Recovery and incident checklist updates.
3. Feedback log entries.
4. Updated docs or error messages for top blockers.
5. CI workflow guide.

### Week 17: Architecture v4 and Release Gate

Dates: `2026-06-15` to `2026-06-19`

Primary outcome: V4 planning closes with a clear release or carry-forward decision.

Tasks:

1. Publish `docs/Architecture-v4.md`.
2. Resolve or defer V3 open questions.
3. Make MongoDB decision: no-go, research spike, or implementation proposal.
4. Make Cassandra decision from `docs/version4-cassandra-research-gate.md`: no-go, research spike, projection-only, or implementation proposal.
5. Run final focused regression checks.
6. Create V4 release notes or V4 carry-forward plan.

Deliverables:

1. Architecture v4 baseline.
2. V4 release checklist.
3. Mongo and Cassandra decision records.
4. Next-version backlog.

## 7. Release Gate

Version 4 can be accepted when:

1. Feature-freeze rule is documented and followed.
2. V4 architecture decision log is published.
3. Support matrix is published and unambiguous.
4. Compose demo passes from a clean setup.
5. S3 support label is decided from evidence.
6. Recovery runbooks or incident checklists cover DB, migration, credential, and artifact-store failures.
7. Security baseline for write routes, authz, secrets, and audit logs is reviewed.
8. At least two onboarding feedback records are captured.
9. Top robustness, security, recovery, and adoption blockers have owners or fixes.
10. Postgres, SQLite, MySQL, filesystem, and S3 focused test slices have fresh verification notes.
11. MongoDB and Cassandra decision gates are explicitly recorded.
12. README points to current V4 entry points.

## 8. Initial Backlog

### P0

1. Draft Architecture v4 support matrix.
2. Publish V4 architecture decision log.
3. Define backend support contract.
4. Re-run compose and S3 beta smoke paths.
5. Add recovery checklists for DB, migration, credentials, and artifact store failures.
6. Review write-route security and secret-handling docs.
7. Capture two onboarding sessions.
8. Decide S3 fallback behavior for production profiles.
9. Record MongoDB and Cassandra gate outcomes.

### P1

1. Improve UI empty/error states for failed check runs and missing artifacts.
2. Add a single "choose your backend" operator guide.
3. Add a GitHub Actions copy-paste example for changed-contract checks.
4. Add troubleshooting table for auth, DB, and S3 failures.
5. Add release notes for V4 planning outcome.
6. Add a backend evaluation template for Cassandra and other future stores.

### P2

1. Evaluate Avro/Protobuf demand for a later version.
2. Explore hosted demo or static demo assets.
3. Add richer UI filtering for artifact backend and storage health.
4. Evaluate signed artifact manifests.
5. Research Cassandra only after the V4 robustness/security/recovery baseline is stable.

## 9. Day 1 Kickoff Checklist

1. Confirm whether V4 is a planning release or implementation release.
2. Review dirty worktree and separate generated build artifacts from source docs.
3. Confirm the feature-freeze rule and allowed exception categories.
4. Publish the first V4 architecture decision log.
5. Run `./mvnw clean test -Dsurefire.failIfNoSpecifiedTests=false` if local prerequisites are available.
6. Run compose smoke path.
7. Draft `docs/Architecture-v4.md` skeleton.
8. Open the first robustness/security/recovery blocker list.

## 10. Open Questions

1. Should S3 be recommended as the default production artifact backend after V4?
2. Should MySQL be labeled GA or remain beta until more external validation exists?
3. What is the minimum public support promise for SQLite production-lite?
4. Should the UI expose artifact backend health directly?
5. What evidence is enough to justify MongoDB research?
6. What evidence is enough to justify Cassandra research?
7. Should V4 include a formal API versioning policy before broader adoption?
8. Which recovery drills must pass before any backend is labeled GA?
