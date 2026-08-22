#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker-compose.mysql.yml"
ENV_FILE="${DCG_MYSQL_DEMO_ENV_FILE:-$ROOT_DIR/.env.mysql-demo}"
ENV_TEMPLATE="${DCG_MYSQL_DEMO_ENV_TEMPLATE:-$ROOT_DIR/config/compose.mysql-demo.env.example}"
PROJECT_NAME="dcg-mysql-demo"

die() { echo "[dcg-mysql-demo] ERROR: $*" >&2; exit 1; }
compose() { docker compose -p "$PROJECT_NAME" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"; }
environment_value() {
  local name="$1"
  printf '%s\n' "$COMPOSE_ENVIRONMENT" | awk -v name="$name" \
    'index($0, name "=") == 1 { print substr($0, length(name) + 2); exit }'
}

command -v docker >/dev/null 2>&1 || die "Missing required command: docker"
command -v curl >/dev/null 2>&1 || die "Missing required command: curl"
docker info >/dev/null 2>&1 || die "Docker daemon is not reachable. Start Docker Desktop and retry."

if [[ ! -f "$ENV_FILE" ]]; then
  [[ -f "$ENV_TEMPLATE" ]] || die "Environment template not found: $ENV_TEMPLATE"
  umask 077
  cp "$ENV_TEMPLATE" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  echo "Created $ENV_FILE from $ENV_TEMPLATE"
  echo "Update its DCG_DB_* and DCG_APP_* values before using outside a local demo."
fi

COMPOSE_ENVIRONMENT="$(compose config --environment)"
for required_setting in DCG_DB_USERNAME DCG_DB_PASSWORD DCG_MYSQL_ROOT_PASSWORD DCG_APP_USERNAME DCG_APP_PASSWORD; do
  value="$(environment_value "$required_setting")"
  [[ -n "$value" ]] || die "Required setting $required_setting is missing or blank in $ENV_FILE"
  [[ "$value" != replace-with-* ]] || die "Replace the placeholder for $required_setting in $ENV_FILE"
done

APP_PORT="$(environment_value DCG_SERVICE_PORT)"; APP_PORT="${APP_PORT:-8080}"
APP_USER="$(environment_value DCG_APP_USERNAME)"
APP_PASSWORD="$(environment_value DCG_APP_PASSWORD)"
BASE_URL="http://localhost:${APP_PORT}"

echo "Starting main DCG with MySQL metadata in Docker..."
compose up --build -d

for _ in $(seq 1 120); do
  if curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 2
done
curl -fsS "$BASE_URL/actuator/health" >/dev/null \
  || { compose logs --tail=120 contract-service mysql; die "Service did not become healthy at $BASE_URL"; }

CREATE_RESPONSE="$(curl -fsS -u "${APP_USER}:${APP_PASSWORD}" -H "Content-Type: application/json" \
  -d '{"contractId":"orders.created","baseVersion":"v1","candidateVersion":"v2","mode":"BACKWARD","commitSha":"mysql-compose-demo","triggeredBy":"mysql-compose-demo"}' \
  "$BASE_URL/checks")"
RUN_ID="$(echo "$CREATE_RESPONSE" | sed -n 's/.*"runId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)"

echo "MySQL DCG demo is ready."
echo "Health: $BASE_URL/actuator/health"
echo "UI:     $BASE_URL/ui"
echo "Run:    $BASE_URL/ui/checks/$RUN_ID"
echo "Stop:   docker compose -p $PROJECT_NAME --env-file $ENV_FILE -f $COMPOSE_FILE down"
