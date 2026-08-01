# Version 4 Notification System Plan

- Plan ID: `PLAN-2026-V4-NOTIFICATIONS`
- Status: `In progress (P0 durable delivery baseline implemented)`
- Created date: `2026-05-23`
- Purpose: notify teams when contracts break, policy enforcement fails, or contract lifecycle events are published

## 1. Goal

Build a reliable notification baseline for events that need human attention or downstream automation:

1. A compatibility check fails because breaking changes are detected.
2. A contract version write is rejected because policy or compatibility rules fail.
3. Policy pack configuration or resolution fails.
4. A new contract is registered.
5. A new schema version is published.
6. Notification delivery fails and an operator needs to know.

This is part of V4 trust work. It is not a broad chat-integration feature sprint.

## 2. Protocol Boundary

Keep request/response workflows on REST. Use WebHook delivery only for asynchronous events.

| Use Case | Keep Using |
|---|---|
| CLI tool checking contracts | REST |
| SDK submitting check runs | REST |
| Dashboard fetching data | REST |
| Breaking change alerts | WebHook |
| New contract registered | WebHook |
| Schema version published | WebHook |

## 3. Non-Goals

1. No provider-specific Slack, Teams, PagerDuty, or Discord integration in the first slice.
2. No paid SaaS dependency.
3. No notification from inside low-level rule evaluation code.
4. No secret values in payloads, logs, audit entries, or UI output.
5. No guarantee that notification delivery is the source of truth; check runs and audit logs remain authoritative.
6. No replacing REST APIs with WebHooks for command/query flows.

## 4. Event Types

Initial event types:

1. `CONTRACT_CHECK_FAILED`
2. `CONTRACT_VERSION_REJECTED`
3. `CONTRACT_REGISTERED`
4. `SCHEMA_VERSION_PUBLISHED`
5. `POLICY_PACK_RESOLUTION_FAILED`
6. `POLICY_PACK_CONFIG_INVALID`
7. `NOTIFICATION_DELIVERY_FAILED`

Optional later event types:

1. `CONTRACT_CHECK_WARNING`
2. `CHECK_QUEUE_BACKLOG_HIGH`
3. `ARTIFACT_STORE_UNAVAILABLE`
4. `METADATA_STORE_UNAVAILABLE`

## 5. Event Payload

Recommended fields:

```json
{
  "eventId": "uuid",
  "eventType": "CONTRACT_CHECK_FAILED",
  "severity": "HIGH",
  "occurredAt": "2026-05-23T00:00:00Z",
  "contractId": "orders.created",
  "runId": "run-123",
  "baseVersion": "v1",
  "candidateVersion": "v2",
  "commitSha": "abc123",
  "triggeredBy": "ci",
  "policyPack": "baseline",
  "summary": "Compatibility check failed with 2 breaking changes.",
  "breakingChanges": [],
  "warnings": [],
  "links": {
    "checkRun": "/checks/run-123"
  },
  "dedupeKey": "CONTRACT_CHECK_FAILED:orders.created:abc123:v1:v2"
}
```

Payload rules:

1. Keep payloads small.
2. Include enough context to route and triage.
3. Do not include database URLs, AWS keys, Basic auth values, webhook secrets, or full environment dumps.
4. Consider truncating long breaking-change lists and linking to the check detail.

Lifecycle events such as `CONTRACT_REGISTERED` and `SCHEMA_VERSION_PUBLISHED` should reuse the same envelope and omit check-specific fields when they do not apply.

## 6. Delivery Sinks

P0 sinks:

1. `log`: structured application log for local and smoke verification.
2. `webhook`: generic HTTP POST for teams that want to connect Slack, Teams, PagerDuty, or custom automation externally.

Current V4 baseline:

1. `NotificationService` publishes typed events to configured sinks.
2. `log` delivery is available for local and smoke verification.
3. `webhook` delivery posts the same event envelope as JSON when notifications and webhook delivery are explicitly enabled.
4. Webhook URL and authorization header values can be resolved through environment-variable indirection.
5. Webhook failures are isolated from the persisted contract or check-run decision and logged by the notification boundary.
6. Events are persisted as one deduplicated delivery record per sink in the metadata-store outbox.
7. A scheduled dispatcher delivers records asynchronously with bounded exponential backoff, terminal failure state, and stale-claim recovery after a worker crash.
8. Authenticated `GET /api/notification-deliveries` exposes redacted delivery state for operator diagnosis; dashboard readiness reflects retryable and permanent failures.

P1 candidates:

1. `email`: SMTP-based notification if there is real demand.
2. `ui`: in-app notification history or banner.

## 7. Reliability Model

Preferred implementation model:

1. Create a notification event after the check-run or contract-write decision is persisted.
2. Store the event in the metadata DB outbox.
3. Deliver asynchronously.
4. Use bounded retries with backoff.
5. Mark events as `PENDING`, `DELIVERED`, `FAILED_RETRYABLE`, or `FAILED_PERMANENT`.
6. Keep notification failure separate from check-run correctness.

Minimum reliability behavior:

1. A failed webhook call must not roll back a completed check run.
2. Delivery attempts must be visible in logs or API/UI later.
3. Duplicate notifications should be reduced with a stable `dedupeKey`.
4. Retried events must not lose the original failure context.

Current V4 implementation also uses the internal `IN_FLIGHT` state while a single worker claims a delivery. It is not a terminal state and is surfaced as degraded readiness if it remains visible during diagnosis.

## 8. Security Requirements

1. Webhook URLs and auth headers must come from env vars or secret references.
2. Webhook secrets must never be logged.
3. Notification payloads must be redacted by default.
4. TLS should be required for production webhook URLs unless explicitly overridden in local profile.
5. Timeouts must be strict to avoid hanging workers.
6. Notification configuration should fail fast in production when enabled but invalid.

## 9. Configuration Draft

```properties
notifications.enabled=false
notifications.sinks=log
notifications.webhook.enabled=false
notifications.webhook.url=
notifications.webhook.url-env=
notifications.webhook.auth-header-env=
notifications.webhook.timeout=3s
notifications.retry.max-attempts=3
notifications.retry.initial-delay=5s
notifications.payload.max-breaking-changes=10
```

Environment aliases can be added later after the property names are accepted.

## 10. Test Bar

Before GA, tests should cover:

1. Failed check run creates a notification event.
2. Rejected contract version creates a notification event.
3. New contract registration creates a notification event when lifecycle webhooks are enabled.
4. Schema version publication creates a notification event when lifecycle webhooks are enabled.
5. Policy pack resolution failure creates a notification event.
6. Passing check run does not create a failure notification.
7. Disabled notifications create no delivery attempts.
8. Webhook delivery success is recorded.
9. Webhook delivery failure is retried or marked failed.
10. Secret values are not present in logs or payloads.
11. Duplicate trigger inputs produce the expected dedupe key.
12. Notification failure does not change check-run status.

## 11. Open Questions

1. Should notification events be exposed through a read API in V4?
2. Should email be included in V4 or deferred until after generic webhooks?
3. Should warnings notify by default, or only breaking failures?
4. Should policy pack config failures block startup when notifications are enabled?
5. Should notification events live in the existing metadata DB migrations or a separate optional migration set?
