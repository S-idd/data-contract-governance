#!/bin/bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd -P)/lib.sh"
require_macos
for cmd in java curl lsof ps shasum python3 file sysctl vm_stat memory_pressure; do
  command -v "$cmd" >/dev/null || die "Required command is missing: $cmd"
done
java_settings=$(java -XshowSettings:properties -version 2>&1) || die 'Java cannot start.'
printf '%s\n' "$java_settings" | grep -Eq 'version "21[."]' || die 'Install native ARM64 Java 21 or set JAVA_HOME.'
printf '%s\n' "$java_settings" | grep -Eq 'os.arch = (aarch64|arm64)$' || die 'Java must be native ARM64, not Intel under Rosetta.'
python3 -c 'import sys,platform; assert sys.version_info >= (3,10), "Python 3.10+ required"; assert platform.machine() == "arm64", "Native ARM64 Python required"'
[[ -x "$DCG_HOME/bin/dcgaimodel" ]] || die 'Rust executable is missing or not executable.'
file "$DCG_HOME/bin/dcgaimodel" | grep -q 'Mach-O 64-bit executable arm64' || die 'Expected Mach-O ARM64 Rust binary.'
python3 - "$BUNDLE_ROOT/bundle-info.json" <<'PY'
import json,sys
info=json.load(open(sys.argv[1]))
assert info['target'] == 'aarch64-apple-darwin' and info['scope'] == 'milestone-1'
PY
(cd "$BUNDLE_ROOT" && shasum -a 256 -c SHA256SUMS)
printf 'PASS: native Apple Silicon, Java 21, Python, tools and bundle checksums verified.\n'
