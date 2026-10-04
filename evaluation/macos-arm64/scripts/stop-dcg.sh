#!/bin/bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd -P)/lib.sh"
require_macos
exec /bin/bash "$DCG_HOME/bin/stop"
