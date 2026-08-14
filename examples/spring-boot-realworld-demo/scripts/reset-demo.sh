#!/usr/bin/env bash
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="$DEMO_DIR/.runtime"

if [[ -d "$RUNTIME_DIR" ]]; then
  rm -rf "$RUNTIME_DIR"
  echo "Removed demo runtime state at $RUNTIME_DIR."
else
  echo "Demo runtime state is already clean."
fi

echo "Stop any running demo processes, then restart the receiver and contract service before seeding again."
