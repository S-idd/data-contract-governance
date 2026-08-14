#!/usr/bin/env bash
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
APP_PORT="${DEMO_APP_PORT:-8081}"
SERVICE_URL="${DCG_DEMO_SERVICE_BASE_URL:-http://localhost:8080}"

if ! command -v lsof >/dev/null 2>&1; then
  echo "Missing required command: lsof" >&2
  exit 1
fi

if lsof -tiTCP:"$APP_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Port $APP_PORT is already in use. Set DEMO_APP_PORT or stop the existing process." >&2
  exit 1
fi

echo "Starting the Spring Boot order API and webhook receiver on port $APP_PORT..."
echo "Webhook inbox: http://localhost:$APP_PORT/demo/webhooks"

cd "$ROOT_DIR"
DEMO_APP_PORT="$APP_PORT" \
DCG_DEMO_SERVICE_BASE_URL="$SERVICE_URL" \
CONTRACT_VALIDATION_CONTRACTS_ROOT="$DEMO_DIR/contracts" \
mvn -pl examples/spring-boot-realworld-demo -am \
  org.springframework.boot:spring-boot-maven-plugin:run
