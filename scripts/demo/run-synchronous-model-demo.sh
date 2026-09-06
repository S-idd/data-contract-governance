#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)

echo "=== CASE 1: AGREEMENT — Kubernetes HTTPIngressPath ==="
echo "Expected: rule engine BREAKING; all three model seeds BREAKING; AGREE"
"$script_dir/run-sync-model-comparison.sh" http-ingress-path

echo
echo "=== CASE 2: DISAGREEMENT — Kubernetes PodFailurePolicyRule ==="
echo "Expected: rule engine SAFE; all three model seeds BREAKING; DISAGREE"
"$script_dir/run-sync-model-comparison.sh" pod-failure-policy-rule

echo
echo "=== DEMO COMPLETE ==="
