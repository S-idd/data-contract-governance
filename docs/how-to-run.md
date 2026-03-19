# How To Run DCG Locally

This guide gets the Data Contract Governance (DCG) project running end-to-end on your machine.
It uses a dedicated Postgres schema (`dcg_dev`) so local runs stay isolated from test schemas.

All commands are meant to run from the repo root unless a step says otherwise.

## 0) Prereqs (Once)

- Java 21+
- PostgreSQL running on `localhost:5432`
- DB `contracts` exists

Create the database (only if needed):

```bash
createdb -h localhost -p 5432 -U <your_pg_user> contracts
```

Create the local dev schema:

```bash
psql -h localhost -p 5432 -U <your_pg_user> -d contracts -c "create schema if not exists dcg_dev;"
```

## 1) Quickest Path (Scripted Demo)

If you already have Postgres credentials set, run:

```bash
REPO_ROOT="/path/to/data-contract-governance"
cd "$REPO_ROOT"
export TEST_POSTGRES_JDBC_URL="jdbc:postgresql://localhost:5432/contracts?currentSchema=dcg_dev"
export TEST_POSTGRES_USERNAME="<your_pg_user>"
export TEST_POSTGRES_PASSWORD="<your_pg_password>"

bash scripts/demo/run-local-demo.sh
```

The script will:
- build the CLI
- record a PASS + FAIL run into Postgres
- start the service
- print the UI URLs

## 2) Manual Runbook (Copy-Paste)

From the repo root:

```bash
REPO_ROOT="/path/to/data-contract-governance"
cd "$REPO_ROOT"
./mvnw -pl contract-cli -am package -DskipTests

export TEST_POSTGRES_JDBC_URL="jdbc:postgresql://localhost:5432/contracts?currentSchema=dcg_dev"
export TEST_POSTGRES_USERNAME="<your_pg_user>"
export TEST_POSTGRES_PASSWORD="<your_pg_password>"
```

Record a check run:

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

Start the service + UI:

```bash
SPRING_PROFILES_ACTIVE=local \
CHECKS_DB_URL="$TEST_POSTGRES_JDBC_URL" \
CHECKS_DB_USERNAME="$TEST_POSTGRES_USERNAME" \
CHECKS_DB_PASSWORD="$TEST_POSTGRES_PASSWORD" \
APP_UI_ENABLED=true \
APP_SECURITY_ENABLED=false \
./mvnw -pl contract-service -am org.springframework.boot:spring-boot-maven-plugin:run
```

## 3) Open The UI

- `http://localhost:8080/ui`
- `http://localhost:8080/ui/contracts`
- `http://localhost:8080/ui/contracts/orders.created`

Get run IDs:

```bash
curl "http://localhost:8080/checks?contractId=orders.created"
```

Open check detail:

- `http://localhost:8080/ui/checks/<runId>`

## 4) Common Issues

**UI shows fewer rows than Postgres**
The service reads from the schema in `currentSchema`. Ensure the JDBC URL includes:

```
jdbc:postgresql://localhost:5432/contracts?currentSchema=dcg_dev
```

**mvnw not found**
Run from repo root (`./mvnw`) or use `../mvnw` when you are inside `contract-service`.
