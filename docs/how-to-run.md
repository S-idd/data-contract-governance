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

Once it starts, follow the submit -> run -> explain flow:

- open `http://localhost:8080/ui/contracts/orders.created`
- click "Run Check" to queue a run (`v1` -> `v2`)
- you will land on `/ui/checks/<runId>` and watch `QUEUED -> RUNNING -> PASS/FAIL`
- open the FAIL run URL printed by the script to show the explanation

## 2) Manual Runbook (Copy-Paste)

From the repo root:

```bash
REPO_ROOT="/path/to/data-contract-governance"
cd "$REPO_ROOT"
# Optional: build the CLI jar for the rerun snippet shown in the UI
./mvnw -pl contract-cli -am package -DskipTests

export TEST_POSTGRES_JDBC_URL="jdbc:postgresql://localhost:5432/contracts?currentSchema=dcg_dev"
export TEST_POSTGRES_USERNAME="<your_pg_user>"
export TEST_POSTGRES_PASSWORD="<your_pg_password>"
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

Submit a check run (UI or API):

Use the UI:

- `http://localhost:8080/ui/contracts/orders.created`
- pick `v1` as base and `v2` as candidate
- click "Run Check"

Or submit via API:

```bash
curl -X POST "http://localhost:8080/checks" \
  -H "Content-Type: application/json" \
  -d '{
    "contractId": "orders.created",
    "baseVersion": "v1",
    "candidateVersion": "v2",
    "mode": "BACKWARD",
    "commitSha": "demo-local",
    "triggeredBy": "manual"
  }'
```

Copy the `runId` from the response.

## 3) Open The UI

- `http://localhost:8080/ui`
- `http://localhost:8080/ui/contracts`
- `http://localhost:8080/ui/contracts/orders.created`
- `http://localhost:8080/ui/checks/<runId>`

Notes:

- The check detail page will move from `QUEUED` to `RUNNING` to `PASS/FAIL` and auto-refresh.

## 4) Common Issues

**UI shows fewer rows than Postgres**
The service reads from the schema in `currentSchema`. Ensure the JDBC URL includes:

```
jdbc:postgresql://localhost:5432/contracts?currentSchema=dcg_dev
```

**mvnw not found**
Run from repo root (`./mvnw`) or use `../mvnw` when you are inside `contract-service`.
