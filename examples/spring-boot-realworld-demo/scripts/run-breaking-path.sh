#!/usr/bin/env bash
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
CLI_JAR="$ROOT_DIR/contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar"
SERVICE_URL="${DCG_DEMO_SERVICE_BASE_URL:-http://localhost:8080}"
APP_URL="${DEMO_APP_URL:-http://localhost:8081}"
CONTRACT_DIR="$DEMO_DIR/contracts/orders.created"

if ! command -v curl >/dev/null 2>&1 || ! command -v java >/dev/null 2>&1; then
  echo "Missing required command: curl or java" >&2
  exit 1
fi

echo "Building the CLI fat jar from clean sources..."
cd "$ROOT_DIR"
./mvnw -pl contract-cli -am clean package -DskipTests

echo "Running the breaking change through the CLI..."
set +e
java -jar "$CLI_JAR" check-compat \
  --base "$CONTRACT_DIR/v2.json" \
  --candidate "$CONTRACT_DIR/v3.json" \
  --mode BACKWARD
cli_exit_code=$?
set -e

if [[ "$cli_exit_code" -ne 1 ]]; then
  echo "Expected the breaking CLI check to exit 1, received $cli_exit_code." >&2
  exit 1
fi

echo "Submitting the breaking check through the Spring Boot app SDK integration..."
submission="$(curl -fsS -X POST "$APP_URL/demo/checks/breaking")"
run_id="$(printf '%s' "$submission" | sed -n 's/.*"runId":"\([^"]*\)".*/\1/p')"

if [[ -z "$run_id" ]]; then
  echo "The demo app returned an unexpected submission response: $submission" >&2
  exit 1
fi

for _ in $(seq 1 30); do
  check="$(curl -fsS "$SERVICE_URL/checks/$run_id")"
  webhook_events="$(curl -fsS "$APP_URL/demo/webhooks")"
  if [[ "$check" == *'"status":"FAIL"'* ]] \
      && [[ "$webhook_events" == *'"eventType":"CONTRACT_CHECK_FAILED"'* ]]; then
    echo "FAIL check completed: $SERVICE_URL/ui/checks/$run_id"
    echo "Webhook received: $APP_URL/demo/webhooks"
    exit 0
  fi
  sleep 1
done

echo "The breaking-path check or webhook did not complete within 30 seconds." >&2
echo "Check detail: $SERVICE_URL/ui/checks/$run_id" >&2
echo "Webhook inbox: $APP_URL/demo/webhooks" >&2
exit 1
