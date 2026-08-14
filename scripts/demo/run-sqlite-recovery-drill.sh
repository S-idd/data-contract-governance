#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SERVICE_JAR="${DCG_SQLITE_RECOVERY_SERVICE_JAR:-$ROOT_DIR/contract-service/target/contract-service-0.1.0-SNAPSHOT.jar}"
SERVICE_PORT="${DCG_SQLITE_RECOVERY_PORT:-18080}"
SERVICE_URL="http://127.0.0.1:${SERVICE_PORT}"
APP_USERNAME="${DCG_SQLITE_RECOVERY_USERNAME:-recovery-demo}"
APP_PASSWORD="${DCG_SQLITE_RECOVERY_PASSWORD:-recovery-demo-pass}"
WORK_DIR="${DCG_SQLITE_RECOVERY_WORK_DIR:-}"
KEEP_WORK_DIR="${DCG_SQLITE_RECOVERY_KEEP_WORK_DIR:-false}"

SERVICE_PID=""
DB_PATH=""
BACKUP_PATH=""
SERVICE_LOG=""
RUN_ID=""

usage() {
  cat <<'EOF'
Usage:
  scripts/demo/run-sqlite-recovery-drill.sh

Runs a non-destructive SQLite production-lite recovery drill in a temporary directory:
  1. starts contract-service with the sqlite-prod-lite profile,
  2. submits and completes a check run,
  3. takes a hot SQLite backup and validates it,
  4. simulates loss of the primary database,
  5. restores the backup, restarts the service, and reads the original run.

Required commands: java, curl, sqlite3, lsof

Optional environment variables:
  DCG_SQLITE_RECOVERY_PORT          Service port (default: 18080)
  DCG_SQLITE_RECOVERY_WORK_DIR      Directory for the temporary database and logs
  DCG_SQLITE_RECOVERY_KEEP_WORK_DIR Set to true to retain drill artifacts after success
  DCG_SQLITE_RECOVERY_SERVICE_JAR   Built contract-service jar path
  DCG_SQLITE_RECOVERY_USERNAME      Local Basic-auth username
  DCG_SQLITE_RECOVERY_PASSWORD      Local Basic-auth password
EOF
}

log() {
  printf '[dcg-sqlite-recovery] %s\n' "$*"
}

die() {
  printf '[dcg-sqlite-recovery] ERROR: %s\n' "$*" >&2
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
  stop_service
  if [[ -n "$WORK_DIR" ]] && [[ -d "$WORK_DIR" ]]; then
    if is_true "$KEEP_WORK_DIR"; then
      log "Retained drill artifacts: $WORK_DIR"
    else
      rm -rf "$WORK_DIR"
    fi
  fi
}

show_service_log() {
  if [[ -n "$SERVICE_LOG" ]] && [[ -f "$SERVICE_LOG" ]]; then
    printf '\nRecent contract-service log output:\n' >&2
    tail -n 80 "$SERVICE_LOG" >&2 || true
  fi
}

wait_for_health() {
  for _ in $(seq 1 60); do
    if curl -fsS "$SERVICE_URL/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  show_service_log
  die "Service did not become healthy at $SERVICE_URL within 60 seconds."
}

start_service() {
  local phase="$1"
  SERVICE_LOG="$WORK_DIR/contract-service-${phase}.log"

  SPRING_PROFILES_ACTIVE=sqlite-prod-lite \
  SERVER_PORT="$SERVICE_PORT" \
  CHECKS_DB_PATH="$DB_PATH" \
  CONTRACTS_ROOT="$ROOT_DIR/contracts" \
  APP_SECURITY_ENABLED=true \
  APP_SECURITY_USERNAME="$APP_USERNAME" \
  APP_SECURITY_PASSWORD="$APP_PASSWORD" \
  java -jar "$SERVICE_JAR" >"$SERVICE_LOG" 2>&1 &
  SERVICE_PID=$!
  wait_for_health
}

verify_integrity() {
  local database_path="$1"
  local result
  result="$(sqlite3 "$database_path" "PRAGMA integrity_check;")"
  [[ "$result" == "ok" ]] || die "Integrity check failed for $database_path: $result"
}

submit_passing_check() {
  local response
  response="$(curl -fsS -u "${APP_USERNAME}:${APP_PASSWORD}" \
    -H "Content-Type: application/json" \
    -d '{"contractId":"orders.created","baseVersion":"v1","candidateVersion":"v2","mode":"BACKWARD","commitSha":"sqlite-recovery-drill","triggeredBy":"recovery-drill"}' \
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

verify_restored_run() {
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

require_command java
require_command curl
require_command sqlite3
require_command lsof

[[ -f "$SERVICE_JAR" ]] || die "Service jar not found: $SERVICE_JAR. Build it with ./mvnw -pl contract-service -am package"
if lsof -tiTCP:"$SERVICE_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  die "Port $SERVICE_PORT is already in use. Set DCG_SQLITE_RECOVERY_PORT to an unused port."
fi

if [[ -z "$WORK_DIR" ]]; then
  WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/dcg-sqlite-recovery-XXXXXX")"
else
  mkdir -p "$WORK_DIR"
fi
DB_PATH="$WORK_DIR/checks.db"
BACKUP_PATH="$WORK_DIR/checks-backup.db"

log "Starting SQLite production-lite service on $SERVICE_URL."
start_service "before-restore"

log "Submitting a check run to persist recovery evidence."
submit_passing_check
wait_for_passing_run

journal_mode="$(sqlite3 "$DB_PATH" "PRAGMA journal_mode;")"
[[ "$journal_mode" == "wal" ]] || die "Expected WAL mode, found: $journal_mode"
verify_integrity "$DB_PATH"

log "Taking a consistent hot backup while the service is running."
sqlite3 "$DB_PATH" ".backup '$BACKUP_PATH'"
[[ -s "$BACKUP_PATH" ]] || die "SQLite backup was not created: $BACKUP_PATH"
verify_integrity "$BACKUP_PATH"
backup_run_count="$(sqlite3 "$BACKUP_PATH" "select count(*) from check_runs where run_id = '$RUN_ID';")"
[[ "$backup_run_count" == "1" ]] || die "Backup does not contain expected run $RUN_ID."

log "Stopping service before restoring the known-good backup."
stop_service

log "Simulating primary database loss in the temporary drill directory."
rm -f "$DB_PATH" "${DB_PATH}-wal" "${DB_PATH}-shm"
[[ ! -e "$DB_PATH" ]] || die "Could not remove temporary primary database."

log "Restoring the validated hot backup and restarting the service."
cp "$BACKUP_PATH" "$DB_PATH"
verify_integrity "$DB_PATH"
start_service "after-restore"
verify_restored_run

log "PASS: restored check $RUN_ID is readable after service restart."
log "SQLite production-lite backup, restore, integrity, and post-restore read checks completed."
