# Quickstart (Local Only)

This project runs fully local. Docker/Testcontainers are not required.

## 1. Prerequisites

- Java 21+
- Maven 3.9+
- PostgreSQL running locally on `localhost:5432`
- Database `contracts` exists

Create DB once (if needed):

```bash
createdb -h localhost -p 5432 -U <your_pg_user> contracts
```

## 2. Build CLI

```bash
cd /Users/siddarthkanamadi/Personal_Projects/dcg/data-contract-governance
mvn -pl contract-cli -am package -DskipTests
```

## 3. Set Local Postgres Env

Replace values with your local credentials:

```bash
export TEST_POSTGRES_JDBC_URL="jdbc:postgresql://localhost:5432/contracts"
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

## 7. Run Postgres-Path Test Suite

```bash
cd /Users/siddarthkanamadi/Personal_Projects/dcg/data-contract-governance
mvn -pl contract-service -am \
  -Dtest=CheckRunStorePostgresPathTest,CheckControllerPostgresSuccessIntegrationTest,CheckControllerPostgresAuthFailureIntegrationTest,CheckControllerPostgresNetworkFailureIntegrationTest,CheckControllerPostgresSchemaMismatchIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

Success criteria:

- `Failures: 0`
- `Errors: 0`
- `BUILD SUCCESS`
