#!/bin/bash
set -euo pipefail
export LC_ALL=C
BUNDLE_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
DCG_HOME="$BUNDLE_ROOT/dcg"
export DCG_DATA_DIR="$BUNDLE_ROOT/workspace"
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
# Each JVM is capped; RSS also includes native memory and mapped files.
export JAVA_TOOL_OPTIONS=${JAVA_TOOL_OPTIONS:--Xms64m -Xmx512m -XX:MaxMetaspaceSize=192m}
export DCG_JAVA_STARTUP_TIMEOUT_SECONDS=${DCG_JAVA_STARTUP_TIMEOUT_SECONDS:-180}
export DCG_AI_STARTUP_TIMEOUT_SECONDS=${DCG_AI_STARTUP_TIMEOUT_SECONDS:-10}
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
require_macos() {
  [[ "$(uname -s)" == Darwin && "$(uname -m)" == arm64 ]] || die 'Native Apple Silicon macOS is required. Intel/Rosetta is not supported.'
}
