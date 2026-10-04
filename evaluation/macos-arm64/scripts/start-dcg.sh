#!/bin/bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd -P)/lib.sh"
require_macos
export DCG_AI_ENABLED=${DCG_AI_ENABLED:-false}
exec /bin/bash "$DCG_HOME/bin/start"
