#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd -P)/lib.sh"
[[ -f "$BUNDLE_ROOT/docker/.env" ]] || { printf 'No generated database environment exists.\n'; exit 0; }
compose --env-file "$BUNDLE_ROOT/docker/.env" -f "$BUNDLE_ROOT/docker/compose.yaml" down
printf 'Database containers stopped. Named volumes were preserved.\n'
