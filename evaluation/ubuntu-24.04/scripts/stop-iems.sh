#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd -P)/lib.sh"
pid_file="$STATE_ROOT/iems/pid"
[[ -f "$pid_file" ]] || { printf 'IEMS is not tracked as running.\n'; exit 0; }
pid=$(cat "$pid_file")
if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
  args=$(ps -p "$pid" -o args= 2>/dev/null || true)
  [[ "$args" == *"$BUNDLE_ROOT/iems/iems.jar"* ]] || die 'PID identity does not match bundled IEMS; no signal was sent.'
  kill -TERM "$pid"
  for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  if kill -0 "$pid" 2>/dev/null; then
    die "IEMS did not stop within 30 seconds; preserving $pid_file. No forced kill sent."
  fi
fi
rm -f "$pid_file" "$STATE_ROOT/iems/backend"
printf 'IEMS stopped.\n'
