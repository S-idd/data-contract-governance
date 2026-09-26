#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd -P)/lib.sh"
export DCG_DATA_DIR="$WORKSPACE"
exec "$DCG_HOME/bin/stop"
