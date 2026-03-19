#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

: "${TEST_POSTGRES_JDBC_URL:=jdbc:postgresql://localhost:5432/contracts?currentSchema=dcg_dev}"
: "${TEST_POSTGRES_USERNAME:=siddarthkanamadi}"
: "${TEST_POSTGRES_PASSWORD:=root}"

: "${SPRING_PROFILES_ACTIVE:=local}"
: "${APP_UI_ENABLED:=true}"
: "${APP_SECURITY_ENABLED:=true}"
: "${APP_SECURITY_USERNAME:=admin}"
: "${APP_SECURITY_PASSWORD:=change-me}"
: "${APP_SECURITY_ROLES:=USER,WRITER}"
: "${APP_SECURITY_WRITE_ROLE:=WRITER}"

cd "$ROOT_DIR"

"$ROOT_DIR/scripts/dev/test-all.sh"

./mvnw -pl contract-cli -am -DskipTests package

CLI_JAR="$(ls "$ROOT_DIR"/contract-cli/target/*-all.jar | head -n 1)"
if [[ -z "$CLI_JAR" ]]; then
  echo "ERROR: contract-cli shaded jar not found under contract-cli/target."
  exit 1
fi

java -jar "$CLI_JAR" check-compat \
  --base contracts/orders.created/v1.json \
  --candidate contracts/orders.created/v2.json \
  --mode BACKWARD \
  --record-jdbc-url "$TEST_POSTGRES_JDBC_URL" \
  --record-db-user "$TEST_POSTGRES_USERNAME" \
  --record-db-password "$TEST_POSTGRES_PASSWORD" \
  --contract-id orders.created \
  --commit-sha local-week6

SPRING_PROFILES_ACTIVE="$SPRING_PROFILES_ACTIVE" \
CHECKS_DB_URL="$TEST_POSTGRES_JDBC_URL" \
CHECKS_DB_USERNAME="$TEST_POSTGRES_USERNAME" \
CHECKS_DB_PASSWORD="$TEST_POSTGRES_PASSWORD" \
APP_UI_ENABLED="$APP_UI_ENABLED" \
APP_SECURITY_ENABLED="$APP_SECURITY_ENABLED" \
APP_SECURITY_USERNAME="$APP_SECURITY_USERNAME" \
APP_SECURITY_PASSWORD="$APP_SECURITY_PASSWORD" \
APP_SECURITY_ROLES="$APP_SECURITY_ROLES" \
APP_SECURITY_WRITE_ROLE="$APP_SECURITY_WRITE_ROLE" \
./mvnw -f contract-service/pom.xml spring-boot:run
