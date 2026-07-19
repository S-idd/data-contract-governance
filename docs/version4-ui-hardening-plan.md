# Version 4 UI Hardening Plan

- Plan ID: `PLAN-2026-V4-UI-HARDENING`
- Status: `In progress`
- Created date: `2026-05-23`
- Purpose: make the UI a trust, diagnosis, and recovery surface

## 1. Goal

The V4 UI should help developers and operators answer five questions quickly:

1. What is broken right now?
2. Which contracts, versions, or policy packs need attention?
3. Are metadata, artifact, and notification systems healthy?
4. What happened, who triggered it, and what changed?
5. What is the next safe action?

The UI should stay operational and scan-friendly. It should not become a marketing page or a decorative dashboard.

## 2. Current UI Baseline

Existing UI surfaces:

1. Dashboard with contract count, recent checks, filters, and check-store unavailable state.
2. Contracts page with search by contract ID, owner team, and domain.
3. Contract detail page with metadata, versions, run-check form, and recent checks.
4. Check detail page with breaking changes, warnings, guidance, execution logs, API command, and CLI rerun command.

These are good foundations. V4 should add failure awareness, notification visibility, and recovery guidance around them.

Current V4 implementation:

1. The dashboard now shows safe readiness for metadata, artifact, notification, and security components.
2. `/api/operational-status` exposes the same readiness model for automation and future UI views.
3. Artifact status distinguishes direct availability from configured S3 fallback.
4. Notification status reports configuration readiness only; delivery history remains deferred until durable outbox persistence exists.

## 3. P0 UI Ideas

### 3.1 Trust Dashboard

Add dashboard sections for:

1. System status: metadata store, artifact store, notification delivery, security mode.
2. Risk summary: failed checks, queued checks, running checks, recent policy rejections.
3. Latest incidents: DB unavailable, artifact read/write failure, notification delivery failure.
4. Next actions: links to check detail, recovery docs, rerun command, and API command.

### 3.2 Check Detail: Failure Triage

Improve check detail with:

1. Failure summary at the top when status is `FAIL`.
2. Severity and reason grouping for breaking changes.
3. "What changed" section comparing base and candidate versions.
4. "Who/what triggered this" section using commit SHA and triggered-by value.
5. Notification status for this failed check once notification events exist.
6. Recovery/rerun actions that are copy-ready.

### 3.3 Contract Detail: Ownership and Policy Clarity

Improve contract detail with:

1. Owner, domain, policy pack, compatibility mode, and latest version in one scan-friendly header.
2. Version timeline with published versions and latest check result per version.
3. Policy pack summary and link to policy-pack docs.
4. Lifecycle event visibility: contract registered, schema version published.
5. Clear empty state when only one version exists.

### 3.4 Notification Status

Add UI planning for:

1. Notification enabled/disabled state.
2. Last delivery status.
3. Failed delivery attempts.
4. Dedupe key and sink name.
5. Link from failed check to notification event.

Do not implement provider-specific notification UI until the generic notification contract is stable.

### 3.5 Recovery Guidance

Add failure panels for:

1. Metadata DB unavailable.
2. Artifact store unavailable.
3. Missing artifact or version.
4. Migration/startup failure.
5. Security/auth configuration issue.
6. Notification delivery failure.

Each panel should show:

1. Plain-language problem.
2. Likely cause.
3. Next safe action.
4. Relevant runbook link.
5. Request ID or run ID for logs.

## 4. P1 UI Ideas

1. Backend support matrix page: Postgres, SQLite, MySQL, filesystem, S3, and research-gated stores.
2. Notification event history page after outbox persistence exists.
3. Policy-pack explorer showing rule severity by pack.
4. Saved filters for failed checks by owner team/domain.
5. Export/share check detail as compact JSON or Markdown for PR comments.
6. Visual diff summary for schema version changes.

## 5. P2 UI Ideas

1. In-app onboarding checklist for first setup.
2. Read-only runbook viewer linked from incidents.
3. Trend charts for checks, failures, warnings, and queue latency.
4. UI support for signed artifact manifest verification.
5. Provider-specific notification setup screens after generic webhook support is stable.

## 6. UX Rules

1. Keep the UI dense and operational.
2. Prefer tables, status strips, tabs, and compact panels over decorative cards.
3. Use clear status labels: `Healthy`, `Degraded`, `Unavailable`, `Disabled`, `Action required`.
4. Every error state should include the next action.
5. Every destructive or security-sensitive action should be explicit.
6. Avoid showing secrets, database URLs, webhook auth headers, or cloud credentials.
7. Keep REST/WebHook boundaries visible: REST for commands and queries, WebHook for events.

## 7. Test Bar

Before V4 UI changes are accepted:

1. Dashboard renders with no checks.
2. Dashboard renders with failed, queued, running, and passing checks.
3. Check detail renders failed checks with breaking changes and guidance.
4. Contract detail renders one-version and multi-version states.
5. Check-store unavailable state is visible and actionable.
6. Notification disabled, delivered, and failed states have planned UI coverage.
7. Mobile and desktop layouts do not hide critical actions.
8. UI text does not expose secrets or raw credential values.

## 8. Open Questions

1. Should notification history be exposed in V4 or after outbox persistence is implemented?
2. Should backend health be read from Actuator, service-owned status endpoints, or both?
3. Should the UI include a policy-pack explorer in V4?
4. Should schema diffs be rendered visually or remain command/API driven for V4?
5. Should the UI show recovery runbook links as local docs paths or public documentation URLs?
