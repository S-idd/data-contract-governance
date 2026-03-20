#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

: "${TEST_POSTGRES_JDBC_URL:=jdbc:postgresql://localhost:5432/contracts?currentSchema=dcg_dev}"
: "${TEST_POSTGRES_USERNAME:=}"
: "${TEST_POSTGRES_PASSWORD:=}"

: "${SPRING_PROFILES_ACTIVE:=local}"
: "${APP_UI_ENABLED:=true}"
: "${APP_SECURITY_ENABLED:=true}"
: "${APP_SECURITY_USERNAME:=admin}"
: "${APP_SECURITY_PASSWORD:=change-me}"
: "${APP_SECURITY_ROLES:=USER,WRITER}"
: "${APP_SECURITY_WRITE_ROLE:=WRITER}"
: "${SERVICE_BASE_URL:=http://localhost:8080}"

if [[ -z "$TEST_POSTGRES_USERNAME" || -z "$TEST_POSTGRES_PASSWORD" ]]; then
  echo "Set TEST_POSTGRES_USERNAME and TEST_POSTGRES_PASSWORD before running this script." >&2
  exit 1
fi

SERVICE_PID=""

cleanup() {
  if [[ -n "$SERVICE_PID" ]] && kill -0 "$SERVICE_PID" 2>/dev/null; then
    kill "$SERVICE_PID" 2>/dev/null || true
    wait "$SERVICE_PID" 2>/dev/null || true
  fi
}

trap cleanup EXIT

cd "$ROOT_DIR"

"$ROOT_DIR/scripts/dev/test-all.sh"

./mvnw -pl contract-cli -am -DskipTests package

CLI_JAR="$(ls "$ROOT_DIR"/contract-cli/target/*-all.jar | head -n 1)"
if [[ -z "$CLI_JAR" ]]; then
  echo "ERROR: contract-cli shaded jar not found under contract-cli/target."
  exit 1
fi

java -jar "$CLI_JAR" check-compat \
  --base contracts/orders.created/v1.json \
  --candidate contracts/orders.created/v2.json \
  --mode BACKWARD

SPRING_PROFILES_ACTIVE="$SPRING_PROFILES_ACTIVE" \
CHECKS_DB_URL="$TEST_POSTGRES_JDBC_URL" \
CHECKS_DB_USERNAME="$TEST_POSTGRES_USERNAME" \
CHECKS_DB_PASSWORD="$TEST_POSTGRES_PASSWORD" \
APP_UI_ENABLED="$APP_UI_ENABLED" \
APP_SECURITY_ENABLED="$APP_SECURITY_ENABLED" \
APP_SECURITY_USERNAME="$APP_SECURITY_USERNAME" \
APP_SECURITY_PASSWORD="$APP_SECURITY_PASSWORD" \
APP_SECURITY_ROLES="$APP_SECURITY_ROLES" \
APP_SECURITY_WRITE_ROLE="$APP_SECURITY_WRITE_ROLE" \
./mvnw -f contract-service/pom.xml spring-boot:run &
SERVICE_PID=$!

READY=0
for _ in $(seq 1 90); do
  if curl -fsS "$SERVICE_BASE_URL/api/status" >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 1
done

if [[ "$READY" -ne 1 ]]; then
  echo "Service did not become ready on $SERVICE_BASE_URL within 90 seconds." >&2
  exit 1
fi

payload='{"contractId":"orders.created","baseVersion":"v1","candidateVersion":"v2","mode":"BACKWARD","commitSha":"local-week7","triggeredBy":"run-all"}'
auth_args=()
security_enabled="$(printf '%s' "${APP_SECURITY_ENABLED}" | tr '[:upper:]' '[:lower:]')"
case "$security_enabled" in
  true|1|yes)
    auth_args=(-u "${APP_SECURITY_USERNAME}:${APP_SECURITY_PASSWORD}")
    ;;
esac

response="$(curl -s -w "\n%{http_code}\n" "${auth_args[@]}" \
  -H "Content-Type: application/json" \
  -d "$payload" \
  "${SERVICE_BASE_URL}/checks")"

http_code="$(echo "$response" | tail -n 1)"
body="$(echo "$response" | sed '$d')"

if [[ "$http_code" != "202" && "$http_code" != "200" ]]; then
  echo "ERROR: Expected HTTP 202 from /checks. Response (${http_code}): ${body}" >&2
  exit 1
fi

run_id="$(echo "$body" | sed -n 's/.*"runId"[[:space:]]*:[[:space:]]*"\\([^"]*\\)".*/\\1/p')"

echo
echo "Service is running at $SERVICE_BASE_URL"
echo "Open:"
echo "  Dashboard: $SERVICE_BASE_URL/ui"
echo "  Contracts: $SERVICE_BASE_URL/ui/contracts"
echo "  Contract detail: $SERVICE_BASE_URL/ui/contracts/orders.created"
if [[ -n "$run_id" ]]; then
  echo "  Check detail: $SERVICE_BASE_URL/ui/checks/$run_id"
fi
echo
echo "Press Ctrl+C to stop the service."
wait "$SERVICE_PID"
