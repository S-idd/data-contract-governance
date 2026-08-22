#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
POSTGRES_IMAGE="${DCG_BENCHMARK_POSTGRES_IMAGE:-postgres:16}"
MYSQL_IMAGE="${DCG_BENCHMARK_MYSQL_IMAGE:-mysql:8.4}"
POSTGRES_PORT="${DCG_BENCHMARK_POSTGRES_PORT:-15432}"
MYSQL_PORT="${DCG_BENCHMARK_MYSQL_PORT:-13307}"
POSTGRES_CONTAINER="dcg-benchmark-postgres-$$"
MYSQL_CONTAINER="dcg-benchmark-mysql-$$"
POSTGRES_USER="benchmark"
POSTGRES_PASSWORD="benchmark-postgres-pass"
MYSQL_USER="benchmark"
MYSQL_PASSWORD="benchmark-mysql-pass"
MYSQL_ROOT_PASSWORD="benchmark-mysql-root-pass"

cleanup() {
  docker rm -f "$POSTGRES_CONTAINER" "$MYSQL_CONTAINER" >/dev/null 2>&1 || true
}

die() {
  printf '[dcg-benchmark] ERROR: %s\n' "$*" >&2
  exit 1
}

wait_for_postgres() {
  for _ in $(seq 1 60); do
    if docker exec "$POSTGRES_CONTAINER" pg_isready -U "$POSTGRES_USER" -d contracts >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  die "PostgreSQL did not become ready."
}

wait_for_mysql() {
  for _ in $(seq 1 90); do
    if docker exec -e "MYSQL_PWD=$MYSQL_PASSWORD" "$MYSQL_CONTAINER" \
      mysql -u"$MYSQL_USER" -Dcontracts -e 'select 1' >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  die "MySQL did not become ready."
}

command -v docker >/dev/null || die "docker is required"
command -v lsof >/dev/null || die "lsof is required"
docker info >/dev/null 2>&1 || die "Docker daemon is not reachable"
[[ ! -e "$ROOT_DIR/.git/index.lock" ]] || die "Git index is locked; retry after the active Git operation finishes"
lsof -tiTCP:"$POSTGRES_PORT" -sTCP:LISTEN >/dev/null 2>&1 \
  && die "PostgreSQL benchmark port $POSTGRES_PORT is in use"
lsof -tiTCP:"$MYSQL_PORT" -sTCP:LISTEN >/dev/null 2>&1 \
  && die "MySQL benchmark port $MYSQL_PORT is in use"
trap cleanup EXIT

printf '[dcg-benchmark] Starting disposable PostgreSQL and MySQL containers.\n'
docker run --detach --rm --name "$POSTGRES_CONTAINER" --publish "127.0.0.1:${POSTGRES_PORT}:5432" \
  --env "POSTGRES_DB=contracts" --env "POSTGRES_USER=$POSTGRES_USER" \
  --env "POSTGRES_PASSWORD=$POSTGRES_PASSWORD" "$POSTGRES_IMAGE" >/dev/null
docker run --detach --rm --name "$MYSQL_CONTAINER" --publish "127.0.0.1:${MYSQL_PORT}:3306" \
  --env "MYSQL_DATABASE=contracts" --env "MYSQL_USER=$MYSQL_USER" \
  --env "MYSQL_PASSWORD=$MYSQL_PASSWORD" --env "MYSQL_ROOT_PASSWORD=$MYSQL_ROOT_PASSWORD" \
  "$MYSQL_IMAGE" >/dev/null
wait_for_postgres
wait_for_mysql

export DCG_BENCHMARK_POSTGRES_JDBC_URL="jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/contracts"
export DCG_BENCHMARK_POSTGRES_USERNAME="$POSTGRES_USER"
export DCG_BENCHMARK_POSTGRES_PASSWORD="$POSTGRES_PASSWORD"
export DCG_BENCHMARK_MYSQL_JDBC_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/contracts?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
export DCG_BENCHMARK_MYSQL_USERNAME="$MYSQL_USER"
export DCG_BENCHMARK_MYSQL_PASSWORD="$MYSQL_PASSWORD"
export DCG_BENCHMARK_REPORT_FILE="${DCG_BENCHMARK_REPORT_FILE:-$ROOT_DIR/docs/verification/database-side-by-side-latest.md}"

printf '[dcg-benchmark] Running the equal DCG store workload.\n'
"$ROOT_DIR/mvnw" -pl contract-service -am test \
  -Dtest=DatabaseSideBySideBenchmarkHarness -Dsurefire.failIfNoSpecifiedTests=false
printf '[dcg-benchmark] PASS: report written to %s\n' "$DCG_BENCHMARK_REPORT_FILE"
