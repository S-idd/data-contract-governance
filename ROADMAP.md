# Data Contract Governance Roadmap (Local-Only)

## Context
- Project mode: local development only (no Docker/Testcontainers).
- Available schedule: Monday-Friday, 17:00-20:00 (3 hours/day).
- Planning start date: March 9, 2026.
- Target: production-ready v1 that is usable daily by developers.

## Capacity
- Weekly capacity: ~15 hours.
- 8-week capacity: ~120 hours.
- Work split guideline:
  - 65% feature + UX delivery
  - 25% testing + reliability
  - 10% docs + demo + launch assets

## Phase Plan

### Phase 1: Foundation + Rules (Week 1: March 9-13, 2026)
- Finalize local-first constraints and team conventions.
- Define release criteria and non-negotiable production checklist.
- Freeze v1 scope and create prioritized backlog (P0/P1/P2).
- Output:
  - `LOCAL_DEV_RULES.md` approved
  - v1 scope checklist
  - sprint board for Weeks 2-8

### Phase 2: API Hardening for UI (Week 2: March 16-20, 2026)
- Stabilize contracts/checks APIs for frontend consumption.
- Add pagination/filtering/sorting where needed.
- Standardize API error payloads and status codes.
- Expand integration tests for local Postgres + SQLite paths.
- Output:
  - API-ready backend for UI
  - updated OpenAPI docs

### Phase 3: UI MVP (Weeks 3-4: March 23-April 3, 2026)
- Build first developer-facing interface.
- Pages:
  - Contracts list
  - Contract detail + version browser
  - Check history (filter by contract/commit)
  - Check detail with breaking/warning summaries
- Output:
  - end-to-end local UI + backend flow
  - demo-ready happy path

### Phase 4: Daily Workflow UX (Week 5: April 6-10, 2026)
- Improve actionable guidance in UI and CLI output.
- Add copy-ready commands/snippets for CI and local workflows.
- Improve traceability from check run -> contract -> commit.
- Output:
  - faster issue triage path for developers

### Phase 5: Reliability + Security Baseline (Week 6: April 13-17, 2026)
- Strengthen logging, metrics, health checks, and config validation.
- Add authentication/authorization baseline for internal usage.
- Add dependency/security checks in local CI flow.
- Output:
  - operationally safe release candidate

### Phase 6: Showcase + Launch Prep (Weeks 7-8: April 20-May 1, 2026)
- Create polished demo flow and sample repo scenario.
- Write quickstart, operator runbook, and troubleshooting docs.
- Run final regression and performance sanity checks.
- Output:
  - v1 launch package
  - reproducible demo for stakeholders

## Daily Execution Template (17:00-20:00)
- 17:00-17:20: plan today (1 concrete target, no multitasking).
- 17:20-18:40: implementation block.
- 18:40-19:20: tests + bug fixes.
- 19:20-19:45: docs/cleanup/refactor.
- 19:45-20:00: commit notes + next-day first task.

## Weekly Definition of Done
- All planned P0 items for the week are merged.
- New functionality has unit + integration tests.
- No broken local run commands.
- Docs updated for any behavior/config changes.
- Demo path for new work is verifiable locally.

## v1 Release Gate (Production-Ready)
- Functional:
  - Contracts and checks can be explored through UI.
  - Breaking changes are understandable and actionable.
- Quality:
  - CI test suite green; no flaky known tests.
  - Integration coverage for Postgres and SQLite paths.
- Operations:
  - Structured logs, health endpoints, core metrics available.
  - Local setup can be completed in <= 15 minutes from docs.
- Security:
  - No hardcoded secrets.
  - Basic access control enabled for service endpoints.
- Adoption:
  - 5-minute demo script and sample workflow documented.

## Risks and Mitigation
- Limited time window:
  - Mitigation: one target/day, strict scope guard.
- Environment drift (local machines):
  - Mitigation: enforce `LOCAL_DEV_RULES.md` and validation commands.
- Feature creep:
  - Mitigation: new features only after v1 release gate is met.
