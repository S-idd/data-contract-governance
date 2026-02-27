#!/usr/bin/env bash
set -euo pipefail

JAR_PATH="contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar"

if [[ ! -f "$JAR_PATH" ]]; then
  echo "CLI jar not found at $JAR_PATH. Build it before running this script."
  exit 2
fi

if [[ -z "${BASE_SHA:-}" || -z "${HEAD_SHA:-}" ]]; then
  echo "BASE_SHA and HEAD_SHA must be set."
  exit 2
fi

if [[ "$BASE_SHA" =~ ^0+$ ]]; then
  echo "BASE_SHA is empty/zero (initial push scenario). Using HEAD^ as fallback."
  BASE_SHA="$(git rev-parse "${HEAD_SHA}^" 2>/dev/null || true)"
  if [[ -z "$BASE_SHA" ]]; then
    echo "Unable to determine previous commit. Skipping contract checks."
    exit 0
  fi
fi

echo "Detecting changed contracts between $BASE_SHA and $HEAD_SHA"

changed_files=$(git diff --name-only "$BASE_SHA" "$HEAD_SHA" -- 'contracts/**' || true)

if [[ -z "$changed_files" ]]; then
  echo "No changes under contracts/. Skipping contract checks."
  exit 0
fi

mapfile -t changed_contract_dirs < <(
  echo "$changed_files" \
    | awk -F/ '/^contracts\/[^/]+\// {print "contracts/" $2}' \
    | sort -u
)

if [[ "${#changed_contract_dirs[@]}" -eq 0 ]]; then
  echo "No contract directories changed. Skipping contract checks."
  exit 0
fi

echo "Changed contract directories:"
for dir in "${changed_contract_dirs[@]}"; do
  echo "  - $dir"
done

for contract_dir in "${changed_contract_dirs[@]}"; do
  if [[ ! -d "$contract_dir" ]]; then
    echo "Skipping removed or missing directory: $contract_dir"
    continue
  fi

  echo ""
  echo "Linting $contract_dir"
  java -jar "$JAR_PATH" lint --path "$contract_dir"

  mapfile -t version_files < <(
    find "$contract_dir" -maxdepth 1 -type f -name 'v*.json' -printf '%f\n' | sort -V
  )

  if [[ "${#version_files[@]}" -lt 2 ]]; then
    echo "Skipping compatibility check for $contract_dir (need at least two versions)."
    continue
  fi

  base_version_file="${version_files[$((${#version_files[@]} - 2))]}"
  candidate_version_file="${version_files[$((${#version_files[@]} - 1))]}"
  base_path="$contract_dir/$base_version_file"
  candidate_path="$contract_dir/$candidate_version_file"

  echo "Checking compatibility for $contract_dir"
  echo "  base: $base_version_file"
  echo "  candidate: $candidate_version_file"
  java -jar "$JAR_PATH" check-compat --base "$base_path" --candidate "$candidate_path" --mode BACKWARD
done

echo ""
echo "Changed contract checks completed successfully."
