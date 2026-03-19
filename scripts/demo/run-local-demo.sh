#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLI_JAR="$ROOT_DIR/contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar"
SERVICE_DIR="$ROOT_DIR/contract-service"
SERVICE_LOG="${DEMO_SERVICE_LOG:-$ROOT_DIR/contract-service-demo.log}"
APP_PORT="${DEMO_APP_PORT:-8080}"
APP_BASE_URL="http://localhost:${APP_PORT}"
CONTRACT_ID="${DEMO_CONTRACT_ID:-orders.created}"

JDBC_URL="${DEMO_JDBC_URL:-${TEST_POSTGRES_JDBC_URL:-${CHECKS_DB_URL:-}}}"
DB_USER="${DEMO_DB_USER:-${TEST_POSTGRES_USERNAME:-${CHECKS_DB_USERNAME:-}}}"
DB_PASSWORD="${DEMO_DB_PASSWORD:-${TEST_POSTGRES_PASSWORD:-${CHECKS_DB_PASSWORD:-}}}"
DB_SCHEMA="${DEMO_DB_SCHEMA:-}"

append_schema() {
  local url="$1"
  local schema="$2"
  local delimiter="?"
  if [[ "$url" == *"?"* ]]; then
    delimiter="&"
  fi
  echo "${url}${delimiter}currentSchema=${schema}"
}

JDBC_NO_PREFIX="${JDBC_URL#jdbc:postgresql://}"
JDBC_NO_QUERY="${JDBC_NO_PREFIX%%\?*}"
JDBC_HOST_PORT="${JDBC_NO_QUERY%%/*}"
DB_NAME="${DEMO_PSQL_DATABASE:-${JDBC_NO_QUERY#*/}}"
DB_HOST="${JDBC_HOST_PORT%%:*}"
DB_PORT="${JDBC_HOST_PORT##*:}"

DEMO_TIMESTAMP="$(date +%Y%m%d%H%M%S)"
PASS_SHA="demo-pass-${DEMO_TIMESTAMP}"
FAIL_SHA="demo-fail-${DEMO_TIMESTAMP}"
FAIL_SCHEMA_PATH="$(mktemp "${TMPDIR:-/tmp}/dcg-demo-fail-XXXX.json")"
SERVICE_PID=""

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

cleanup() {
  rm -f "$FAIL_SCHEMA_PATH"
  if [[ -n "$SERVICE_PID" ]] && kill -0 "$SERVICE_PID" 2>/dev/null; then
    kill "$SERVICE_PID" 2>/dev/null || true
    wait "$SERVICE_PID" 2>/dev/null || true
  fi
}

trap cleanup EXIT

require_command mvn
require_command java
require_command curl
require_command psql
require_command lsof

if [[ -z "$JDBC_URL" || -z "$DB_USER" || -z "$DB_PASSWORD" ]]; then
  echo "Set TEST_POSTGRES_JDBC_URL, TEST_POSTGRES_USERNAME, and TEST_POSTGRES_PASSWORD before running this demo." >&2
  exit 1
fi

if [[ "$JDBC_URL" != jdbc:postgresql://*/* ]]; then
  echo "Unsupported JDBC URL format: $JDBC_URL" >&2
  exit 1
fi

if [[ -z "$DB_NAME" || "$DB_NAME" == "$JDBC_NO_QUERY" ]]; then
  echo "Unable to derive database name from JDBC URL: $JDBC_URL" >&2
  exit 1
fi

if [[ -z "$DB_SCHEMA" ]]; then
  if [[ "$JDBC_URL" == *"currentSchema="* ]]; then
    DB_SCHEMA="${JDBC_URL#*currentSchema=}"
    DB_SCHEMA="${DB_SCHEMA%%&*}"
  fi
fi

DB_SCHEMA="${DB_SCHEMA:-dcg_dev}"

if [[ "$JDBC_URL" != *"currentSchema="* ]]; then
  JDBC_URL="$(append_schema "$JDBC_URL" "$DB_SCHEMA")"
fi

if [[ "$DB_HOST" == "$JDBC_HOST_PORT" ]]; then
  DB_PORT="5432"
fi

if lsof -tiTCP:"$APP_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Port $APP_PORT is already in use. Stop the existing service before running the local demo." >&2
  exit 1
fi

echo "Ensuring schema ${DB_SCHEMA} exists..."
PGPASSWORD="$DB_PASSWORD" psql \
  -h "$DB_HOST" \
  -p "$DB_PORT" \
  -U "$DB_USER" \
  -d "$DB_NAME" \
  -Atqc "create schema if not exists ${DB_SCHEMA};"

echo "Building CLI fat jar..."
cd "$ROOT_DIR"
mvn -pl contract-cli -am package -DskipTests

if [[ ! -f "$CLI_JAR" ]]; then
  echo "CLI jar not found at $CLI_JAR" >&2
  exit 1
fi

echo "Recording PASS demo run into $DB_NAME..."
java -jar "$CLI_JAR" \
  check-compat \
  --base contracts/orders.created/v1.json \
  --candidate contracts/orders.created/v2.json \
  --mode BACKWARD \
  --record-jdbc-url "$JDBC_URL" \
  --record-db-user "$DB_USER" \
  --record-db-password "$DB_PASSWORD" \
  --contract-id "$CONTRACT_ID" \
  --commit-sha "$PASS_SHA"

cat <<'JSON' > "$FAIL_SCHEMA_PATH"
{"type":"object","properties":{"orderId":{"type":"integer"}}}
JSON

echo "Recording FAIL demo run into $DB_NAME..."
set +e
java -jar "$CLI_JAR" \
  check-compat \
  --base contracts/orders.created/v1.json \
  --candidate "$FAIL_SCHEMA_PATH" \
  --mode BACKWARD \
  --record-jdbc-url "$JDBC_URL" \
  --record-db-user "$DB_USER" \
  --record-db-password "$DB_PASSWORD" \
  --contract-id "$CONTRACT_ID" \
  --commit-sha "$FAIL_SHA"
FAIL_EXIT_CODE=$?
set -e

if [[ "$FAIL_EXIT_CODE" -ne 1 ]]; then
  echo "Expected the FAIL demo run to exit with status 1. Actual exit code: $FAIL_EXIT_CODE" >&2
  exit 1
fi

echo "Starting contract-service on port $APP_PORT..."
(
  cd "$ROOT_DIR"
  SPRING_PROFILES_ACTIVE=local \
  CHECKS_DB_URL="$JDBC_URL" \
  CHECKS_DB_USERNAME="$DB_USER" \
  CHECKS_DB_PASSWORD="$DB_PASSWORD" \
  APP_UI_ENABLED=true \
  APP_SECURITY_ENABLED=false \
  mvn -pl contract-service -am org.springframework.boot:spring-boot-maven-plugin:run >"$SERVICE_LOG" 2>&1
) &
SERVICE_PID=$!

READY=0
for _ in $(seq 1 90); do
  if curl -fsS "$APP_BASE_URL/api/status" >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 1
done

if [[ "$READY" -ne 1 ]]; then
  echo "Service did not become ready on $APP_BASE_URL within 90 seconds." >&2
  echo "Recent log output:" >&2
  tail -n 40 "$SERVICE_LOG" >&2 || true
  exit 1
fi

PASS_RUN_ID="$(
  PGPASSWORD="$DB_PASSWORD" psql \
    -h "$DB_HOST" \
    -p "$DB_PORT" \
    -U "$DB_USER" \
    -d "$DB_NAME" \
    -Atqc "select run_id from ${DB_SCHEMA}.check_runs where commit_sha = '${PASS_SHA}' order by created_at desc limit 1;"
)"

FAIL_RUN_ID="$(
  PGPASSWORD="$DB_PASSWORD" psql \
    -h "$DB_HOST" \
    -p "$DB_PORT" \
    -U "$DB_USER" \
    -d "$DB_NAME" \
    -Atqc "select run_id from ${DB_SCHEMA}.check_runs where commit_sha = '${FAIL_SHA}' order by created_at desc limit 1;"
)"

echo
echo "Local demo is ready."
echo "Service log: $SERVICE_LOG"
echo
echo "Open these URLs in your browser:"
echo "  Dashboard: $APP_BASE_URL/ui"
echo "  Contracts: $APP_BASE_URL/ui/contracts"
echo "  Contract detail: $APP_BASE_URL/ui/contracts/$CONTRACT_ID"

if [[ -n "$PASS_RUN_ID" ]]; then
  echo "  PASS check detail: $APP_BASE_URL/ui/checks/$PASS_RUN_ID"
fi

if [[ -n "$FAIL_RUN_ID" ]]; then
  echo "  FAIL check detail: $APP_BASE_URL/ui/checks/$FAIL_RUN_ID"
fi

echo
echo "Useful API calls:"
echo "  curl \"$APP_BASE_URL/checks?contractId=$CONTRACT_ID\""
echo "  curl \"$APP_BASE_URL/checks/page?contractId=$CONTRACT_ID&status=FAIL&limit=20&offset=0\""
echo
echo "Press Ctrl+C to stop the demo service."
wait "$SERVICE_PID"
