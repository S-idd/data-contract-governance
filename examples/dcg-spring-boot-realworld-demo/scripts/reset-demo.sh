#!/usr/bin/env bash
set -euo pipefail

DEMO_CHECKS_DB_PATH="${DEMO_CHECKS_DB_PATH:-/tmp/dcg-v4-demo-checks.db}"

rm -f "${DEMO_CHECKS_DB_PATH}" "${DEMO_CHECKS_DB_PATH}-wal" "${DEMO_CHECKS_DB_PATH}-shm"
echo "Removed demo check history: ${DEMO_CHECKS_DB_PATH}"
echo "Restart contract-service before running the scenarios again."
