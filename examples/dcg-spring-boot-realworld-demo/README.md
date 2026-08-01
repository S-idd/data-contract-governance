# DCG Spring Boot Order Fulfillment Demo

This independent Spring Boot application demonstrates the V4 integration path for runtime payload validation, CLI checks, REST-submitted checks, UI triage, and generic WebHook delivery.

## What It Demonstrates

1. `POST /demo/orders` validates an `orders.created` payload through the DCG Spring Boot starter.
2. `POST /demo/payments` validates a `payments.authorized` payload through the same starter.
3. `scripts/run-happy-path.sh` shows a compatible contract change as `PASS` through CLI and REST.
4. `scripts/run-breaking-path.sh` shows a breaking contract change as `FAIL`, then verifies the WebHook receiver.
5. `GET /demo/webhooks` shows captured generic WebHook events.

## Prerequisites

- Java 21
- Bash and `curl`
- Docker Desktop only when using the optional compose path

## Demo Commands

Run every command below from the repository root.

### 1. Build

```bash
./mvnw clean install \
  -pl contract-cli,contract-service,examples/dcg-spring-boot-realworld-demo \
  -am
```

### 2. Start the Demo Application

Open a terminal and leave it running:

```bash
DEMO_CONTRACTS_ROOT="$PWD/examples/dcg-spring-boot-realworld-demo/contracts" \
./mvnw -pl examples/dcg-spring-boot-realworld-demo spring-boot:run
```

The demo application runs at `http://127.0.0.1:8090`.

### 3. Start Contract Service

Open a second terminal and leave it running:

```bash
CONTRACTS_ROOT="$PWD/examples/dcg-spring-boot-realworld-demo/contracts" \
CHECKS_DB_PATH=/tmp/dcg-v4-demo-checks.db \
APP_SECURITY_ENABLED=true \
APP_SECURITY_USERNAME=demo \
APP_SECURITY_PASSWORD=demo-pass \
NOTIFICATIONS_ENABLED=true \
NOTIFICATIONS_SINKS=webhook \
NOTIFICATIONS_WEBHOOK_ENABLED=true \
NOTIFICATIONS_WEBHOOK_URL=http://127.0.0.1:8090/demo/webhooks \
./mvnw -pl contract-service spring-boot:run
```

Contract-service runs at `http://127.0.0.1:8080`.

### 4. Verify Runtime Payload Validation

In a third terminal:

```bash
curl -fsS -H "Content-Type: application/json" \
  -d '{"orderId":"ord-100","status":"CREATED","amount":42.50}' \
  http://127.0.0.1:8090/demo/orders
```

This invalid payload should return `CONTRACT_PAYLOAD_INVALID`:

```bash
curl -i -H "Content-Type: application/json" \
  -d '{"orderId":"ord-100","amount":42.50}' \
  http://127.0.0.1:8090/demo/orders
```

### 5. Run the Compatible Contract Scenario

```bash
DCG_AUTH=demo:demo-pass \
bash examples/dcg-spring-boot-realworld-demo/scripts/run-happy-path.sh
```

Open the printed check-detail URL to show the passing run in the DCG UI.

### 6. Run the Breaking Contract and WebHook Scenario

```bash
DCG_AUTH=demo:demo-pass \
bash examples/dcg-spring-boot-realworld-demo/scripts/run-breaking-path.sh
```

Open these URLs during the explanation:

```text
http://127.0.0.1:8080/ui
http://127.0.0.1:8080/api/notification-deliveries
http://127.0.0.1:8090/demo/webhooks
```

### 7. Reset Between Rehearsals

Stop contract-service, then run:

```bash
bash examples/dcg-spring-boot-realworld-demo/scripts/reset-demo.sh
```

Restart contract-service and repeat the scenarios.

## Optional Compose Service Path

The repository also includes a PostgreSQL-backed compose demo:

```bash
bash scripts/demo/run-compose-demo.sh
```

Use the local service command above for the Spring Boot demo because it points contract-service at this demo module's contracts and local WebHook receiver.
