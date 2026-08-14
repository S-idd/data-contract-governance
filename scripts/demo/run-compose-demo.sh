#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"
ENV_FILE="${DCG_COMPOSE_ENV_FILE:-$ROOT_DIR/.env}"
COMPOSE_PULL_POLICY="${DCG_COMPOSE_PULL_POLICY:-missing}"
COMPOSE_BUILD_ENABLED="${DCG_COMPOSE_BUILD_ENABLED:-true}"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

print_debug_context() {
  echo
  echo "Compose status:"
  compose ps || true
  echo
  echo "Recent service logs:"
  compose logs --tail=120 contract-service postgres || true
}

wait_for_health() {
  local url="$1"
  local attempts="$2"
  local sleep_secs="$3"
  local i
  for i in $(seq 1 "$attempts"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep "$sleep_secs"
  done
  return 1
}

require_command docker
require_command curl

if ! docker info >/dev/null 2>&1; then
  echo "Docker daemon is not reachable. Start Docker Desktop and retry." >&2
  exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
  cp "$ROOT_DIR/config/compose.env.example" "$ENV_FILE"
  echo "Created $ENV_FILE from config/compose.env.example"
fi

# shellcheck disable=SC1090
set -a
source "$ENV_FILE"
set +a

APP_PORT="${DCG_SERVICE_PORT:-8080}"
APP_USER="${DCG_APP_USERNAME:-dcg-compose-admin}"
APP_PASSWORD="${DCG_APP_PASSWORD:-dcg-compose-demo-password}"
BASE_URL="http://localhost:${APP_PORT}"
HEALTH_URL="$BASE_URL/actuator/health"

echo "Starting Docker Compose stack (service + Postgres)..."
if [[ "$COMPOSE_BUILD_ENABLED" == "true" ]]; then
  compose up --build --pull "$COMPOSE_PULL_POLICY" -d
else
  compose up --pull "$COMPOSE_PULL_POLICY" -d
fi

echo "Waiting for service health at $HEALTH_URL ..."
if ! wait_for_health "$HEALTH_URL" 90 2; then
  echo "Service did not become healthy within 180 seconds." >&2
  print_debug_context
  exit 1
fi

echo "Submitting sample check run..."
CREATE_RESPONSE="$(
  curl -fsS -u "${APP_USER}:${APP_PASSWORD}" \
    -H "Content-Type: application/json" \
    -d '{
      "contractId": "orders.created",
      "baseVersion": "v1",
      "candidateVersion": "v2",
      "mode": "BACKWARD",
      "commitSha": "compose-demo",
      "triggeredBy": "compose-quickstart"
    }' \
    "$BASE_URL/checks"
)"

RUN_ID="$(echo "$CREATE_RESPONSE" | sed -n 's/.*"runId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)"

echo
echo "Compose demo is ready."
echo "Health: $HEALTH_URL"
echo "UI:     $BASE_URL/ui"
echo "API:    $BASE_URL/swagger-ui/index.html"
if [[ -n "$RUN_ID" ]]; then
  echo "Run:    $BASE_URL/ui/checks/$RUN_ID"
fi

echo
echo "To stop:"
echo "  docker compose --env-file $ENV_FILE -f $COMPOSE_FILE down"
echo "To reset DB volume as well:"
echo "  docker compose --env-file $ENV_FILE -f $COMPOSE_FILE down -v"
echo
echo "Bandwidth controls (optional):"
echo "  DCG_COMPOSE_PULL_POLICY=never    # never pull newer images"
echo "  DCG_COMPOSE_BUILD_ENABLED=false  # skip image build during startup"
