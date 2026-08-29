#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMMAND="${1:-help}"

usage() {
  cat <<'EOF'
Usage: bash scripts/dcg.sh <command>

Start with `sequence`; it prints the recommended order without starting
containers. Run one database demo at a time because all demos use port 8080.

Commands:
  sequence          Print the recommended end-to-end learning/demo sequence
  test              Run the full Maven test suite
  cli-pass          Build the CLI and show a compatible schema result (PASS)
  cli-breaking      Build the CLI and show an intentional breaking result (FAIL)
  postgres          Start the local PostgreSQL Compose UI/API demo
  sqlite            Start the local SQLite Compose UI/API demo
  mysql             Start the local MySQL Compose UI/API demo (beta)
  s3                Prepare or start the S3 beta demo; requires user-supplied AWS values
  example-v4        Print the three-terminal Spring Boot webhook rehearsal
  example-starter   Print the Spring Boot validation-starter demo sequence
  recovery <store>  Run an isolated recovery drill: postgres, sqlite, mysql, or s3
  benchmark         Run the PostgreSQL-vs-MySQL benchmark (local databases required)
  private-verify    Statically verify the private MySQL Kubernetes baseline
  stop <store>      Stop one local Compose demo: postgres, sqlite, or mysql
  help              Show this help

The launcher creates an ignored `.env.*` file only for local PostgreSQL,
SQLite, or MySQL demos. It never reads, stages, or writes config environment
files, and it never creates AWS credentials.
EOF
}

die() {
  echo "[dcg] ERROR: $*" >&2
  exit 1
}

random_suffix() {
  printf '%s_%s' "$(date +%s)" "$RANDOM"
}

ensure_local_demo_env() {
  local store="$1"
  local env_file suffix
  suffix="$(random_suffix)"

  case "$store" in
    postgres) env_file="$ROOT_DIR/.env.live-demo" ;;
    sqlite) env_file="$ROOT_DIR/.env.sqlite-demo" ;;
    mysql) env_file="$ROOT_DIR/.env.mysql-demo" ;;
    s3) env_file="$ROOT_DIR/.env.s3-beta" ;;
    *) die "Unknown local demo store: $store" ;;
  esac

  [[ -f "$env_file" ]] && return 0

  umask 077
  case "$store" in
    postgres)
      cat >"$env_file" <<EOF
DCG_DB_USERNAME=dcg_demo
DCG_DB_PASSWORD=dcg-db-${suffix}
DCG_DB_SCHEMA=dcg_demo
DCG_APP_USERNAME=dcg-demo
DCG_APP_PASSWORD=dcg-app-${suffix}
DCG_SERVICE_PORT=8080
EOF
      ;;
    sqlite)
      cat >"$env_file" <<EOF
DCG_APP_USERNAME=dcg-demo
DCG_APP_PASSWORD=dcg-app-${suffix}
DCG_SERVICE_PORT=8080
EOF
      ;;
    mysql)
      cat >"$env_file" <<EOF
DCG_DB_USERNAME=dcg_demo
DCG_DB_PASSWORD=dcg-db-${suffix}
DCG_MYSQL_ROOT_PASSWORD=dcg-root-${suffix}
DCG_APP_USERNAME=dcg-demo
DCG_APP_PASSWORD=dcg-app-${suffix}
DCG_MYSQL_PORT=3306
DCG_SERVICE_PORT=8080
EOF
      ;;
    s3)
      cat >"$env_file" <<EOF
DCG_DB_USERNAME=dcg_demo
DCG_DB_PASSWORD=dcg-db-${suffix}
DCG_APP_USERNAME=dcg-demo
DCG_APP_PASSWORD=dcg-app-${suffix}
DCG_SERVICE_PORT=8080
CONTRACTS_ARTIFACT_BACKEND=s3
CONTRACTS_ARTIFACT_S3_BUCKET=replace-with-s3-bucket
CONTRACTS_ARTIFACT_S3_REGION=replace-with-aws-region
CONTRACTS_ARTIFACT_S3_ACCESS_KEY=replace-with-access-key
CONTRACTS_ARTIFACT_S3_SECRET_KEY=replace-with-secret-key
CONTRACTS_ARTIFACT_S3_FALLBACK_ENABLED=false
EOF
      ;;
  esac
  chmod 600 "$env_file"
  echo "[dcg] Created ignored local demo configuration: $env_file"
}

build_cli() {
  cd "$ROOT_DIR"
  ./mvnw --batch-mode --no-transfer-progress -pl contract-cli -am package -DskipTests
}

cli_jar() {
  local jar
  jar="$ROOT_DIR/contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar"
  [[ -f "$jar" ]] || die "CLI jar not found after build: $jar"
  printf '%s\n' "$jar"
}

print_sequence() {
  cat <<'EOF'
Recommended sequence (one Compose database demo at a time):

1. `bash scripts/dcg.sh test`
   Output: full Maven regression results.
2. `bash scripts/dcg.sh cli-pass`
   Output: local CLI `Schema compatibility: PASS`.
3. `bash scripts/dcg.sh cli-breaking`
   Output: intentional CLI breaking-change result; this is a successful demo when it exits non-zero.
4. `bash scripts/dcg.sh postgres`
   Output: PostgreSQL-backed UI, Swagger, health endpoint, and a queued check.
5. `bash scripts/dcg.sh stop postgres`
6. `bash scripts/dcg.sh sqlite`
   Output: SQLite-backed UI/API portability demo.
7. `bash scripts/dcg.sh stop sqlite`
8. `bash scripts/dcg.sh mysql`
   Output: MySQL 8.4 beta compatibility demo. Do not describe this as managed-production proof.
9. `bash scripts/dcg.sh stop mysql`
10. `bash scripts/dcg.sh example-v4`
    Output: instructions for the full Spring Boot + SDK + webhook rehearsal.

Optional, only when the prerequisites are intentionally available:
- `bash scripts/dcg.sh s3` for an AWS/S3 beta flow with your own least-privilege credentials.
- `bash scripts/dcg.sh recovery mysql` for an isolated restart/restore drill.
- `bash scripts/dcg.sh benchmark` for a local PostgreSQL-vs-MySQL comparison.
- `bash scripts/dcg.sh private-verify` for Kubernetes-manifest guardrails.

Full details: docs/run-dcg-and-examples.md
EOF
}

run_compose_demo() {
  local store="$1"
  ensure_local_demo_env "$store"
  case "$store" in
    postgres)
      DCG_COMPOSE_ENV_FILE="$ROOT_DIR/.env.live-demo" \
        bash "$ROOT_DIR/scripts/demo/run-compose-demo.sh"
      ;;
    sqlite)
      DCG_SQLITE_DEMO_ENV_FILE="$ROOT_DIR/.env.sqlite-demo" \
        bash "$ROOT_DIR/scripts/demo/run-sqlite-compose-demo.sh"
      ;;
    mysql)
      DCG_MYSQL_DEMO_ENV_FILE="$ROOT_DIR/.env.mysql-demo" \
        bash "$ROOT_DIR/scripts/demo/run-mysql-compose-demo.sh"
      ;;
  esac
}

stop_compose_demo() {
  local store="$1"
  command -v docker >/dev/null 2>&1 || die "Missing required command: docker"
  case "$store" in
    postgres)
      [[ -f "$ROOT_DIR/.env.live-demo" ]] || die "No local PostgreSQL demo configuration exists."
      docker compose --env-file "$ROOT_DIR/.env.live-demo" -f "$ROOT_DIR/docker-compose.yml" down
      ;;
    sqlite)
      [[ -f "$ROOT_DIR/.env.sqlite-demo" ]] || die "No local SQLite demo configuration exists."
      docker compose -p dcg-sqlite-demo --env-file "$ROOT_DIR/.env.sqlite-demo" \
        -f "$ROOT_DIR/docker-compose.sqlite.yml" down
      ;;
    mysql)
      [[ -f "$ROOT_DIR/.env.mysql-demo" ]] || die "No local MySQL demo configuration exists."
      docker compose -p dcg-mysql-demo --env-file "$ROOT_DIR/.env.mysql-demo" \
        -f "$ROOT_DIR/docker-compose.mysql.yml" down
      ;;
    *) die "Use one of: postgres, sqlite, mysql" ;;
  esac
}

print_v4_rehearsal() {
  cat <<EOF
From the repository root, use three terminals:

Terminal 1:
  bash examples/spring-boot-realworld-demo/scripts/run-webhook-receiver.sh

Terminal 2:
  bash examples/spring-boot-realworld-demo/scripts/run-dcg-service.sh

Terminal 3:
  bash examples/spring-boot-realworld-demo/scripts/seed-contracts.sh
  bash examples/spring-boot-realworld-demo/scripts/run-happy-path.sh
  bash examples/spring-boot-realworld-demo/scripts/run-breaking-path.sh

Expected output: CLI and SDK PASS, then intentional FAIL plus a received webhook.
Open: http://localhost:8080/ui, http://localhost:8080/ui/notifications,
      http://localhost:8081/demo/webhooks
EOF
}

print_starter_rehearsal() {
  cat <<EOF
Follow the independent validation-starter example in three terminals:

1. Build:
   ./mvnw clean install -pl contract-cli,contract-service,examples/dcg-spring-boot-realworld-demo -am
2. Start the demo app and then contract-service using the exact commands in:
   examples/dcg-spring-boot-realworld-demo/README.md
3. Run its happy-path and breaking-path scripts.

This example demonstrates runtime payload validation in addition to CLI/API checks.
EOF
}

case "$COMMAND" in
  help|-h|--help) usage ;;
  sequence) print_sequence ;;
  test)
    cd "$ROOT_DIR"
    ./mvnw --batch-mode --no-transfer-progress test
    ;;
  cli-pass)
    build_cli
    java -jar "$(cli_jar)" check-compat \
      --base "$ROOT_DIR/contracts/orders.created/v1.json" \
      --candidate "$ROOT_DIR/contracts/orders.created/v2.json" \
      --mode BACKWARD
    ;;
  cli-breaking)
    build_cli
    set +e
    java -jar "$(cli_jar)" check-compat \
      --base "$ROOT_DIR/examples/dcg-spring-boot-realworld-demo/contracts/orders.created/v2.json" \
      --candidate "$ROOT_DIR/examples/dcg-spring-boot-realworld-demo/contracts/orders.created/v3.json" \
      --mode BACKWARD
    exit_code=$?
    set -e
    [[ "$exit_code" -ne 0 ]] || die "Expected the intentional breaking check to exit non-zero."
    echo "[dcg] Expected breaking result observed (CLI exit code $exit_code)."
    ;;
  postgres|sqlite|mysql) run_compose_demo "$COMMAND" ;;
  s3)
    ensure_local_demo_env s3
    if rg -q '^CONTRACTS_ARTIFACT_S3_(BUCKET|REGION|ACCESS_KEY|SECRET_KEY)=replace-with-' "$ROOT_DIR/.env.s3-beta"; then
      echo "[dcg] Edit the ignored $ROOT_DIR/.env.s3-beta with least-privilege AWS values, then rerun `bash scripts/dcg.sh s3`."
      exit 0
    fi
    DCG_S3_BETA_ENV_FILE="$ROOT_DIR/.env.s3-beta" bash "$ROOT_DIR/scripts/demo/run-s3-beta-demo.sh"
    ;;
  example-v4) print_v4_rehearsal ;;
  example-starter) print_starter_rehearsal ;;
  recovery)
    store="${2:-}"
    case "$store" in
      postgres|sqlite|mysql|s3) bash "$ROOT_DIR/scripts/demo/run-${store}-recovery-drill.sh" ;;
      *) die "Usage: bash scripts/dcg.sh recovery <postgres|sqlite|mysql|s3>" ;;
    esac
    ;;
  benchmark) bash "$ROOT_DIR/scripts/perf/run-database-side-by-side-benchmark.sh" ;;
  private-verify) bash "$ROOT_DIR/scripts/verify-private-mysql-deployment.sh" ;;
  stop) stop_compose_demo "${2:-}" ;;
  *)
    usage >&2
    exit 2
    ;;
esac
