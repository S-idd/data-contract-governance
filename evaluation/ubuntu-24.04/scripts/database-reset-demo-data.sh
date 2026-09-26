#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd -P)/lib.sh"
[[ "${1:-}" == --confirm-disposable-data ]] || die 'Usage: scripts/database-reset-demo-data.sh --confirm-disposable-data'
[[ -f "$BUNDLE_ROOT/docker/.env" ]] || die 'No generated database environment exists.'
compose --env-file "$BUNDLE_ROOT/docker/.env" -f "$BUNDLE_ROOT/docker/compose.yaml" down --volumes
printf 'Removed only this evaluation bundle\x27s disposable PostgreSQL/MySQL volumes.\n'
