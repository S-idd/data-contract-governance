#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

: "${SERVICE_BASE_URL:=http://localhost:8080}"
: "${APP_SECURITY_ENABLED:=true}"
: "${APP_SECURITY_USERNAME:=admin}"
: "${APP_SECURITY_PASSWORD:=change-me}"

: "${TEST_POSTGRES_USERNAME:=}"
: "${TEST_POSTGRES_PASSWORD:=}"
: "${PGHOST:=localhost}"
: "${PGPORT:=5432}"
: "${PGDATABASE:=contracts}"
: "${PGSCHEMA:=dcg_dev}"
: "${PGUSER:=${TEST_POSTGRES_USERNAME}}"
: "${PGPASSWORD:=${TEST_POSTGRES_PASSWORD}}"

if [[ -z "$PGUSER" || -z "$PGPASSWORD" ]]; then
  echo "Set PGUSER/PGPASSWORD or TEST_POSTGRES_USERNAME/TEST_POSTGRES_PASSWORD before running this script." >&2
  exit 1
fi

payload='{"contractId":"orders.created","baseVersion":"v1","candidateVersion":"v2","mode":"BACKWARD","commitSha":"week7-audit","triggeredBy":"verify"}'

echo "Posting check run to ${SERVICE_BASE_URL}/checks"
auth_args=()
security_enabled="$(printf '%s' "${APP_SECURITY_ENABLED}" | tr '[:upper:]' '[:lower:]')"
case "$security_enabled" in
  true|1|yes)
    auth_args=(-u "${APP_SECURITY_USERNAME}:${APP_SECURITY_PASSWORD}")
    ;;
esac

response="$(curl -s -w "\n%{http_code}\n" "${auth_args[@]}" \
  -H "Content-Type: application/json" \
  -d "$payload" \
  "${SERVICE_BASE_URL}/checks")"

http_code="$(echo "$response" | tail -n 1)"
body="$(echo "$response" | sed '$d')"

echo "Response (${http_code}): ${body}"
if [[ "$http_code" != "202" && "$http_code" != "200" ]]; then
  echo "ERROR: Expected HTTP 202 from /checks."
  exit 1
fi

run_id="$(echo "$body" | sed -n 's/.*"runId"[[:space:]]*:[[:space:]]*"\\([^"]*\\)".*/\\1/p')"
if [[ -n "$run_id" ]]; then
  echo "Run ID: ${run_id}"
fi

echo "Checking audit logs in ${PGSCHEMA}.audit_logs"
export PGPASSWORD

count="$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -t -A \
  -c "select count(*) from ${PGSCHEMA}.audit_logs;")"

echo "Audit log count: ${count}"
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
  -c "select action, status, actor, actor_roles, resource_id, created_at from ${PGSCHEMA}.audit_logs order by created_at desc limit 5;"

if [[ "$count" -eq 0 ]]; then
  echo "ERROR: audit_logs is empty."
  exit 2
fi
