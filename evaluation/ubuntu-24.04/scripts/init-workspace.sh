#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd -P)/lib.sh"
mkdir -p "$WORKSPACE/contracts" "$STATE_ROOT" "$WORKSPACE/evidence"
if [[ ! -f "$WORKSPACE/contracts/policy-packs.json" ]]; then
  cp "$BUNDLE_ROOT/examples/policy-packs.json" "$WORKSPACE/contracts/policy-packs.json"
fi
cat <<EOF
Workspace ready: $WORKSPACE
Write each contract in: $WORKSPACE/contracts/<contract-id>/
Required files: metadata.yaml, v1.json, candidate.json
Examples remain unchanged in: $BUNDLE_ROOT/examples/contracts/
EOF
