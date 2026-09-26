#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd -P)/lib.sh"
[[ "$(uname -s)" == Linux ]] || die 'This evaluation bundle targets Linux.'
[[ "$(uname -m)" == x86_64 ]] || die 'This bundle contains Linux x86-64 binaries.'
require_java21
for cmd in curl jq lsof openssl python3 sha256sum; do require_command "$cmd"; done
[[ -x "$DCG_HOME/bin/dcg" ]] || die 'DCG CLI is missing.'
[[ -f "$BUNDLE_ROOT/iems/iems.jar" ]] || die 'IEMS executable JAR is missing.'
(cd "$BUNDLE_ROOT" && sha256sum --check SHA256SUMS)
printf 'PASS: Ubuntu tools, Java 21, package binaries, IEMS JAR, and bundle checksums verified.\n'
