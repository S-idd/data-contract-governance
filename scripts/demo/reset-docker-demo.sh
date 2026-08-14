#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"
ENV_FILE="${DCG_COMPOSE_ENV_FILE:-$ROOT_DIR/.env}"

usage() {
  cat <<'EOF'
Usage:
  scripts/demo/reset-docker-demo.sh --yes

Stops and removes only the DCG Docker Compose stack, its PostgreSQL volume,
and its locally built service image. It does not remove unrelated containers,
images, volumes, or a locally installed PostgreSQL database.

Set DCG_COMPOSE_ENV_FILE to choose a compose environment file. When .env is
absent, the checked-in config/compose.env.example is used.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi
if [[ "${1:-}" != "--yes" || $# -ne 1 ]]; then
  usage >&2
  exit 2
fi

command -v docker >/dev/null 2>&1 || {
  echo "Missing required command: docker" >&2
  exit 1
}
docker info >/dev/null 2>&1 || {
  echo "Docker daemon is not reachable. Start Docker Desktop and retry." >&2
  exit 1
}

if [[ ! -f "$ENV_FILE" ]]; then
  ENV_FILE="$ROOT_DIR/config/compose.env.example"
fi

echo "Resetting DCG Docker resources using $ENV_FILE"
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
  down --volumes --rmi local --remove-orphans
echo "DCG Docker demo reset complete."
