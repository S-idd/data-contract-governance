#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" != --install ]]; then
  cat <<'EOF'
This script installs Ubuntu 24.04 prerequisites only when explicitly invoked with --install:
  ./scripts/bootstrap-ubuntu.sh --install

It uses apt and does not install Docker Engine. Install Docker Desktop with WSL integration,
or follow Docker's official Ubuntu installation guide, then rerun verify-install.sh.
EOF
  exit 0
fi
sudo apt-get update
sudo apt-get install -y ca-certificates curl jq lsof openssl python3 tar unzip
printf 'Base Ubuntu packages installed. Install Java 21 and Docker/Compose if verify-install.sh reports them missing.\n'
