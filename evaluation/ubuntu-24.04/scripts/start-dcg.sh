#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd -P)/lib.sh"
"$BUNDLE_ROOT/scripts/init-workspace.sh" >/dev/null
export DCG_DATA_DIR="$WORKSPACE"
export DCG_AI_ENABLED=${DCG_AI_ENABLED:-false}
exec "$DCG_HOME/bin/start"
