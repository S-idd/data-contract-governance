# Quickstart (Local Only)

This project runs fully local. Docker/Testcontainers are not required.

## Fastest Path

If your local PostgreSQL credentials are already set, use the one-command demo runner:

```bash
cd /path/to/data-contract-governance
bash scripts/demo/run-local-demo.sh
```

For the full manual setup, continue below.

## Manual Runbook (Copy-Paste)

Use this when you want the exact command sequence end to end. Replace placeholders with your local credentials.

```bash
cd /path/to/data-contract-governance
mvn -pl contract-cli -am package -DskipTests

export TEST_POSTGRES_JDBC_URL="jdbc:postgresql://localhost:5432/contracts?currentSchema=dcg_dev"
export TEST_POSTGRES_USERNAME="<your_pg_user>"
export TEST_POSTGRES_PASSWORD="<your_pg_password>"

java -jar contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar \
  check-compat \
  --base contracts/orders.created/v1.json \
  --candidate contracts/orders.created/v2.json \
  --mode BACKWARD \
  --record-jdbc-url "$TEST_POSTGRES_JDBC_URL" \
  --record-db-user "$TEST_POSTGRES_USERNAME" \
  --record-db-password "$TEST_POSTGRES_PASSWORD" \
  --contract-id orders.created \
  --commit-sha demo-local

cd contract-service
SPRING_PROFILES_ACTIVE=local \
CHECKS_DB_URL="$TEST_POSTGRES_JDBC_URL" \
CHECKS_DB_USERNAME="$TEST_POSTGRES_USERNAME" \
CHECKS_DB_PASSWORD="$TEST_POSTGRES_PASSWORD" \
APP_UI_ENABLED=true \
APP_SECURITY_ENABLED=false \
mvn spring-boot:run
```

Open the UI:

- `http://localhost:8080/ui`
- `http://localhost:8080/ui/contracts`
- `http://localhost:8080/ui/contracts/orders.created`

Get run IDs:

```bash
curl "http://localhost:8080/checks?contractId=orders.created"
```

Open check detail:

- `http://localhost:8080/ui/checks/<runId>`

## 1. Prerequisites

- Java 21+
- Maven 3.9+
- PostgreSQL running locally on `localhost:5432`
- Database `contracts` exists

Create DB once (if needed):

```bash
createdb -h localhost -p 5432 -U <your_pg_user> contracts
psql -h localhost -p 5432 -U <your_pg_user> -d contracts -c "create schema if not exists dcg_dev;"
```

## 2. Build CLI

```bash
cd /path/to/data-contract-governance
mvn -pl contract-cli -am package -DskipTests
```

## 3. Set Local Postgres Env

Replace values with your local credentials:

```bash
export TEST_POSTGRES_JDBC_URL="jdbc:postgresql://localhost:5432/contracts?currentSchema=dcg_dev"
export TEST_POSTGRES_USERNAME="<your_pg_user>"
export TEST_POSTGRES_PASSWORD="<your_pg_password>"
```

Optional connectivity check:

```bash
psql -h localhost -p 5432 -U "$TEST_POSTGRES_USERNAME" -d contracts -c "select current_user, current_database();"
```

## 4. Record a Check Run

```bash
java -jar contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar \
  check-compat \
  --base contracts/orders.created/v1.json \
  --candidate contracts/orders.created/v2.json \
  --mode BACKWARD \
  --record-jdbc-url "$TEST_POSTGRES_JDBC_URL" \
  --record-db-user "$TEST_POSTGRES_USERNAME" \
  --record-db-password "$TEST_POSTGRES_PASSWORD" \
  --contract-id orders.created \
  --commit-sha demo-local
```

Expected in output: `Persistence: RECORDED`

## 5. Start Service + Embedded UI

```bash
cd contract-service
SPRING_PROFILES_ACTIVE=local \
CHECKS_DB_URL="$TEST_POSTGRES_JDBC_URL" \
CHECKS_DB_USERNAME="$TEST_POSTGRES_USERNAME" \
CHECKS_DB_PASSWORD="$TEST_POSTGRES_PASSWORD" \
APP_UI_ENABLED=true \
APP_SECURITY_ENABLED=false \
mvn spring-boot:run
```

## 6. Open UI in Browser

- `http://localhost:8080/ui`
- `http://localhost:8080/ui/contracts`
- `http://localhost:8080/ui/contracts/orders.created`

Get run IDs:

```bash
curl "http://localhost:8080/checks?contractId=orders.created"
```

Open check detail:

- `http://localhost:8080/ui/checks/<runId>`

Notes:

- The dashboard and contract detail pages default to the latest 20 runs.
- If the UI looks "short", clear filters (Status should be `Any`) or use the API example below.

```bash
curl "http://localhost:8080/checks/page?contractId=orders.created&limit=50&offset=0"
```

## 7. Run Postgres-Path Test Suite

```bash
cd /path/to/data-contract-governance
mvn -pl contract-service -am \
  -Dtest=CheckRunStorePostgresPathTest,CheckControllerPostgresSuccessIntegrationTest,CheckControllerPostgresAuthFailureIntegrationTest,CheckControllerPostgresNetworkFailureIntegrationTest,CheckControllerPostgresSchemaMismatchIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

Success criteria:

- `Failures: 0`
- `Errors: 0`
- `BUILD SUCCESS`
- `Skipped: 0` for the Postgres availability-dependent tests (`CheckControllerPostgresSuccessIntegrationTest`, `CheckControllerPostgresAuthFailureIntegrationTest`, `CheckControllerPostgresSchemaMismatchIntegrationTest`)

If those Postgres tests are skipped, your local PostgreSQL path was not actually exercised. Re-check `TEST_POSTGRES_JDBC_URL`, `TEST_POSTGRES_USERNAME`, `TEST_POSTGRES_PASSWORD`, and direct `psql` connectivity before treating the run as production-ready.
