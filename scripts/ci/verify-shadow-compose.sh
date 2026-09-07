#!/usr/bin/env bash
set -Eeuo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
project_root=$(cd "$script_dir/../.." && pwd)
model_root=${DCG_MODEL_ROOT:-"$project_root/../../dcgaimodel"}
compose_project=${DCG_SHADOW_COMPOSE_PROJECT:-"dcg-shadow-ci-${PPID}"}
service_port=${DCG_SHADOW_COMPOSE_PORT:-18080}
app_username=${DCG_SHADOW_COMPOSE_USERNAME:-dcg-compose-admin}
app_password=${DCG_SHADOW_COMPOSE_PASSWORD:-dcg-compose-demo-password}
compose=(
  docker compose
  --project-name "$compose_project"
  -f "$project_root/docker-compose.sqlite.yml"
  -f "$project_root/docker-compose.shadow-inference.yml"
)

if [[ ! -d "$model_root" ]]; then
  echo "Rust model checkout not found: $model_root" >&2
  echo "Set DCG_MODEL_ROOT to the dcgaimodel checkout." >&2
  exit 2
fi

export DCG_AI_MODEL_CONTEXT="$model_root"
export DCG_APP_USERNAME="$app_username"
export DCG_APP_PASSWORD="$app_password"
export DCG_SERVICE_PORT="$service_port"

cleanup() {
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_for() {
  local description=$1
  shift
  for _ in $(seq 1 40); do
    if "$@" >/dev/null 2>&1; then
      echo "$description: ready"
      return 0
    fi
    sleep 2
  done
  echo "$description: timed out" >&2
  "${compose[@]}" ps >&2 || true
  return 1
}

"${compose[@]}" up -d --build >/dev/null
wait_for "Java health" curl -fsS "http://127.0.0.1:${service_port}/actuator/health"
wait_for "Rust readiness through Compose DNS" \
  "${compose[@]}" exec -T contract-service curl -fsS http://dcgaimodel:8080/health/ready

readiness=$("${compose[@]}" exec -T contract-service curl -fsS http://dcgaimodel:8080/health/ready)
jq -e '
  .status == "UP" and
  .service == "dcgaimodel-shadow-inference" and
  .feature_version == "dcg-features-v6" and
  (.model_seeds | sort) == ["20260826", "20260827", "20260828"]
' <<<"$readiness" >/dev/null

submit_check() {
  local commit_sha=$1
  curl -fsS -u "${app_username}:${app_password}" \
    -X POST "http://127.0.0.1:${service_port}/checks" \
    -H 'Content-Type: application/json' \
    --data "{\"contractId\":\"orders.created\",\"baseVersion\":\"v1\",\"candidateVersion\":\"v2\",\"mode\":\"BACKWARD\",\"commitSha\":\"${commit_sha}\",\"triggeredBy\":\"compose-integration\"}"
}

check_result() {
  local run_id=$1
  curl -fsS -u "${app_username}:${app_password}" \
    "http://127.0.0.1:${service_port}/checks/${run_id}"
}

wait_for_check() {
  local run_id=$1
  local result=''
  for _ in $(seq 1 30); do
    result=$(check_result "$run_id")
    if jq -e '.status != "QUEUED" and .status != "RUNNING"' <<<"$result" >/dev/null; then
      printf '%s\n' "$result"
      return 0
    fi
    sleep 1
  done
  echo "check $run_id: timed out" >&2
  printf '%s\n' "$result" >&2
  return 1
}

created=$(submit_check "compose-shadow-ci-available")
available_run_id=$(jq -er '.runId' <<<"$created")
available_result=$(wait_for_check "$available_run_id")
jq -e '.status == "PASS" and .executionState == "SUCCESS"' <<<"$available_result" >/dev/null

for _ in $(seq 1 20); do
  logs=$("${compose[@]}" logs --no-color contract-service 2>/dev/null || true)
  if grep -Fq 'event=shadow_inference_prediction' <<<"$logs" \
      && grep -Fq 'seed":"20260826"' <<<"$logs" \
      && grep -Fq 'seed":"20260827"' <<<"$logs" \
      && grep -Fq 'seed":"20260828"' <<<"$logs"; then
    echo "available shadow prediction: logged"
    break
  fi
  sleep 1
done
grep -Fq 'event=shadow_inference_prediction' <<<"$logs"

"${compose[@]}" stop dcgaimodel >/dev/null
created=$(submit_check "compose-shadow-ci-unavailable")
unavailable_run_id=$(jq -er '.runId' <<<"$created")
unavailable_result=$(wait_for_check "$unavailable_run_id")
jq -e '.status == "PASS" and .executionState == "SUCCESS"' <<<"$unavailable_result" >/dev/null

for _ in $(seq 1 20); do
  logs=$("${compose[@]}" logs --no-color contract-service 2>/dev/null || true)
  if grep -Fq 'event=shadow_inference_call_failed' <<<"$logs" \
      && (grep -Fq 'failure_stage=CONNECTION_REFUSED' <<<"$logs" \
        || grep -Fq 'failure_stage=TIMEOUT' <<<"$logs"); then
    echo "unavailable shadow inference: fail-open logged"
    break
  fi
  sleep 1
done
grep -Fq 'event=shadow_inference_call_failed' <<<"$logs"
grep -Eq 'failure_stage=(CONNECTION_REFUSED|TIMEOUT)' <<<"$logs"

"${compose[@]}" up -d dcgaimodel >/dev/null
wait_for "Rust recovery" \
  "${compose[@]}" exec -T contract-service curl -fsS http://dcgaimodel:8080/health/ready
echo "Shadow Compose integration verification passed."
