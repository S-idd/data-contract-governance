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

echo "Running the compatible change through the CLI..."
java -jar "$CLI_JAR" check-compat \
  --base "$CONTRACT_DIR/v1.json" \
  --candidate "$CONTRACT_DIR/v2.json" \
  --mode BACKWARD

echo "Submitting the same check through the Spring Boot app SDK integration..."
submission="$(curl -fsS -X POST "$APP_URL/demo/checks/happy")"
run_id="$(printf '%s' "$submission" | sed -n 's/.*"runId":"\([^"]*\)".*/\1/p')"

if [[ -z "$run_id" ]]; then
  echo "The demo app returned an unexpected submission response: $submission" >&2
  exit 1
fi

for _ in $(seq 1 30); do
  check="$(curl -fsS "$SERVICE_URL/checks/$run_id")"
  if [[ "$check" == *'"status":"PASS"'* ]]; then
    echo "PASS check completed: $SERVICE_URL/ui/checks/$run_id"
    exit 0
  fi
  sleep 1
done

echo "The happy-path check did not reach PASS within 30 seconds: $SERVICE_URL/ui/checks/$run_id" >&2
exit 1
