#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

: "${TEST_POSTGRES_JDBC_URL:=jdbc:postgresql://localhost:5432/contracts?currentSchema=dcg_dev}"
: "${TEST_POSTGRES_USERNAME:=}"
: "${TEST_POSTGRES_PASSWORD:=}"

if [[ -z "$TEST_POSTGRES_USERNAME" || -z "$TEST_POSTGRES_PASSWORD" ]]; then
  echo "Set TEST_POSTGRES_USERNAME and TEST_POSTGRES_PASSWORD before running this script." >&2
  exit 1
fi

cd "$ROOT_DIR"

TEST_POSTGRES_JDBC_URL="$TEST_POSTGRES_JDBC_URL" \
TEST_POSTGRES_USERNAME="$TEST_POSTGRES_USERNAME" \
TEST_POSTGRES_PASSWORD="$TEST_POSTGRES_PASSWORD" \
./mvnw -pl contract-core,contract-service,contract-cli -am test

echo "Logs: Postgres tables dcg_dev.check_run_logs + dcg_dev.audit_logs (or checks.db if SQLite). App logs print to console stdout."
