#!/usr/bin/env bash
# Prevent local environment files from being added to version control.
# Secret content is inspected separately by Gitleaks; this guard makes the
# repository boundary explicit and catches accidental `git add -f` usage.
set -Eeuo pipefail

failed=0

while IFS= read -r -d '' path; do
  printf 'error: environment file must not be tracked: %s\n' "$path" >&2
  failed=1
done < <(git ls-files -z -- .env '.env.*' '*.env' '*.env.*')

# Verify that the ignore policy covers both repository-root and config paths.
for path in .env .env.local config/dcg-local.env config/dcg-local.env.example; do
  if ! git check-ignore --quiet --no-index -- "$path"; then
    printf 'error: expected environment file to be ignored: %s\n' "$path" >&2
    failed=1
  fi
done

if (( failed )); then
  printf '%s\n' 'Keep credentials in your secret manager or untracked local environment files.' >&2
  exit 1
fi

printf '%s\n' 'Secret-hygiene path policy passed.'
