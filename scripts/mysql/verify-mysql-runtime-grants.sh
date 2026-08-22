#!/usr/bin/env bash
set -euo pipefail

: "${DCG_MYSQL_ADMIN_HOST:?Set DCG_MYSQL_ADMIN_HOST}"
: "${DCG_MYSQL_ADMIN_USERNAME:?Set DCG_MYSQL_ADMIN_USERNAME}"
: "${DCG_MYSQL_ADMIN_PASSWORD:?Set DCG_MYSQL_ADMIN_PASSWORD through secret injection}"
: "${DCG_MYSQL_RUNTIME_USERNAME:?Set DCG_MYSQL_RUNTIME_USERNAME}"

ADMIN_PORT="${DCG_MYSQL_ADMIN_PORT:-3306}"
RUNTIME_HOST="${DCG_MYSQL_RUNTIME_HOST:-%}"
TEMP_OUTPUT="$(mktemp -t dcg-mysql-runtime-grants.XXXXXX)"
trap 'rm -f "$TEMP_OUTPUT"' EXIT

MYSQL_PWD="$DCG_MYSQL_ADMIN_PASSWORD" mysql \
  --host="$DCG_MYSQL_ADMIN_HOST" \
  --port="$ADMIN_PORT" \
  --user="$DCG_MYSQL_ADMIN_USERNAME" \
  --batch --skip-column-names \
  -e "SHOW GRANTS FOR '${DCG_MYSQL_RUNTIME_USERNAME}'@'${RUNTIME_HOST}'" >"$TEMP_OUTPUT"

if rg -qi 'GRANT (ALL PRIVILEGES|.*\b(ALTER|CREATE|DROP|INDEX|TRIGGER)\b)' "$TEMP_OUTPUT"; then
  printf '[dcg-mysql-grants] ERROR: runtime identity has a prohibited DDL or global grant.\n' >&2
  exit 1
fi

if ! rg -qi 'GRANT .*\b(SELECT|INSERT|UPDATE|DELETE)\b' "$TEMP_OUTPUT"; then
  printf '[dcg-mysql-grants] ERROR: runtime identity has no expected DML grant.\n' >&2
  exit 1
fi

printf '[dcg-mysql-grants] PASS: runtime identity has no detected DDL/global grants.\n'
