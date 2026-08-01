#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEMO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${DEMO_ROOT}/../.." && pwd)"
CONTRACT_DIR="${DEMO_ROOT}/contracts/orders.created"
CLI_JAR="${DCG_CLI_JAR:-${REPO_ROOT}/contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar}"
SERVICE_URL="${DCG_SERVICE_URL:-http://127.0.0.1:8080}"
DEMO_URL="${DEMO_URL:-http://127.0.0.1:8090}"
AUTH="${DCG_AUTH:-demo:demo-pass}"

if [[ ! -f "${CLI_JAR}" ]]; then
  echo "CLI jar not found: ${CLI_JAR}" >&2
  echo "Build it first: ./mvnw -pl contract-cli -am package -DskipTests" >&2
  exit 1
fi

curl -fsS "${SERVICE_URL}/actuator/health" >/dev/null
curl -fsS "${DEMO_URL}/demo/webhooks" >/dev/null

echo "1/4: Running the intentionally breaking CLI check (v2 -> v3)."
set +e
java -jar "${CLI_JAR}" check-compat \
  --base "${CONTRACT_DIR}/v2.json" \
  --candidate "${CONTRACT_DIR}/v3.json" \
  --mode BACKWARD
cli_exit=$?
set -e
if [[ ${cli_exit} -eq 0 ]]; then
  echo "Expected the breaking CLI check to fail, but it passed." >&2
  exit 1
fi
echo "CLI correctly rejected the breaking change."

echo "2/4: Submitting the breaking change through the REST API."
response="$(curl -fsS -u "${AUTH}" \
  -H "Content-Type: application/json" \
  -d '{"contractId":"orders.created","baseVersion":"v2","candidateVersion":"v3","mode":"BACKWARD","commitSha":"demo-breaking-path","triggeredBy":"spring-boot-demo"}' \
  "${SERVICE_URL}/checks")"
run_id="$(printf '%s' "${response}" | sed -nE 's/.*"runId"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p')"
if [[ -z "${run_id}" ]]; then
  echo "Could not read runId from: ${response}" >&2
  exit 1
fi

echo "3/4: Waiting for the check to fail."
for _ in $(seq 1 20); do
  run="$(curl -fsS -u "${AUTH}" "${SERVICE_URL}/checks/${run_id}")"
  if printf '%s' "${run}" | grep -q '"status":"FAIL"'; then
    echo "FAIL recorded: ${run_id}"
    break
  fi
  sleep 1
done
if ! printf '%s' "${run:-}" | grep -q '"status":"FAIL"'; then
  echo "Timed out waiting for ${run_id}. Inspect ${SERVICE_URL}/checks/${run_id}" >&2
  exit 1
fi

echo "4/4: Waiting for the generic webhook receiver to capture the failure."
for _ in $(seq 1 20); do
  webhooks="$(curl -fsS "${DEMO_URL}/demo/webhooks")"
  if printf '%s' "${webhooks}" | grep -q 'CONTRACT_CHECK_FAILED'; then
    echo "Webhook received."
    echo "Check detail: ${SERVICE_URL}/ui/checks/${run_id}"
    echo "Webhook events: ${DEMO_URL}/demo/webhooks"
    exit 0
  fi
  sleep 1
done

echo "The check failed, but no webhook was received. Inspect ${SERVICE_URL}/api/notification-deliveries" >&2
exit 1
