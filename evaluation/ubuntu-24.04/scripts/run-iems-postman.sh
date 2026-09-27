#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd -P)/lib.sh"
require_command jq
require_command npx
require_command python3
umask 077
backend_file="$STATE_ROOT/iems/backend"
[[ -s "$backend_file" ]] || die 'Start IEMS with the updated scripts/start-iems.sh first.'
running_store=$(cat "$backend_file")
store=${1:-$running_store}
[[ "$#" -le 1 && "$store" == "$running_store" ]] || die 'Requested backend differs from the running IEMS backend.'
case "$store" in sqlite|postgres|mysql) ;; *) die 'Usage: scripts/run-iems-postman.sh [sqlite|postgres|mysql]';; esac
password_file="$STATE_ROOT/iems/admin-password"
[[ -s "$password_file" ]] || die 'Start IEMS first with scripts/start-iems.sh.'
# db-demo disables the Kafka notification consumer. The collection consumes
# this fixture (marks it read, then deletes it), so prepare it on every run.
seed="$BUNDLE_ROOT/iems/postman/seed_notification.py"
if [[ "$store" == sqlite ]]; then
  python3 "$seed" "$WORKSPACE/iems-data/iems.db"
else
  python3 "$seed" --backend "$store" --compose-file "$BUNDLE_ROOT/docker/compose.yaml" --env-file "$BUNDLE_ROOT/docker/.env"
fi
mkdir -p "$WORKSPACE/evidence"
evidence=$(mktemp -d "$WORKSPACE/evidence/newman-$store-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")
# Bypass inherited proxies for local Newman requests; preserve registry proxy use.
export NO_PROXY="${NO_PROXY:+$NO_PROXY,}127.0.0.1,localhost,::1"
export no_proxy="${no_proxy:+$no_proxy,}127.0.0.1,localhost,::1"
environment="$evidence/iems-environment.json"
jq --arg password "$(cat "$password_file")" \
  '(.values[] | select(.key == "adminPassword").value) = $password' \
  "$BUNDLE_ROOT/iems/postman/iems-demo.postman_environment.json" > "$environment"
npx --yes newman@6.2.2 run "$BUNDLE_ROOT/iems/postman/iems-api-collection.json" \
  --environment "$environment" \
  --reporters cli,json \
  --reporter-json-export "$evidence/results.json"
printf 'Private Newman evidence: %s\n' "$evidence"
