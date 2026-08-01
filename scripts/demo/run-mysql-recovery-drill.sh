#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SERVICE_JAR="${DCG_MYSQL_RECOVERY_SERVICE_JAR:-$ROOT_DIR/contract-service/target/contract-service-0.1.0-SNAPSHOT.jar}"
MYSQL_IMAGE="${DCG_MYSQL_RECOVERY_IMAGE:-mysql:8.4}"
MYSQL_PORT="${DCG_MYSQL_RECOVERY_DB_PORT:-13306}"
SERVICE_PORT="${DCG_MYSQL_RECOVERY_SERVICE_PORT:-18082}"
APP_USERNAME="${DCG_MYSQL_RECOVERY_USERNAME:-recovery-demo}"
APP_PASSWORD="${DCG_MYSQL_RECOVERY_PASSWORD:-recovery-demo-pass}"
DB_USERNAME="recovery_demo"
DB_PASSWORD="${DCG_MYSQL_RECOVERY_DB_PASSWORD:-recovery-db-pass}"
ROOT_PASSWORD="${DCG_MYSQL_RECOVERY_ROOT_PASSWORD:-recovery-root-pass}"
KEEP_CONTAINER="${DCG_MYSQL_RECOVERY_KEEP_CONTAINER:-false}"

STAMP="$(date +%Y%m%d%H%M%S)"
SUFFIX="${STAMP}_${RANDOM}"
MYSQL_CONTAINER="dcg-mysql-recovery-${SUFFIX}"
SOURCE_DB="dcg_recovery_src_${STAMP}_${RANDOM}"
RESTORE_DB="dcg_recovery_restore_${STAMP}_${RANDOM}"
BACKUP_FILE="/tmp/dcg-mysql-recovery-${SUFFIX}.sql"
SERVICE_URL="http://127.0.0.1:${SERVICE_PORT}"

SERVICE_PID=""
SERVICE_LOG=""
RUN_ID=""

usage() {
  cat <<'EOF'
Usage:
  scripts/demo/run-mysql-recovery-drill.sh

Runs an isolated MySQL recovery drill using a temporary mysql:8.4 Docker
container. It creates a disposable source database, persists a DCG check run,
creates a consistent logical backup, restores it into a separate database, and
proves DCG can read the same check run from the restored target.

Required commands: docker, java, curl, lsof

Optional environment variables:
  DCG_MYSQL_RECOVERY_DB_PORT       MySQL host port (default: 13306)
  DCG_MYSQL_RECOVERY_SERVICE_PORT  Contract-service port (default: 18082)
  DCG_MYSQL_RECOVERY_IMAGE         MySQL image (default: mysql:8.4)
  DCG_MYSQL_RECOVERY_SERVICE_JAR   Built contract-service jar path
  DCG_MYSQL_RECOVERY_KEEP_CONTAINER Set true to retain the database container after completion
  DCG_MYSQL_RECOVERY_USERNAME      Local Basic-auth username
  DCG_MYSQL_RECOVERY_PASSWORD      Local Basic-auth password
  DCG_MYSQL_RECOVERY_DB_PASSWORD   Temporary application database password
  DCG_MYSQL_RECOVERY_ROOT_PASSWORD Temporary root database password
EOF
}

log() {
  printf '[dcg-mysql-recovery] %s\n' "$*"
}

show_service_log() {
  if [[ -n "$SERVICE_LOG" ]] && [[ -f "$SERVICE_LOG" ]]; then
    printf '\nRecent contract-service log output:\n' >&2
    tail -n 80 "$SERVICE_LOG" >&2 || true
  fi
}

show_mysql_log() {
  if [[ -n "$MYSQL_CONTAINER" ]] \
      && docker container inspect "$MYSQL_CONTAINER" >/dev/null 2>&1; then
    printf '\nRecent MySQL container log output:\n' >&2
    docker logs --tail 80 "$MYSQL_CONTAINER" >&2 || true
  fi
}

die() {
  printf '[dcg-mysql-recovery] ERROR: %s\n' "$*" >&2
  show_service_log
  show_mysql_log
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
    if docker container inspect "$MYSQL_CONTAINER" >/dev/null 2>&1; then
      log "Retained MySQL drill container: $MYSQL_CONTAINER"
    fi
    return "$exit_code"
  fi

  if docker container inspect "$MYSQL_CONTAINER" >/dev/null 2>&1; then
    docker rm -f "$MYSQL_CONTAINER" >/dev/null
  fi
  return "$exit_code"
}

mysql_root() {
  docker exec -e "MYSQL_PWD=$ROOT_PASSWORD" "$MYSQL_CONTAINER" \
    mysql --protocol=socket -uroot "$@"
}

mysql_root_query() {
  local database="$1"
  local sql="$2"
  docker exec -e "MYSQL_PWD=$ROOT_PASSWORD" "$MYSQL_CONTAINER" \
    mysql --protocol=socket -uroot -N -B "$database" -e "$sql"
}

wait_for_mysql() {
  for _ in $(seq 1 90); do
    if mysql_root -e "select 1" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  die "MySQL container did not become ready within 90 seconds."
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
  printf 'jdbc:mysql://127.0.0.1:%s/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
    "$MYSQL_PORT" "$database"
}

start_service() {
  local database="$1"
  local phase="$2"
  SERVICE_LOG="${TMPDIR:-/tmp}/dcg-mysql-recovery-${SUFFIX}-${phase}.log"

  SPRING_PROFILES_ACTIVE=local \
  SERVER_PORT="$SERVICE_PORT" \
  CHECKS_DB_URL="$(service_jdbc_url "$database")" \
  CHECKS_DB_USERNAME="$DB_USERNAME" \
  CHECKS_DB_PASSWORD="$DB_PASSWORD" \
  CHECKS_DB_FAIL_FAST_STARTUP=true \
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
    -d '{"contractId":"orders.created","baseVersion":"v1","candidateVersion":"v2","mode":"BACKWARD","commitSha":"mysql-recovery-drill","triggeredBy":"recovery-drill"}' \
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

verify_restored_database() {
  local run_count migration_count notification_table_count
  run_count="$(mysql_root_query "$RESTORE_DB" "select count(*) from check_runs where run_id = '${RUN_ID}';")"
  [[ "$run_count" == "1" ]] || die "Restored database does not contain check run $RUN_ID."

  migration_count="$(mysql_root_query "$RESTORE_DB" "select count(*) from flyway_schema_history where success = 1;")"
  [[ "$migration_count" -ge 7 ]] || die "Restored database does not contain the expected Flyway history."

  notification_table_count="$(mysql_root_query "$RESTORE_DB" "select count(*) from information_schema.tables where table_schema = '${RESTORE_DB}' and table_name = 'notification_deliveries';")"
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
if lsof -tiTCP:"$MYSQL_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  die "Port $MYSQL_PORT is already in use. Set DCG_MYSQL_RECOVERY_DB_PORT to an unused port."
fi
if lsof -tiTCP:"$SERVICE_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  die "Port $SERVICE_PORT is already in use. Set DCG_MYSQL_RECOVERY_SERVICE_PORT to an unused port."
fi

log "Starting isolated MySQL container $MYSQL_CONTAINER."
docker run --detach --rm \
  --name "$MYSQL_CONTAINER" \
  --publish "127.0.0.1:${MYSQL_PORT}:3306" \
  --env "MYSQL_DATABASE=contracts" \
  --env "MYSQL_USER=$DB_USERNAME" \
  --env "MYSQL_PASSWORD=$DB_PASSWORD" \
  --env "MYSQL_ROOT_PASSWORD=$ROOT_PASSWORD" \
  "$MYSQL_IMAGE" >/dev/null
wait_for_mysql

mysql_root -e "create database \`${SOURCE_DB}\`;"
mysql_root -e "grant all privileges on \`${SOURCE_DB}\`.* to '${DB_USERNAME}'@'%'; flush privileges;"

log "Starting contract-service against disposable source database $SOURCE_DB."
start_service "$SOURCE_DB" "source"

log "Submitting a check run to persist recovery evidence."
submit_passing_check
wait_for_passing_run

log "Creating a consistent logical backup of source database $SOURCE_DB."
docker exec -e "MYSQL_PWD=$ROOT_PASSWORD" "$MYSQL_CONTAINER" \
  sh -c "mysqldump -uroot --single-transaction --routines --triggers '$SOURCE_DB' > '$BACKUP_FILE'"
docker exec "$MYSQL_CONTAINER" test -s "$BACKUP_FILE" \
  || die "MySQL backup was not created: $BACKUP_FILE"

log "Stopping the source service before switching to the restored target."
stop_service

log "Restoring backup into separate database $RESTORE_DB."
mysql_root -e "create database \`${RESTORE_DB}\`;"
mysql_root -e "grant all privileges on \`${RESTORE_DB}\`.* to '${DB_USERNAME}'@'%'; flush privileges;"
docker exec -e "MYSQL_PWD=$ROOT_PASSWORD" "$MYSQL_CONTAINER" \
  sh -c "mysql -uroot '$RESTORE_DB' < '$BACKUP_FILE'"
verify_restored_database

log "Starting contract-service against the restored database."
start_service "$RESTORE_DB" "restored"
verify_restored_service_run

log "PASS: restored check $RUN_ID is readable from $RESTORE_DB after service restart."
log "MySQL backup, restore, Flyway history, notification table, and application read checks completed."
