#!/usr/bin/env bash
set -euo pipefail

case_name=${1:-}
if [[ -z "$case_name" ]]; then
  echo "Usage: $0 <http-ingress-path|pod-failure-policy-rule>" >&2
  exit 2
fi

script_dir=$(cd "$(dirname "$0")" && pwd)
project_root=$(cd "$script_dir/../.." && pwd)
model_root=$(cd "$project_root/.." && pwd)
fixture_manifest="$model_root/data/external/kubernetes-openapi-v1/scored-accepted-v1/kubernetes-accepted-external-manifest-v3.json"
cli_jar=${DCG_DEMO_CLI_JAR:-"$project_root/contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar"}
endpoint=${DCG_DEMO_ENDPOINT:-"http://127.0.0.1:8081/demo/compare"}

case "$case_name" in
  http-ingress-path)
    record_id='kubernetes.prescreen.v1.21.0-to-v1.22.0.io.k8s.api.networking.v1.HTTPIngressPath'
    ;;
  pod-failure-policy-rule)
    record_id='kubernetes.prescreen.v1.28.0-to-v1.29.0.io.k8s.api.batch.v1.PodFailurePolicyRule'
    ;;
  *)
    echo "Unknown demo case: $case_name" >&2
    exit 2
    ;;
esac

if [[ ! -f "$cli_jar" ]]; then
  echo "CLI jar not found: $cli_jar. Build it with: mvn -pl contract-cli -am package -DskipTests" >&2
  exit 2
fi

demo_workspace=$(mktemp -d "${TMPDIR:-/tmp}/dcg-sync-demo.XXXXXX")
trap 'rm -rf "$demo_workspace"' EXIT

jq -e --arg id "$record_id" \
  '.transitions[] | select(.record_id == $id) | .base_schema' \
  "$fixture_manifest" > "$demo_workspace/base.json"
jq -e --arg id "$record_id" \
  '.transitions[] | select(.record_id == $id) | .candidate_schema' \
  "$fixture_manifest" > "$demo_workspace/candidate.json"

echo "Demo case: $case_name"
echo "Source record: $record_id"
java -jar "$cli_jar" demo-compare \
  --endpoint "$endpoint" \
  --base "$demo_workspace/base.json" \
  --candidate "$demo_workspace/candidate.json" \
  --policy-pack baseline \
  --mode BACKWARD
