#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SERVICE_JAR="${DCG_POSTGRES_RECOVERY_SERVICE_JAR:-$ROOT_DIR/contract-service/target/contract-service-0.1.0-SNAPSHOT.jar}"
POSTGRES_IMAGE="${DCG_POSTGRES_RECOVERY_IMAGE:-postgres:16}"
POSTGRES_PORT="${DCG_POSTGRES_RECOVERY_DB_PORT:-15432}"
SERVICE_PORT="${DCG_POSTGRES_RECOVERY_SERVICE_PORT:-18081}"
APP_USERNAME="${DCG_POSTGRES_RECOVERY_USERNAME:-recovery-demo}"
APP_PASSWORD="${DCG_POSTGRES_RECOVERY_PASSWORD:-recovery-demo-pass}"
DB_NAME="contracts"
DB_USERNAME="recovery_demo"
DB_PASSWORD="${DCG_POSTGRES_RECOVERY_DB_PASSWORD:-recovery-db-pass}"
KEEP_CONTAINER="${DCG_POSTGRES_RECOVERY_KEEP_CONTAINER:-false}"

STAMP="$(date +%Y%m%d%H%M%S)"
SUFFIX="${STAMP}_${RANDOM}"
POSTGRES_CONTAINER="dcg-postgres-recovery-${SUFFIX}"
SOURCE_SCHEMA="dcg_recovery_src_${STAMP}_${RANDOM}"
RESTORE_DB="dcg_recovery_restore_${STAMP}_${RANDOM}"
BACKUP_FILE="/tmp/dcg-recovery-${SUFFIX}.dump"
SERVICE_URL="http://127.0.0.1:${SERVICE_PORT}"

SERVICE_PID=""
SERVICE_LOG=""
RUN_ID=""
COMPLETED=false

usage() {
  cat <<'EOF'
Usage:
  scripts/demo/run-postgres-recovery-drill.sh

Runs an isolated PostgreSQL recovery drill using a temporary postgres:16 Docker
container. It creates a disposable source schema, persists a DCG check run,
backs up that schema, restores it into a separate database, and proves DCG can
read the same check run from the restored target.

Required commands: docker, java, curl, lsof

Optional environment variables:
  DCG_POSTGRES_RECOVERY_DB_PORT       PostgreSQL host port (default: 15432)
  DCG_POSTGRES_RECOVERY_SERVICE_PORT  Contract-service port (default: 18081)
  DCG_POSTGRES_RECOVERY_IMAGE         PostgreSQL image (default: postgres:16)
  DCG_POSTGRES_RECOVERY_SERVICE_JAR   Built contract-service jar path
  DCG_POSTGRES_RECOVERY_KEEP_CONTAINER Set true to retain the database container after completion
  DCG_POSTGRES_RECOVERY_USERNAME      Local Basic-auth username
  DCG_POSTGRES_RECOVERY_PASSWORD      Local Basic-auth password
  DCG_POSTGRES_RECOVERY_DB_PASSWORD   Temporary database password
EOF
}

log() {
  printf '[dcg-postgres-recovery] %s\n' "$*"
}

show_service_log() {
  if [[ -n "$SERVICE_LOG" ]] && [[ -f "$SERVICE_LOG" ]]; then
    printf '\nRecent contract-service log output:\n' >&2
    tail -n 80 "$SERVICE_LOG" >&2 || true
  fi
}

show_postgres_log() {
  if [[ -n "$POSTGRES_CONTAINER" ]] \
      && docker container inspect "$POSTGRES_CONTAINER" >/dev/null 2>&1; then
    printf '\nRecent PostgreSQL container log output:\n' >&2
    docker logs --tail 80 "$POSTGRES_CONTAINER" >&2 || true
  fi
}

die() {
  printf '[dcg-postgres-recovery] ERROR: %s\n' "$*" >&2
  show_service_log
  show_postgres_log
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Missing required command: $1"
}

is_true() {
  case "$1" in
    true|TRUE|True|1|yes|YES|Yes) return 0 ;;
    *) return 1 ;;
  esac
}

stop_service() {
  if [[ -z "$SERVICE_PID" ]] || ! kill -0 "$SERVICE_PID" 2>/dev/null; then
    SERVICE_PID=""
    return
  fi

  kill "$SERVICE_PID" 2>/dev/null || true
  for _ in $(seq 1 15); do
    if ! kill -0 "$SERVICE_PID" 2>/dev/null; then
      break
    fi
    sleep 1
  done
  if kill -0 "$SERVICE_PID" 2>/dev/null; then
    kill -9 "$SERVICE_PID" 2>/dev/null || true
  fi
  wait "$SERVICE_PID" 2>/dev/null || true
  SERVICE_PID=""
}

cleanup() {
  local exit_code="$?"
  stop_service

  if [[ "$exit_code" -ne 0 ]] || is_true "$KEEP_CONTAINER"; then
    if docker container inspect "$POSTGRES_CONTAINER" >/dev/null 2>&1; then
      log "Retained PostgreSQL drill container: $POSTGRES_CONTAINER"
    fi
    return "$exit_code"
  fi

  if docker container inspect "$POSTGRES_CONTAINER" >/dev/null 2>&1; then
    docker rm -f "$POSTGRES_CONTAINER" >/dev/null
  fi
  return "$exit_code"
}

wait_for_postgres() {
  for _ in $(seq 1 60); do
    if docker exec "$POSTGRES_CONTAINER" pg_isready -U "$DB_USERNAME" -d "$DB_NAME" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  die "PostgreSQL container did not become ready within 60 seconds."
}

wait_for_service() {
  for _ in $(seq 1 60); do
    if curl -fsS "$SERVICE_URL/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  die "Service did not become healthy at $SERVICE_URL within 60 seconds."
}

service_jdbc_url() {
  local database="$1"
  printf 'jdbc:postgresql://127.0.0.1:%s/%s?currentSchema=%s' \
    "$POSTGRES_PORT" "$database" "$SOURCE_SCHEMA"
}

start_service() {
  local database="$1"
  local phase="$2"
  SERVICE_LOG="${TMPDIR:-/tmp}/dcg-postgres-recovery-${SUFFIX}-${phase}.log"

  SPRING_PROFILES_ACTIVE=local \
  SERVER_PORT="$SERVICE_PORT" \
  CHECKS_DB_URL="$(service_jdbc_url "$database")" \
  CHECKS_DB_USERNAME="$DB_USERNAME" \
  CHECKS_DB_PASSWORD="$DB_PASSWORD" \
  CHECKS_DB_EXPECTED_SCHEMA="$SOURCE_SCHEMA" \
  CHECKS_DB_FAIL_FAST_STARTUP=true \
  CHECKS_DB_ENFORCE_SECURE_POSTGRES=false \
  CHECKS_DB_SSL_ENABLED=false \
  CONTRACTS_ROOT="$ROOT_DIR/contracts" \
  APP_SECURITY_ENABLED=true \
  APP_SECURITY_USERNAME="$APP_USERNAME" \
  APP_SECURITY_PASSWORD="$APP_PASSWORD" \
  java -jar "$SERVICE_JAR" >"$SERVICE_LOG" 2>&1 &
  SERVICE_PID=$!
  wait_for_service
}

submit_passing_check() {
  local response
  response="$(curl -fsS -u "${APP_USERNAME}:${APP_PASSWORD}" \
    -H "Content-Type: application/json" \
    -d '{"contractId":"orders.created","baseVersion":"v1","candidateVersion":"v2","mode":"BACKWARD","commitSha":"postgres-recovery-drill","triggeredBy":"recovery-drill"}' \
    "$SERVICE_URL/checks")"
  RUN_ID="$(printf '%s' "$response" | sed -nE 's/.*"runId"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p')"
  [[ -n "$RUN_ID" ]] || die "Could not read runId from check submission: $response"
}

wait_for_passing_run() {
  local run
  for _ in $(seq 1 60); do
    run="$(curl -fsS -u "${APP_USERNAME}:${APP_PASSWORD}" "$SERVICE_URL/checks/$RUN_ID")"
    if printf '%s' "$run" | grep -q '"status":"PASS"'; then
      return 0
    fi
    if printf '%s' "$run" | grep -Eq '"status":"(FAIL|ERROR)"'; then
      die "Recovery drill check did not pass: $run"
    fi
    sleep 1
  done
  die "Timed out waiting for check $RUN_ID to pass."
}

query_restore_db() {
  local sql="$1"
  docker exec -e "PGPASSWORD=$DB_PASSWORD" "$POSTGRES_CONTAINER" \
    psql -v ON_ERROR_STOP=1 -U "$DB_USERNAME" -d "$RESTORE_DB" -Atqc "$sql"
}

verify_restored_database() {
  local run_count migration_count notification_table_count
  run_count="$(query_restore_db "select count(*) from ${SOURCE_SCHEMA}.check_runs where run_id = '${RUN_ID}';")"
  [[ "$run_count" == "1" ]] || die "Restored database does not contain check run $RUN_ID."

  migration_count="$(query_restore_db "select count(*) from ${SOURCE_SCHEMA}.flyway_schema_history where success = true;")"
  [[ "$migration_count" -ge 7 ]] || die "Restored database does not contain the expected Flyway history."

  notification_table_count="$(query_restore_db "select count(*) from information_schema.tables where table_schema = '${SOURCE_SCHEMA}' and table_name = 'notification_deliveries';")"
  [[ "$notification_table_count" == "1" ]] || die "Restored database is missing notification_deliveries."
}

verify_restored_service_run() {
  local run
  run="$(curl -fsS -u "${APP_USERNAME}:${APP_PASSWORD}" "$SERVICE_URL/checks/$RUN_ID")"
  printf '%s' "$run" | grep -q '"contractId":"orders.created"' \
    || die "Restored service did not return the original contract run: $run"
  printf '%s' "$run" | grep -q '"status":"PASS"' \
    || die "Restored service did not return a passing run: $run"
}

trap cleanup EXIT

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi
if [[ $# -gt 0 ]]; then
  usage >&2
  exit 2
fi

require_command docker
require_command java
require_command curl
require_command lsof
docker info >/dev/null 2>&1 || die "Docker daemon is not reachable. Start Docker Desktop and retry."

[[ -f "$SERVICE_JAR" ]] || die "Service jar not found: $SERVICE_JAR. Build it with ./mvnw -pl contract-service -am package"
if lsof -tiTCP:"$POSTGRES_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  die "Port $POSTGRES_PORT is already in use. Set DCG_POSTGRES_RECOVERY_DB_PORT to an unused port."
fi
if lsof -tiTCP:"$SERVICE_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  die "Port $SERVICE_PORT is already in use. Set DCG_POSTGRES_RECOVERY_SERVICE_PORT to an unused port."
fi

log "Starting isolated PostgreSQL container $POSTGRES_CONTAINER."
docker run --detach --rm \
  --name "$POSTGRES_CONTAINER" \
  --publish "127.0.0.1:${POSTGRES_PORT}:5432" \
  --env "POSTGRES_DB=$DB_NAME" \
  --env "POSTGRES_USER=$DB_USERNAME" \
  --env "POSTGRES_PASSWORD=$DB_PASSWORD" \
  "$POSTGRES_IMAGE" >/dev/null
wait_for_postgres

log "Starting contract-service against disposable source schema $SOURCE_SCHEMA."
start_service "$DB_NAME" "source"

log "Submitting a check run to persist recovery evidence."
submit_passing_check
wait_for_passing_run

log "Creating a custom-format backup of source schema $SOURCE_SCHEMA."
docker exec -e "PGPASSWORD=$DB_PASSWORD" "$POSTGRES_CONTAINER" \
  pg_dump --format=custom --no-owner --no-privileges \
  --schema="$SOURCE_SCHEMA" -U "$DB_USERNAME" -d "$DB_NAME" \
  --file="$BACKUP_FILE"
docker exec "$POSTGRES_CONTAINER" test -s "$BACKUP_FILE" \
  || die "PostgreSQL backup was not created: $BACKUP_FILE"

log "Stopping the source service before switching to the restored target."
stop_service

log "Restoring backup into separate database $RESTORE_DB."
docker exec -e "PGPASSWORD=$DB_PASSWORD" "$POSTGRES_CONTAINER" \
  createdb -U "$DB_USERNAME" "$RESTORE_DB"
docker exec -e "PGPASSWORD=$DB_PASSWORD" "$POSTGRES_CONTAINER" \
  pg_restore --clean --if-exists --no-owner --no-privileges \
  -U "$DB_USERNAME" --dbname="$RESTORE_DB" "$BACKUP_FILE" >/dev/null
verify_restored_database

log "Starting contract-service against the restored database."
start_service "$RESTORE_DB" "restored"
verify_restored_service_run

COMPLETED=true
log "PASS: restored check $RUN_ID is readable from $RESTORE_DB after service restart."
log "PostgreSQL backup, restore, Flyway history, notification table, and application read checks completed."
