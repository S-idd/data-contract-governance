#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd -P)/lib.sh"
store=${1:-sqlite}
require_java21
mkdir -p "$STATE_ROOT/iems" "$WORKSPACE/iems-data"
[[ ! -f "$STATE_ROOT/iems/pid" ]] || die 'IEMS PID file exists. Run scripts/stop-iems.sh first.'
export IEMS_JWT_SECRET=${IEMS_JWT_SECRET:-$(openssl rand -hex 48)}
export IEMS_DEMO_ADMIN_PASSWORD=${IEMS_DEMO_ADMIN_PASSWORD:-$(openssl rand -hex 24)}
case "$store" in
  sqlite)
    profiles=db-demo,db-sqlite
    export IEMS_JDBC_URL="jdbc:sqlite:$WORKSPACE/iems-data/iems.db"
    ;;
  postgres)
    load_database_env
    profiles=db-demo,db-postgres
    export IEMS_JDBC_URL="jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/iems_app"
    export IEMS_DB_USER=$POSTGRES_USER IEMS_DB_PASSWORD=$POSTGRES_PASSWORD
    ;;
  mysql)
    load_database_env
    profiles=db-demo,db-mysql
    export IEMS_JDBC_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/iems_app?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    export IEMS_DB_USER=$MYSQL_USER IEMS_DB_PASSWORD=$MYSQL_PASSWORD
    ;;
  *) die 'Usage: scripts/start-iems.sh [sqlite|postgres|mysql]';;
esac
printf 'Running deterministic DCG gate for bundled IEMS contracts...\n'
gate_rc=0
for contract_dir in "$BUNDLE_ROOT"/iems/contracts/iems.*; do
  "$DCG_HOME/bin/dcg" lint --path "$contract_dir"
  record_args=()
  case "$store" in
    sqlite) record_args=(--record-db "$WORKSPACE/iems-dcg-checks.db");;
    postgres)
      export DCG_EVAL_DB_USER=$POSTGRES_USER DCG_EVAL_DB_PASSWORD=$POSTGRES_PASSWORD
      record_args=(--record-jdbc-url "jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/dcg_history" --record-db-user-env DCG_EVAL_DB_USER --record-db-password-env DCG_EVAL_DB_PASSWORD);;
    mysql)
      export DCG_EVAL_DB_USER=$MYSQL_USER DCG_EVAL_DB_PASSWORD=$MYSQL_PASSWORD
      record_args=(--record-jdbc-url "jdbc:mysql://127.0.0.1:${MYSQL_PORT}/dcg_history?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" --record-db-user-env DCG_EVAL_DB_USER --record-db-password-env DCG_EVAL_DB_PASSWORD);;
  esac
  result=0
  "$DCG_HOME/bin/dcg" check-compat --base "$contract_dir/v1.json" \
    --candidate "$contract_dir/candidate.json" --mode BACKWARD \
    --contract-id "${contract_dir##*/}" --commit-sha ubuntu-evaluation \
    "${record_args[@]}" || result=$?
  if (( result > gate_rc )); then gate_rc=$result; fi
done
[[ "$gate_rc" == 0 ]] || die "IEMS startup blocked by deterministic DCG gate (exit $gate_rc)."
umask 077
printf '%s\n' "$IEMS_DEMO_ADMIN_PASSWORD" > "$STATE_ROOT/iems/admin-password"
nohup java -jar "$BUNDLE_ROOT/iems/iems.jar" --spring.profiles.active="$profiles" \
  > "$STATE_ROOT/iems/application.log" 2>&1 < /dev/null &
pid=$!
printf '%s\n' "$pid" > "$STATE_ROOT/iems/pid"
for _ in $(seq 1 90); do
  if ! kill -0 "$pid" 2>/dev/null; then die "IEMS exited; inspect $STATE_ROOT/iems/application.log"; fi
  if curl --noproxy '*' -fsS http://127.0.0.1:8090/actuator/health >/dev/null 2>&1; then
    printf 'IEMS ready on http://127.0.0.1:8090 using %s. Log: %s\n' "$store" "$STATE_ROOT/iems/application.log"
    exit 0
  fi
  sleep 1
done
die "IEMS readiness timed out; inspect $STATE_ROOT/iems/application.log"
