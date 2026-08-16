# Version 4 Planning: Robust, Secure, Recoverable Platform

- Plan ID: `PLAN-2026-V4`
- Status: `In progress`
- Created date: `2026-05-23`
- Planning start: `2026-05-25`
- Planning horizon: `4 weeks`
- Owner: `DCG maintainers`
- Scope: feature freeze, architecture decisions, robustness hardening, security baseline, recovery drills, notification/alerting baseline, UI trust surface, backend support policy, and Cassandra research gate
- Primary release plan: `docs/version4-production-readiness-release-plan.md`

## 1. Version 4 Thesis

Version 4 should convert the current broad technical foundation into a robust, secure, recoverable, production-ready platform.

The project already has:

1. Core JSON Schema compatibility checks.
2. CLI, service API, embedded UI, SDK, and Spring Boot starter surfaces.
3. SQLite, Postgres, and MySQL metadata-store paths.
4. Filesystem and S3 artifact-store paths.
5. Docker compose smoke flow, S3 beta runbook, launch post, and onboarding guide.

Version 4 should not start by adding another feature or backend. It should first prove that the existing system can survive expected failures, protect shared environments, be recovered by an operator without maintainer guesswork, and pass a real-time validation using a separate Spring Boot demo project in front of `56` people.

## 2. Product Goal

By the end of Version 4, a new platform engineer should be able to:

1. Run the compose demo without ad-hoc help.
2. Configure a real metadata backend and artifact backend using documented profiles.
3. Submit and inspect a check run through CLI, API, and UI.
4. Recover from database, artifact-store, migration, credential, and partial-write failures using documented runbooks.
5. Receive reliable notifications when contract compatibility or policy enforcement fails.
6. Use the UI to identify current risk, failed checks, backend health, notification status, and the next recovery action.
7. Understand storage support levels, beta/GA boundaries, and the requirements for any future backend.
8. Trust that security defaults, auth behavior, secret handling, audit logs, notification behavior, and UI guidance are intentional.
9. Run a production-like Spring Boot demo project through compatible and breaking contract changes during the final V4 validation.

## 2.1 Current Implementation Status

The V4 technical baseline is in progress, not release-ready.

Completed implementation slices include:

1. Durable notification deliveries in the metadata-store outbox, including deduplication, retries, terminal states, stale-worker recovery, redacted diagnostics, and an authenticated delivery-status API.
2. A separate Spring Boot order-fulfillment demo with starter-backed runtime payload validation.
3. Compatible and intentionally breaking `orders.created` contract scenarios exercised through the CLI, REST API, embedded UI, and generic WebHook delivery.
4. A runnable SQLite production-lite recovery drill that verifies hot backup, integrity, restore, service restart, and retrieval of a persisted check run.
5. Shared-profile credential validation, writer-route authorization coverage, successful write audit coverage, and staged credential-rotation guidance.
6. Recovery failure-path coverage for unavailable metadata-store reads and writes, PostgreSQL credential/network failures, migration rollback, and S3 artifact rollback, with operator runbook commands.
7. A clean-start first-user adoption runbook with green-path, policy-enforcement, backend-evidence, feedback, and cleanup commands. Feedback sessions remain to be captured.

Still required before V4 can be called production-ready:

1. Capture or formally carry forward the postponed onboarding feedback sessions.
2. Prepare release notes and record final acceptance evidence.

## 3. Strategic Decisions

### 3.1 Robust Platform Before New Features

V4 prioritizes correctness, reliability, recovery, security, docs, onboarding, and support boundaries over expanding to MongoDB, Cassandra, or any other backend immediately.

New feature work should be accepted only when it directly improves robustness, security, recovery, observability, or operator/developer trust.

### 3.2 S3 Beta Moves Toward GA

S3 is implemented and beta-published. The V4 decision is to retain the `Beta` label after AWS smoke, recovery, IAM/error, lifecycle, encryption, and cost-ownership evidence. A future promotion decision requires adopter feedback:

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

### 3.6 Notifications Are Trust Infrastructure

Notifications for broken contracts and policy failures are allowed in V4 because they improve operational trust. They must be built as a durable alerting path, not as best-effort side effects hidden inside controllers or the rule engine.

Use `docs/version4-notification-system-plan.md` as the planning baseline.

Initial notification scope:

1. Compatibility check fails because breaking changes are detected.
2. Contract version creation is rejected because policy/compatibility rules fail.
3. New contract registration is published as a lifecycle event.
4. Schema version publication is published as a lifecycle event.
5. Policy pack resolution or policy configuration fails in a way operators must fix.
6. Notification delivery itself fails and needs retry or operator visibility.

### 3.7 UI Is a Trust and Operations Surface

The V4 UI should help users answer:

1. What is broken right now?
2. Which contracts or policy packs are risky?
3. Are metadata and artifact backends healthy?
4. Were notifications delivered?
5. What is the next safe action?

Use `docs/version4-ui-hardening-plan.md` as the UI planning baseline.

### 3.8 Final Validation Uses a Real Spring Boot Demo

The final week of V4 must validate DCG against a separate Spring Boot project with realistic contracts, CI-style checks, REST/SDK check submission, starter validation, UI triage, and WebHook notifications.

This demo is a production-readiness test, not a polished illusion. If something fails, the recovery path and failure explanation must be visible.

## 4. Non-Goals

1. No runtime broker interception.
2. No Kubernetes-first rewrite.
3. No Avro or Protobuf expansion in this planning cycle.
4. No MongoDB implementation unless the decision gate passes.
5. No Cassandra implementation in V4.
6. No new backend implementation without the backend support contract above.
7. No breaking API changes without an explicit API versioning proposal.
8. No paid SaaS dependency as a required path.
9. No provider-specific Slack, Teams, or PagerDuty implementation until the generic notification contract is proven.
10. No marketing-style UI redesign that hides operational detail.
11. No final-week live demo that depends on undocumented local state.

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
2. Run and record the MySQL recovery drill as GA evidence.
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

### 5.9 Notification and Alerting Baseline

Objective: notify the right people when contracts break or policy enforcement fails.

P0 tasks:

1. Publish `docs/version4-notification-system-plan.md`.
2. Define REST-versus-WebHook protocol boundary.
3. Define notification event types, payload shape, and redaction rules.
4. Decide first delivery sinks: audit/log sink and generic webhook sink.
5. Define retry, dedupe, timeout, and failure visibility behavior.
6. Define security rules for webhook URLs, headers, credentials, and payload contents.
7. Add tests for notification trigger conditions and failed delivery handling before GA.

Exit criteria:

1. Breaking contract checks create notification events.
2. Policy/compatibility rejections create notification events.
3. New contract registration and schema version publication can create lifecycle events.
4. Notifications are asynchronous and do not corrupt check-run state.
5. Delivery failures are visible, retryable, and do not leak secrets.
6. Local profile remains quiet unless notifications are explicitly enabled.

### 5.10 UI Trust Surface

Objective: make the UI useful during normal review and failure recovery.

P0 tasks:

1. Publish `docs/version4-ui-hardening-plan.md`.
2. Define a V4 dashboard layout for risk, health, recent failures, and notification status.
3. Define check detail improvements for root cause, impact, next action, rerun, and notification delivery.
4. Define contract detail improvements for policy pack, latest health, version timeline, and lifecycle events.
5. Define empty/error states for missing artifacts, unavailable metadata DB, failed notifications, and disabled security.
6. Define UI test expectations for desktop and mobile layouts.

Exit criteria:

1. UI has a clear P0 implementation plan.
2. The dashboard can show current system risk without reading logs.
3. Check detail pages make failures actionable.
4. Notification and recovery states have a planned UI home.
5. UI does not replace REST/WebHook boundaries; it observes and explains them.

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

Primary outcome: shared deployments are safer, failure handling is clearer, and notification/UI design is ready.

Tasks:

1. Review write-route security tests and fill gaps.
2. Add or update incident checklists for DB, migration, credential, and artifact-store failures.
3. Publish notification system design and event contract.
4. Publish UI hardening plan and first wireframe checklist.
5. Run two onboarding sessions.
6. Convert feedback into fixes.
7. Refresh CI example docs.
8. Improve CLI/API/UI wording where feedback shows confusion.

Deliverables:

1. Security baseline checklist.
2. Recovery and incident checklist updates.
3. Notification event and delivery design.
4. UI trust surface plan.
5. Feedback log entries.
6. Updated docs or error messages for top blockers.
7. CI workflow guide.

### Week 17: Architecture v4, Release Candidate, and Live Validation

Dates: `2026-06-15` to `2026-06-19`

Primary outcome: V4 closes with a production-readiness decision after release-candidate checks and the 56-person live validation.

Tasks:

1. Publish `docs/Architecture-v4.md`.
2. Resolve or defer V3 open questions.
3. Make MongoDB decision: no-go, research spike, or implementation proposal.
4. Make Cassandra decision from `docs/version4-cassandra-research-gate.md`: no-go, research spike, projection-only, or implementation proposal.
5. Run final focused regression checks.
6. Run the separate Spring Boot demo project happy path and breaking-change path.
7. Run WebHook notification validation.
8. Run at least one recovery drill from the demo script.
9. Complete the live validation in front of `56` people.
10. Create V4 release notes or V4 carry-forward plan.

Deliverables:

1. Architecture v4 baseline.
2. V4 release checklist.
3. Mongo and Cassandra decision records.
4. Spring Boot demo project validation evidence.
5. Live validation feedback log.
6. Next-version backlog.

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
12. Notification event contract and first delivery sinks are documented.
13. UI trust surface plan is published with P0 implementation priorities.
14. Separate Spring Boot demo project completes compatible and breaking contract scenarios.
15. Two rehearsals pass before the final live validation.
16. Final live validation in front of `56` people is completed or a carry-forward decision is recorded.
17. README points to current V4 entry points.

## 8. Initial Backlog

### P0

1. Draft Architecture v4 support matrix.
2. Publish V4 architecture decision log.
3. Define backend support contract.
4. Re-run compose and S3 beta smoke paths.
5. Add recovery checklists for DB, migration, credentials, and artifact store failures.
6. Review write-route security and secret-handling docs.
7. Define notification event contract for contract breaks and policy failures.
8. Define UI trust surface and failure-state plan.
9. Define separate Spring Boot demo project requirements.
10. Capture two onboarding sessions.
11. Decide S3 fallback behavior for production profiles.
12. Record MongoDB and Cassandra gate outcomes.

### P1

1. Improve UI empty/error states for failed check runs and missing artifacts.
2. Add dashboard widgets for backend health, failed checks, notification failures, and recovery actions.
3. Add a single "choose your backend" operator guide.
4. Add a GitHub Actions copy-paste example for changed-contract checks.
5. Add troubleshooting table for auth, DB, and S3 failures.
6. Add release notes for V4 planning outcome.
7. Add a backend evaluation template for Cassandra and other future stores.
8. Add generic webhook notification sink implementation plan.

### P2

1. Evaluate Avro/Protobuf demand for a later version.
2. Explore hosted demo or static demo assets.
3. Add richer UI filtering for artifact backend and storage health.
4. Evaluate signed artifact manifests.
5. Research Cassandra only after the V4 robustness/security/recovery baseline is stable.
6. Evaluate provider-specific notification sinks after the generic sink is stable.
7. Add in-app notification history after outbox delivery semantics are stable.

## 9. Day 1 Kickoff Checklist

1. Confirm whether V4 is a planning release or implementation release.
2. Review dirty worktree and separate generated build artifacts from source docs.
3. Confirm the feature-freeze rule and allowed exception categories.
4. Publish the first V4 architecture decision log.
5. Run `./mvnw clean test -Dsurefire.failIfNoSpecifiedTests=false` if local prerequisites are available.
6. Run compose smoke path.
7. Draft `docs/Architecture-v4.md` skeleton.
8. Draft the notification system baseline.
9. Draft the UI hardening baseline.
10. Draft the Spring Boot demo project scenario.
11. Open the first robustness/security/recovery blocker list.

## 10. Open Questions

1. Should S3 be recommended as the default production artifact backend after V4?
2. Should MySQL be labeled GA or remain beta until more external validation exists?
3. What is the minimum public support promise for SQLite production-lite?
4. Should the UI expose artifact backend health directly?
5. What evidence is enough to justify MongoDB research?
6. What evidence is enough to justify Cassandra research?
7. Should V4 include a formal API versioning policy before broader adoption?
8. Which recovery drills must pass before any backend is labeled GA?
9. Should V4 implement only generic webhooks first, or also email notifications?
10. Should notification events be stored in the metadata DB outbox before delivery?
11. Which UI states are P0 before V4 can be called robust?
12. Should notification delivery history be exposed in UI during V4 or deferred until after the outbox is stable?
13. Which Spring Boot demo domain best proves real production usage?
14. What is the minimum live-demo fallback plan that still keeps the validation honest?
