# Version 4 Planning: Beta to Adoptable Platform

- Plan ID: `PLAN-2026-V4`
- Status: `Draft for review`
- Created date: `2026-05-23`
- Planning start: `2026-05-25`
- Planning horizon: `4 weeks`
- Owner: `DCG maintainers`
- Scope: S3 beta hardening, storage support clarity, adoption feedback, operational trust, and Architecture v4 decision record

## 1. Version 4 Thesis

Version 4 should convert the current broad technical foundation into an adoptable platform.

The project already has:

1. Core JSON Schema compatibility checks.
2. CLI, service API, embedded UI, SDK, and Spring Boot starter surfaces.
3. SQLite, Postgres, and MySQL metadata-store paths.
4. Filesystem and S3 artifact-store paths.
5. Docker compose smoke flow, S3 beta runbook, launch post, and onboarding guide.

Version 4 should not start by adding another backend. It should first prove that the existing paths are understandable, supportable, and trustworthy for early adopters.

## 2. Product Goal

By the end of Version 4, a new platform engineer should be able to:

1. Run the compose demo without ad-hoc help.
2. Configure a real metadata backend and artifact backend using documented profiles.
3. Submit and inspect a check run through CLI, API, and UI.
4. Understand storage support levels and beta/GA boundaries.
5. Provide feedback that maps cleanly to the next roadmap decision.

## 3. Strategic Decisions

### 3.1 Adoptable Platform Before New Backend

V4 prioritizes reliability, docs, onboarding, and support boundaries over expanding to MongoDB immediately.

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

## 4. Non-Goals

1. No runtime broker interception.
2. No Kubernetes-first rewrite.
3. No Avro or Protobuf expansion in this planning cycle.
4. No MongoDB implementation unless the decision gate passes.
5. No breaking API changes without an explicit API versioning proposal.
6. No paid SaaS dependency as a required path.

## 5. Workstreams

### 5.1 S3 Beta to GA Readiness

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

### 5.2 Storage Support Matrix and Architecture v4

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

### 5.3 First-User Adoption Loop

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

### 5.4 Developer Experience and CI Workflow

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

### 5.5 Operational Trust

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

Primary outcome: current-state truth is captured.

Tasks:

1. Audit existing docs and implementation against V3 architecture claims.
2. Draft the V4 storage support matrix.
3. Run clean compose smoke path.
4. Run focused S3 beta smoke path.
5. List all known adoption blockers.

Deliverables:

1. `docs/version4-planning.md` reviewed.
2. Draft support matrix for `docs/Architecture-v4.md`.
3. First adoption-blocker backlog.

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

### Week 16: Adoption Loop and Developer Workflow

Dates: `2026-06-08` to `2026-06-12`

Primary outcome: first-user friction is reduced.

Tasks:

1. Run two onboarding sessions.
2. Convert feedback into fixes.
3. Refresh CI example docs.
4. Improve CLI/API/UI wording where feedback shows confusion.
5. Publish a short V4 adoption status note.

Deliverables:

1. Feedback log entries.
2. Updated docs or error messages for top blockers.
3. CI workflow guide.

### Week 17: Architecture v4 and Release Gate

Dates: `2026-06-15` to `2026-06-19`

Primary outcome: V4 planning closes with a clear release or carry-forward decision.

Tasks:

1. Publish `docs/Architecture-v4.md`.
2. Resolve or defer V3 open questions.
3. Make MongoDB decision: no-go, research spike, or implementation proposal.
4. Run final focused regression checks.
5. Create V4 release notes or V4 carry-forward plan.

Deliverables:

1. Architecture v4 baseline.
2. V4 release checklist.
3. Mongo decision record.
4. Next-version backlog.

## 7. Release Gate

Version 4 can be accepted when:

1. Support matrix is published and unambiguous.
2. Compose demo passes from a clean setup.
3. S3 support label is decided from evidence.
4. At least two onboarding feedback records are captured.
5. Top adoption blockers have owners or fixes.
6. Postgres, SQLite, MySQL, filesystem, and S3 focused test slices have fresh verification notes.
7. MongoDB decision gate is explicitly recorded.
8. README points to current V4 entry points.

## 8. Initial Backlog

### P0

1. Draft Architecture v4 support matrix.
2. Re-run compose and S3 beta smoke paths.
3. Capture two onboarding sessions.
4. Add S3 recovery checklist.
5. Decide S3 fallback behavior for production profiles.
6. Record MongoDB gate outcome.

### P1

1. Improve UI empty/error states for failed check runs and missing artifacts.
2. Add a single "choose your backend" operator guide.
3. Add a GitHub Actions copy-paste example for changed-contract checks.
4. Add troubleshooting table for auth, DB, and S3 failures.
5. Add release notes for V4 planning outcome.

### P2

1. Evaluate Avro/Protobuf demand for a later version.
2. Explore hosted demo or static demo assets.
3. Add richer UI filtering for artifact backend and storage health.
4. Evaluate signed artifact manifests.

## 9. Day 1 Kickoff Checklist

1. Confirm whether V4 is a planning release or implementation release.
2. Review dirty worktree and separate generated build artifacts from source docs.
3. Run `./mvnw clean test -Dsurefire.failIfNoSpecifiedTests=false` if local prerequisites are available.
4. Run compose smoke path.
5. Draft `docs/Architecture-v4.md` skeleton.
6. Open the first adoption-blocker list.

## 10. Open Questions

1. Should S3 be recommended as the default production artifact backend after V4?
2. Should MySQL be labeled GA or remain beta until more external validation exists?
3. What is the minimum public support promise for SQLite production-lite?
4. Should the UI expose artifact backend health directly?
5. What evidence is enough to justify MongoDB research?
6. Should V4 include a formal API versioning policy before broader adoption?
