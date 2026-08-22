#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"
ENV_FILE="${DCG_S3_BETA_ENV_FILE:-$ROOT_DIR/.env.s3-beta}"
ENV_TEMPLATE="${DCG_S3_BETA_ENV_TEMPLATE:-$ROOT_DIR/config/compose.s3-beta.env.example}"

die() {
  echo "[dcg-s3-beta] ERROR: $*" >&2
  exit 1
}

environment_value() {
  local name="$1"
  printf '%s\n' "$COMPOSE_ENVIRONMENT" | awk -v name="$name" \
    'index($0, name "=") == 1 { print substr($0, length(name) + 2); exit }'
}

if [[ ! -f "$ENV_FILE" ]]; then
  [[ -f "$ENV_TEMPLATE" ]] || die "Environment template not found: $ENV_TEMPLATE"
  umask 077
  cp "$ENV_TEMPLATE" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  echo "Created $ENV_FILE from $ENV_TEMPLATE"
  echo "Set the bucket and least-privilege S3 credentials in $ENV_FILE, then run this command again."
  exit 0
fi

command -v docker >/dev/null 2>&1 || die "Missing required command: docker"
COMPOSE_ENVIRONMENT="$(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" config --environment)"

for required_setting in \
  DCG_DB_USERNAME \
  DCG_DB_PASSWORD \
  DCG_APP_USERNAME \
  DCG_APP_PASSWORD \
  CONTRACTS_ARTIFACT_S3_BUCKET \
  CONTRACTS_ARTIFACT_S3_REGION \
  CONTRACTS_ARTIFACT_S3_ACCESS_KEY \
  CONTRACTS_ARTIFACT_S3_SECRET_KEY; do
  value="$(environment_value "$required_setting")"
  [[ -n "$value" ]] || die "Required setting $required_setting is missing or blank in $ENV_FILE"
  [[ "$value" != replace-with-* ]] || die "Replace the placeholder for $required_setting in $ENV_FILE"
done

[[ "$(environment_value CONTRACTS_ARTIFACT_BACKEND)" == "s3" ]] \
  || die "CONTRACTS_ARTIFACT_BACKEND must be s3 in $ENV_FILE"
[[ "$(environment_value CONTRACTS_ARTIFACT_S3_FALLBACK_ENABLED)" == "false" ]] \
  || die "CONTRACTS_ARTIFACT_S3_FALLBACK_ENABLED must be false for the S3 beta"

DCG_COMPOSE_ENV_FILE="$ENV_FILE" \
DCG_COMPOSE_ENV_TEMPLATE="$ENV_TEMPLATE" \
DCG_COMPOSE_SUBMIT_SAMPLE_CHECK=false \
  bash "$ROOT_DIR/scripts/demo/run-compose-demo.sh"

cat <<EOF

S3 beta stack is ready. Create and verify contract artifacts with:
  scripts/aws/s3-artifact-demo.sh seed-contract --profile <profile> --region $(environment_value CONTRACTS_ARTIFACT_S3_REGION) --bucket $(environment_value CONTRACTS_ARTIFACT_S3_BUCKET)
  scripts/aws/s3-artifact-demo.sh verify --profile <profile> --region $(environment_value CONTRACTS_ARTIFACT_S3_REGION) --bucket $(environment_value CONTRACTS_ARTIFACT_S3_BUCKET)
EOF
