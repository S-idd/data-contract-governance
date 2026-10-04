#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd -P)/lib.sh"
require_command jq
require_command npx
require_command python3
password_file="$STATE_ROOT/iems/admin-password"
[[ -s "$password_file" ]] || die 'Start IEMS first with scripts/start-iems.sh.'
# db-demo disables the Kafka notification consumer. The collection consumes
# this fixture (marks it read, then deletes it), so prepare it on every run.
python3 "$BUNDLE_ROOT/iems/postman/seed_notification.py" "$WORKSPACE/iems-data/iems.db"
evidence="$WORKSPACE/evidence/newman-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$evidence"
environment="$evidence/iems-environment.json"
jq --arg password "$(cat "$password_file")" \
  '(.values[] | select(.key == "adminPassword").value) = $password' \
  "$BUNDLE_ROOT/iems/postman/iems-demo.postman_environment.json" > "$environment"
npx --yes newman@6.2.2 run "$BUNDLE_ROOT/iems/postman/iems-api-collection.json" \
  --environment "$environment" \
  --reporters cli,json \
  --reporter-json-export "$evidence/results.json"
printf 'Private Newman evidence: %s\n' "$evidence"
