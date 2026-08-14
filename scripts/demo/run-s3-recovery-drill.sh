#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SERVICE_JAR="${DCG_S3_RECOVERY_SERVICE_JAR:-$ROOT_DIR/contract-service/target/contract-service-0.1.0-SNAPSHOT.jar}"
MINIO_IMAGE="${DCG_S3_RECOVERY_IMAGE:-minio/minio:RELEASE.2025-04-22T22-12-26Z}"
S3_PORT="${DCG_S3_RECOVERY_PORT:-19000}"
SERVICE_PORT="${DCG_S3_RECOVERY_SERVICE_PORT:-18083}"
MINIO_ACCESS_KEY="${DCG_S3_RECOVERY_ACCESS_KEY:-dcg-recovery-user}"
MINIO_SECRET_KEY="${DCG_S3_RECOVERY_SECRET_KEY:-dcg-recovery-password}"
APP_USERNAME="${DCG_S3_RECOVERY_USERNAME:-recovery-demo}"
APP_PASSWORD="${DCG_S3_RECOVERY_PASSWORD:-recovery-demo-pass}"
KEEP_WORK_DIR="${DCG_S3_RECOVERY_KEEP_WORK_DIR:-false}"

STAMP="$(date +%Y%m%d%H%M%S)"
SUFFIX="${STAMP}_${RANDOM}"
MINIO_CONTAINER="dcg-s3-recovery-${SUFFIX}"
BUCKET="dcg-artifact-recovery-${STAMP}-${RANDOM}"
CONTRACT_ID="recovery.s3.${STAMP}.${RANDOM}"
SERVICE_URL="http://127.0.0.1:${SERVICE_PORT}"
S3_ENDPOINT="http://127.0.0.1:${S3_PORT}"
WORK_DIR="${TMPDIR:-/tmp}/dcg-s3-recovery-${SUFFIX}"
CACHE_ROOT="$WORK_DIR/contracts-cache"
CHECKS_DB_PATH="$WORK_DIR/checks.db"
SCHEMA_FILE="$WORK_DIR/schema.json"
CHECKSUM_FILE="$WORK_DIR/schema.sha256"

SERVICE_PID=""
SERVICE_LOG=""

usage() {
  cat <<'EOF'
Usage:
  scripts/demo/run-s3-recovery-drill.sh

Runs an isolated S3 artifact recovery drill using MinIO in Docker. The drill
creates a versioned bucket, starts contract-service with S3 fallback disabled,
creates a contract, confirms expected object keys, proves a deleted S3 schema
does not come from the local cache, restores the schema and checksum from known
object versions, and reads the contract again through the API.

Required commands: docker, java, curl, aws, lsof

Optional environment variables:
  DCG_S3_RECOVERY_PORT              MinIO S3 host port (default: 19000)
  DCG_S3_RECOVERY_SERVICE_PORT      Contract-service port (default: 18083)
  DCG_S3_RECOVERY_IMAGE             MinIO image tag
  DCG_S3_RECOVERY_SERVICE_JAR       Built contract-service jar path
  DCG_S3_RECOVERY_ACCESS_KEY        Temporary MinIO access key
  DCG_S3_RECOVERY_SECRET_KEY        Temporary MinIO secret key
  DCG_S3_RECOVERY_USERNAME          Local Basic-auth username
  DCG_S3_RECOVERY_PASSWORD          Local Basic-auth password
  DCG_S3_RECOVERY_KEEP_WORK_DIR     Set true to retain logs and restored files
EOF
}

log() {
  printf '[dcg-s3-recovery] %s\n' "$*"
}

is_true() {
  case "$1" in
    true|TRUE|True|1|yes|YES|Yes) return 0 ;;
    *) return 1 ;;
  esac
}

show_service_log() {
  if [[ -n "$SERVICE_LOG" && -f "$SERVICE_LOG" ]]; then
    printf '\nRecent contract-service log output:\n' >&2
    tail -n 80 "$SERVICE_LOG" >&2 || true
  fi
}

show_minio_log() {
  if docker container inspect "$MINIO_CONTAINER" >/dev/null 2>&1; then
    printf '\nRecent MinIO container log output:\n' >&2
    docker logs --tail 80 "$MINIO_CONTAINER" >&2 || true
  fi
}

die() {
  printf '[dcg-s3-recovery] ERROR: %s\n' "$*" >&2
  show_service_log
  show_minio_log
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Missing required command: $1"
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

  if docker container inspect "$MINIO_CONTAINER" >/dev/null 2>&1; then
    docker rm -f "$MINIO_CONTAINER" >/dev/null || true
  fi
  if [[ "$exit_code" -ne 0 ]] || is_true "$KEEP_WORK_DIR"; then
    log "Retained drill work directory: $WORK_DIR"
  else
    rm -rf "$WORK_DIR"
  fi
  return "$exit_code"
}

aws_s3() {
  AWS_ACCESS_KEY_ID="$MINIO_ACCESS_KEY" \
  AWS_SECRET_ACCESS_KEY="$MINIO_SECRET_KEY" \
  AWS_DEFAULT_REGION="us-east-1" \
  aws --endpoint-url "$S3_ENDPOINT" "$@"
}

wait_for_minio() {
  for _ in $(seq 1 60); do
    if curl -fsS "$S3_ENDPOINT/minio/health/live" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  die "MinIO did not become healthy at $S3_ENDPOINT within 60 seconds."
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

start_service() {
  SERVICE_LOG="$WORK_DIR/contract-service.log"
  SPRING_PROFILES_ACTIVE=local \
  SERVER_PORT="$SERVICE_PORT" \
  CHECKS_DB_PATH="$CHECKS_DB_PATH" \
  CONTRACTS_ROOT="$CACHE_ROOT" \
  CONTRACTS_ARTIFACT_BACKEND=s3 \
  CONTRACTS_ARTIFACT_S3_BUCKET="$BUCKET" \
  CONTRACTS_ARTIFACT_S3_REGION=us-east-1 \
  CONTRACTS_ARTIFACT_S3_ENDPOINT="$S3_ENDPOINT" \
  CONTRACTS_ARTIFACT_S3_PATH_STYLE=true \
  CONTRACTS_ARTIFACT_S3_ACCESS_KEY="$MINIO_ACCESS_KEY" \
  CONTRACTS_ARTIFACT_S3_SECRET_KEY="$MINIO_SECRET_KEY" \
  CONTRACTS_ARTIFACT_S3_FALLBACK_ENABLED=false \
  CONTRACTS_ARTIFACT_S3_LOCAL_CACHE_ROOT="$CACHE_ROOT" \
  CONTRACTS_ARTIFACT_S3_SERVER_SIDE_ENCRYPTION= \
  APP_SECURITY_ENABLED=true \
  APP_SECURITY_USERNAME="$APP_USERNAME" \
  APP_SECURITY_PASSWORD="$APP_PASSWORD" \
  java -jar "$SERVICE_JAR" >"$SERVICE_LOG" 2>&1 &
  SERVICE_PID=$!
  wait_for_service
}

create_contract() {
  curl -fsS -u "${APP_USERNAME}:${APP_PASSWORD}" \
    -X POST "$SERVICE_URL/contracts" \
    -H 'Content-Type: application/json' \
    -d "{
      \"contractId\": \"$CONTRACT_ID\",
      \"ownerTeam\": \"platform\",
      \"domain\": \"recovery\",
      \"compatibilityMode\": \"BACKWARD\",
      \"policyPack\": \"baseline\",
      \"initialVersion\": \"v1\",
      \"schema\": {
        \"type\": \"object\",
        \"properties\": {
          \"artifactId\": { \"type\": \"string\" }
        },
        \"required\": [\"artifactId\"]
      }
    }" >/dev/null
}

assert_object() {
  local key="$1"
  aws_s3 s3api head-object --bucket "$BUCKET" --key "$key" >/dev/null \
    || die "Expected S3 object is missing: $key"
}

http_status() {
  local path="$1"
  curl -sS -o "$WORK_DIR/http-response.json" -w '%{http_code}' \
    -u "${APP_USERNAME}:${APP_PASSWORD}" "$SERVICE_URL$path"
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
require_command aws
require_command lsof
docker info >/dev/null 2>&1 || die "Docker daemon is not reachable. Start Docker Desktop and retry."

[[ -f "$SERVICE_JAR" ]] || die "Service jar not found: $SERVICE_JAR. Build it with ./mvnw -pl contract-service -am package"
if lsof -tiTCP:"$S3_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  die "Port $S3_PORT is already in use. Set DCG_S3_RECOVERY_PORT to an unused port."
fi
if lsof -tiTCP:"$SERVICE_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  die "Port $SERVICE_PORT is already in use. Set DCG_S3_RECOVERY_SERVICE_PORT to an unused port."
fi

mkdir -p "$WORK_DIR"
SCHEMA_KEY="contracts/$CONTRACT_ID/versions/v1/schema.json"
CHECKSUM_KEY="contracts/$CONTRACT_ID/versions/v1/schema.sha256"
METADATA_KEY="contracts/$CONTRACT_ID/metadata.yaml"

log "Starting isolated MinIO container $MINIO_CONTAINER."
docker run --detach --rm \
  --name "$MINIO_CONTAINER" \
  --publish "127.0.0.1:${S3_PORT}:9000" \
  --env "MINIO_ROOT_USER=$MINIO_ACCESS_KEY" \
  --env "MINIO_ROOT_PASSWORD=$MINIO_SECRET_KEY" \
  "$MINIO_IMAGE" server /data >/dev/null
wait_for_minio

aws_s3 s3api create-bucket --bucket "$BUCKET" >/dev/null
aws_s3 s3api put-bucket-versioning \
  --bucket "$BUCKET" \
  --versioning-configuration Status=Enabled

log "Starting contract-service with S3 fallback disabled."
start_service
create_contract

assert_object "$METADATA_KEY"
assert_object "$SCHEMA_KEY"
assert_object "$CHECKSUM_KEY"
SCHEMA_VERSION_ID="$(aws_s3 s3api head-object --bucket "$BUCKET" --key "$SCHEMA_KEY" --query VersionId --output text)"
CHECKSUM_VERSION_ID="$(aws_s3 s3api head-object --bucket "$BUCKET" --key "$CHECKSUM_KEY" --query VersionId --output text)"
[[ "$SCHEMA_VERSION_ID" != "None" && "$CHECKSUM_VERSION_ID" != "None" ]] \
  || die "Bucket versioning did not return schema and checksum version IDs."

[[ "$(http_status "/contracts/$CONTRACT_ID/versions/v1")" == "200" ]] \
  || die "Fresh S3-backed contract version is not readable."

log "Deleting the current schema object to prove fallback-disabled behavior."
aws_s3 s3api delete-object --bucket "$BUCKET" --key "$SCHEMA_KEY" >/dev/null
MISSING_STATUS="$(http_status "/contracts/$CONTRACT_ID/versions/v1")"
[[ "$MISSING_STATUS" == "404" ]] \
  || die "Expected 404 for deleted S3 schema with fallback disabled, received HTTP $MISSING_STATUS."

log "Restoring schema and checksum from their known-good S3 object versions."
aws_s3 s3api get-object \
  --bucket "$BUCKET" --key "$SCHEMA_KEY" --version-id "$SCHEMA_VERSION_ID" \
  "$SCHEMA_FILE" >/dev/null
aws_s3 s3api get-object \
  --bucket "$BUCKET" --key "$CHECKSUM_KEY" --version-id "$CHECKSUM_VERSION_ID" \
  "$CHECKSUM_FILE" >/dev/null
aws_s3 s3api put-object --bucket "$BUCKET" --key "$SCHEMA_KEY" --body "$SCHEMA_FILE" >/dev/null
aws_s3 s3api put-object --bucket "$BUCKET" --key "$CHECKSUM_KEY" --body "$CHECKSUM_FILE" >/dev/null

RESTORED_STATUS="$(http_status "/contracts/$CONTRACT_ID/versions/v1")"
[[ "$RESTORED_STATUS" == "200" ]] \
  || die "Restored S3 schema is not readable, received HTTP $RESTORED_STATUS."
grep -q '"artifactId"' "$WORK_DIR/http-response.json" \
  || die "Restored API response does not contain the expected schema."

log "PASS: fallback-disabled missing-object check returned 404 and the restored schema is readable."
log "S3 versioning, object-pair recovery, and application read checks completed."
