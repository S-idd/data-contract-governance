# V4 Spring Boot Real-World Demo

This order-fulfillment demo is the runnable V4 validation project. It proves the CLI, SDK check submission, Spring Boot validation starter, contract-service UI, and generic webhook delivery from outside the main service module.

## What It Demonstrates

1. `POST /orders` validates an `orders.created` payload with `contract-validation-spring-boot-starter`.
2. `POST /demo/checks/happy` uses `contract-sdk` to submit the compatible `v1 -> v2` check.
3. `POST /demo/checks/breaking` uses `contract-sdk` to submit the breaking `v2 -> v3` check.
4. `POST /demo/webhooks` is a generic webhook receiver; `GET /demo/webhooks` shows received events.

The demo service stores its database and registered contract artifacts only under `.runtime/`. The source schemas under `contracts/` remain unchanged.

## Rehearsal

Open three terminals from this directory.

Terminal 1 starts the Spring Boot order API and webhook receiver:

```bash
bash scripts/run-webhook-receiver.sh
```

Terminal 2 starts contract-service with isolated runtime state, a one-second check runner, and webhook notifications directed at the demo receiver:

```bash
bash scripts/run-dcg-service.sh
```

Terminal 3 registers the three demo contract versions, then runs the compatible and breaking flows:

```bash
bash scripts/seed-contracts.sh
bash scripts/run-happy-path.sh
bash scripts/run-breaking-path.sh
```

The happy script verifies a CLI `PASS` and an SDK-submitted `PASS`. The breaking script verifies the CLI exit code, the SDK-submitted `FAIL`, the contract-service check detail page, and a received `CONTRACT_CHECK_FAILED` webhook.

During the breaking flow, open:

```text
http://localhost:8080/ui
http://localhost:8080/ui/notifications
http://localhost:8081/demo/webhooks
```

`v3` is intentionally registered with strict version enforcement disabled only for this local demo service. That lets the asynchronous check runner surface the failure and notification. Production deployments should retain strict version enforcement unless an operator has an explicit staged-publication workflow.

## Runtime Payload Validation

With the demo app running, this payload is accepted:

```bash
curl -i -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' \
  --data '{"orderId":"order-123","status":"CREATED","amount":42.50}'
```

This payload is rejected by the starter because `orderId` must be a string in `v1`:

```bash
curl -i -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' \
  --data '{"orderId":123,"status":"CREATED"}'
```

## Reset

After stopping both services, clear only the demo's generated state:

```bash
bash scripts/reset-demo.sh
```

The reset script removes `examples/spring-boot-realworld-demo/.runtime/` and never touches source contracts or any repository-level database.
