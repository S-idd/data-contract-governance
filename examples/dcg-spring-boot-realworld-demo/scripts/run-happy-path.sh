#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEMO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${DEMO_ROOT}/../.." && pwd)"
CONTRACT_DIR="${DEMO_ROOT}/contracts/orders.created"
CLI_JAR="${DCG_CLI_JAR:-${REPO_ROOT}/contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar}"
SERVICE_URL="${DCG_SERVICE_URL:-http://127.0.0.1:8080}"
AUTH="${DCG_AUTH:-demo:demo-pass}"

if [[ ! -f "${CLI_JAR}" ]]; then
  echo "CLI jar not found: ${CLI_JAR}" >&2
  echo "Build it first: ./mvnw -pl contract-cli -am package -DskipTests" >&2
  exit 1
fi

curl -fsS "${SERVICE_URL}/actuator/health" >/dev/null

echo "1/3: Running the local CLI compatibility check (v1 -> v2)."
java -jar "${CLI_JAR}" check-compat \
  --base "${CONTRACT_DIR}/v1.json" \
  --candidate "${CONTRACT_DIR}/v2.json" \
  --mode BACKWARD

echo "2/3: Submitting the same compatible change through the REST API."
response="$(curl -fsS -u "${AUTH}" \
  -H "Content-Type: application/json" \
  -d '{"contractId":"orders.created","baseVersion":"v1","candidateVersion":"v2","mode":"BACKWARD","commitSha":"demo-happy-path","triggeredBy":"spring-boot-demo"}' \
  "${SERVICE_URL}/checks")"
run_id="$(printf '%s' "${response}" | sed -nE 's/.*"runId"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p')"
if [[ -z "${run_id}" ]]; then
  echo "Could not read runId from: ${response}" >&2
  exit 1
fi

echo "3/3: Waiting for a PASS result."
for _ in $(seq 1 20); do
  run="$(curl -fsS -u "${AUTH}" "${SERVICE_URL}/checks/${run_id}")"
  if printf '%s' "${run}" | grep -q '"status":"PASS"'; then
    echo "PASS: ${run_id}"
    echo "Open: ${SERVICE_URL}/ui/checks/${run_id}"
    exit 0
  fi
  sleep 1
done

echo "Timed out waiting for ${run_id}. Inspect ${SERVICE_URL}/checks/${run_id}" >&2
exit 1
