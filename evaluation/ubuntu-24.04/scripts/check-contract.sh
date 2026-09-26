#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd -P)/lib.sh"
[[ $# -ge 1 && $# -le 3 ]] || die 'Usage: scripts/check-contract.sh <contract-id> [BACKWARD|FORWARD|FULL] [sqlite|postgres|mysql]'
contract_id=$1
mode=${2:-BACKWARD}
store=${3:-sqlite}
case "$contract_id" in *[!A-Za-z0-9._-]*|'') die 'Contract ID contains unsupported characters.';; esac
case "$mode" in BACKWARD|FORWARD|FULL) ;; *) die 'Mode must be BACKWARD, FORWARD, or FULL.';; esac
base="$WORKSPACE/contracts/$contract_id/v1.json"
candidate="$WORKSPACE/contracts/$contract_id/candidate.json"
[[ -f "$base" && -f "$candidate" ]] || die "Expected $base and $candidate"
require_java21
record_args=()
case "$store" in
  sqlite)
    record_args=(--record-db "$WORKSPACE/checks.db")
    ;;
  postgres)
    load_database_env
    export DCG_EVAL_DB_USER=$POSTGRES_USER DCG_EVAL_DB_PASSWORD=$POSTGRES_PASSWORD
    record_args=(--record-jdbc-url "jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/dcg_history" --record-db-user-env DCG_EVAL_DB_USER --record-db-password-env DCG_EVAL_DB_PASSWORD)
    ;;
  mysql)
    load_database_env
    export DCG_EVAL_DB_USER=$MYSQL_USER DCG_EVAL_DB_PASSWORD=$MYSQL_PASSWORD
    record_args=(--record-jdbc-url "jdbc:mysql://127.0.0.1:${MYSQL_PORT}/dcg_history?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" --record-db-user-env DCG_EVAL_DB_USER --record-db-password-env DCG_EVAL_DB_PASSWORD)
    ;;
  *) die 'History store must be sqlite, postgres, or mysql.';;
esac
exec "$DCG_HOME/bin/dcg" check-compat \
  --base "$base" --candidate "$candidate" --mode "$mode" \
  --contract-id "$contract_id" --commit-sha ubuntu-evaluation \
  "${record_args[@]}"
