#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd -P)/lib.sh"
require_command docker
env_file="$BUNDLE_ROOT/docker/.env"
if [[ ! -f "$env_file" ]]; then
  umask 077
  pg_password=$(openssl rand -hex 24)
  mysql_password=$(openssl rand -hex 24)
  mysql_root_password=$(openssl rand -hex 24)
  cat > "$env_file" <<EOF
POSTGRES_USER=dcg_demo
POSTGRES_PASSWORD=$pg_password
POSTGRES_PORT=54329
MYSQL_USER=dcg_demo
MYSQL_PASSWORD=$mysql_password
MYSQL_ROOT_PASSWORD=$mysql_root_password
MYSQL_PORT=33069
EOF
fi
compose --env-file "$env_file" -f "$BUNDLE_ROOT/docker/compose.yaml" up -d --wait
printf 'PostgreSQL and MySQL are healthy. Credentials are private in docker/.env.\n'
