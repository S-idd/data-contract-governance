# Version 4 Spring Boot Demo Project Plan

- Plan ID: `PLAN-2026-V4-SPRING-BOOT-DEMO`
- Status: `Draft`
- Created date: `2026-05-23`
- Purpose: define the separate Spring Boot project used to validate V4 production readiness

## 1. Demo Purpose

The demo project must prove that Data Contract Governance works outside its own repository.

It should show:

1. A realistic Spring Boot service producing and consuming contract-like payloads.
2. Local developer checks through CLI.
3. Service-submitted checks through REST or SDK.
4. Runtime payload validation through the Spring Boot starter.
5. Contract-service UI triage for failed checks.
6. WebHook notification when a contract or policy breaks.
7. Recovery guidance when something fails.

## 2. Recommended Domain

Use an order fulfillment platform because it naturally has multiple contracts and realistic change pressure.

Suggested services inside the demo project:

1. `OrderController`
2. `PaymentController`
3. `InventoryController`
4. `ShipmentController`
5. `CustomerProfileController`

Suggested contracts:

1. `orders.created`
2. `payments.authorized`
3. `inventory.reserved`
4. `shipments.dispatched`
5. `customer.profile.updated`

## 3. Required Scenarios

### 3.1 Happy Path

1. Developer adds an optional field to `orders.created`.
2. CLI check passes.
3. Demo project submits check run through REST or SDK.
4. Contract-service UI shows `PASS`.
5. No breaking-change WebHook is emitted.

### 3.2 Breaking Change Path

1. Developer removes a required field or changes a field type.
2. CLI check fails.
3. Demo project submits check run through REST or SDK.
4. Contract-service UI shows `FAIL`.
5. Check detail explains the breaking change and next action.
6. Generic WebHook receiver receives `CONTRACT_CHECK_FAILED`.

### 3.3 Policy Failure Path

1. Demo contract uses stricter policy pack.
2. A schema change violates the policy.
3. Contract-service rejects or fails the check.
4. UI and WebHook show policy context.

### 3.4 Recovery Path

Pick one for the live session:

1. Metadata DB unavailable: show degraded UI and runbook action.
2. WebHook receiver unavailable: show retry/failure state.
3. Missing artifact/version: show actionable error and recovery guidance.

## 4. Project Structure

Recommended structure:

```text
dcg-spring-boot-realworld-demo/
  pom.xml
  src/main/java/...
  src/test/java/...
  contracts/
    orders.created/
      metadata.yaml
      v1.json
      v2-compatible.json
      v2-breaking.json
    payments.authorized/
    inventory.reserved/
    shipments.dispatched/
    customer.profile.updated/
  scripts/
    run-happy-path.sh
    run-breaking-path.sh
    run-webhook-receiver.sh
    reset-demo.sh
  docs/
    demo-script.md
    troubleshooting.md
```

## 5. Integration Points

The demo project should use DCG through:

1. CLI fat jar for local/CI checks.
2. REST API for check-run submission.
3. SDK if stable enough for the demo flow.
4. Spring Boot starter for runtime payload validation.
5. Generic WebHook receiver for notifications.
6. UI routes for dashboard, contracts, and check details.

## 6. Live Demo Checklist

Before the 56-person validation:

1. Demo project starts from clean checkout.
2. DCG service starts from documented command.
3. Contracts are seeded or registered predictably.
4. Happy-path script passes.
5. Breaking-path script fails in the expected way.
6. WebHook receiver prints the expected event.
7. UI shows the failed check and guidance.
8. Recovery path has been rehearsed.
9. Credentials and secrets are scrubbed.
10. Fallback screenshots are ready but not used unless necessary.

## 7. Acceptance Criteria

The demo project is V4-ready when:

1. Another engineer can run it from docs.
2. It demonstrates both `PASS` and `FAIL`.
3. It exercises REST, CLI, UI, starter, and WebHook paths.
4. It does not require editing source code during the live demo.
5. It can be reset safely between rehearsals.
6. It produces predictable output in under the planned demo time.
7. It has troubleshooting steps for the top five expected failures.

## 8. Open Questions

1. Should the demo project live inside this repo under `examples/`, or in a separate repository?
2. Should the live demo use filesystem artifacts for reliability, with S3 shown as a validated optional path?
3. Should the demo prioritize SDK or raw REST check submission?
4. Should WebHook receiver be part of the demo app or a separate tiny Spring Boot service?
5. How much intentional failure injection should be shown live versus described from rehearsal evidence?