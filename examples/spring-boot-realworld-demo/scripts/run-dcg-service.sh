#!/usr/bin/env bash
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
RUNTIME_DIR="$DEMO_DIR/.runtime"
SERVICE_PORT="${DCG_DEMO_SERVICE_PORT:-8080}"
APP_PORT="${DEMO_APP_PORT:-8081}"

if ! command -v lsof >/dev/null 2>&1; then
  echo "Missing required command: lsof" >&2
  exit 1
fi

if lsof -tiTCP:"$SERVICE_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Port $SERVICE_PORT is already in use. Set DCG_DEMO_SERVICE_PORT or stop the existing process." >&2
  exit 1
fi

mkdir -p "$RUNTIME_DIR/contracts"

echo "Starting contract-service on port $SERVICE_PORT..."
echo "Runtime metadata and artifacts: $RUNTIME_DIR"

cd "$ROOT_DIR"
SERVER_PORT="$SERVICE_PORT" \
CONTRACTS_ROOT="$RUNTIME_DIR/contracts" \
CHECKS_DB_PATH="$RUNTIME_DIR/checks.db" \
CONTRACTS_VALIDATION_STRICT_MODE=false \
CHECKS_RUNNER_POLL_INTERVAL=1s \
NOTIFICATIONS_ENABLED=true \
NOTIFICATIONS_SINKS=webhook \
NOTIFICATIONS_WEBHOOK_ENABLED=true \
NOTIFICATIONS_WEBHOOK_URL="http://localhost:$APP_PORT/demo/webhooks" \
NOTIFICATIONS_DISPATCH_POLL_INTERVAL_MS=1000 \
APP_UI_ENABLED=true \
APP_SECURITY_ENABLED=false \
mvn -pl contract-service -am org.springframework.boot:spring-boot-maven-plugin:run
