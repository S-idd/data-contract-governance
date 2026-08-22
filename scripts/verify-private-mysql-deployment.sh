#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOYMENT="$ROOT_DIR/deploy/kubernetes/mysql-private/deployment.yaml"
SERVICE="$ROOT_DIR/deploy/kubernetes/mysql-private/service.yaml"
NETWORK_POLICY="$ROOT_DIR/deploy/kubernetes/mysql-private/network-policy.yaml"

require() { rg -Fq "$2" "$1" || { echo "FAIL: $1 missing $2" >&2; exit 1; }; }
reject() { ! rg -Fq "$2" "$1" || { echo "FAIL: $1 must not contain $2" >&2; exit 1; }; }

require "$DEPLOYMENT" "replicas: 2"
require "$DEPLOYMENT" "maxUnavailable: 0"
require "$DEPLOYMENT" "maxSurge: 1"
require "$DEPLOYMENT" "secretRef:"
require "$DEPLOYMENT" "resources:"
require "$DEPLOYMENT" "readOnlyRootFilesystem: true"
require "$SERVICE" "type: ClusterIP"
reject "$SERVICE" "LoadBalancer"
reject "$SERVICE" "NodePort"
require "$NETWORK_POLICY" "port: 3306"
printf '[dcg-private-deploy] PASS: private service, secret references, canary settings, and resource limits verified.\n'
