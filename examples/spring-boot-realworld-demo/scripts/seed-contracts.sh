#!/usr/bin/env bash
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE_URL="${DCG_DEMO_SERVICE_BASE_URL:-http://localhost:8080}"
CONTRACT_ID="orders.created"
CONTRACT_DIR="$DEMO_DIR/contracts/$CONTRACT_ID"

if ! command -v curl >/dev/null 2>&1; then
  echo "Missing required command: curl" >&2
  exit 1
fi

if ! curl -fsS "$SERVICE_URL/api/status" >/dev/null 2>&1; then
  echo "Contract service is not ready at $SERVICE_URL. Start scripts/run-dcg-service.sh first." >&2
  exit 1
fi

if curl -fsS "$SERVICE_URL/contracts/$CONTRACT_ID" >/dev/null 2>&1; then
  echo "$CONTRACT_ID is already seeded. Run scripts/reset-demo.sh before a clean rehearsal." >&2
  exit 1
fi

schema() {
  tr -d '\n' < "$CONTRACT_DIR/$1.json"
}

echo "Registering $CONTRACT_ID v1, v2, and v3..."
curl -fsS -X POST "$SERVICE_URL/contracts" \
  -H "Content-Type: application/json" \
  --data "{\"contractId\":\"$CONTRACT_ID\",\"ownerTeam\":\"fulfillment\",\"domain\":\"commerce\",\"compatibilityMode\":\"BACKWARD\",\"initialVersion\":\"v1\",\"schema\":$(schema v1)}" \
  >/dev/null

for version in v2 v3; do
  curl -fsS -X POST "$SERVICE_URL/contracts/$CONTRACT_ID/versions" \
    -H "Content-Type: application/json" \
    --data "{\"version\":\"$version\",\"schema\":$(schema "$version")}" \
    >/dev/null
done

echo "Seed complete: $CONTRACT_ID v1 -> v2 (compatible), v2 -> v3 (breaking)."
