# Data Contract Governance (V1)

Open-source Java/Spring Boot tooling to prevent breaking schema changes before merge/deploy.

## Prerequisites
- Java 21
- Maven 3.9+

## Build
```bash
cd /path/to/data-contract-governance
mvn test
```

## Build CLI Fat Jar
```bash
cd /path/to/data-contract-governance
mvn -pl contract-cli -am package
```

## CLI Usage

Help:
```bash
java -jar contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar --help
```

Lint sample contract:
```bash
java -jar contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar lint --path contracts/orders.created
```

Diff sample versions:
```bash
java -jar contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar diff --base contracts/orders.created/v1.json --candidate contracts/orders.created/v2.json
```

Check compatibility:
```bash
java -jar contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar check-compat --base contracts/orders.created/v1.json --candidate contracts/orders.created/v2.json --mode BACKWARD
```

Record compatibility result to SQLite:
```bash
java -jar contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar check-compat --base contracts/orders.created/v1.json --candidate contracts/orders.created/v2.json --mode BACKWARD --record-db checks.db --contract-id orders.created --commit-sha local-dev
```

Record compatibility result to PostgreSQL:
```bash
java -jar contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar check-compat --base contracts/orders.created/v1.json --candidate contracts/orders.created/v2.json --mode BACKWARD --record-jdbc-url "jdbc:postgresql://localhost:5432/contracts" --record-db-user contracts_user --record-db-password change-me --contract-id orders.created --commit-sha local-dev
```

Record compatibility result to PostgreSQL using env-secret references:
```bash
export CONTRACT_DB_USER="contracts_user"
export CONTRACT_DB_PASSWORD="change-me"
java -jar contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar check-compat --base contracts/orders.created/v1.json --candidate contracts/orders.created/v2.json --mode BACKWARD --record-jdbc-url "jdbc:postgresql://localhost:5432/contracts" --record-db-user-env CONTRACT_DB_USER --record-db-password-env CONTRACT_DB_PASSWORD --contract-id orders.created --commit-sha local-dev
```

## CI Contract Checks (Changed Contracts Only)
GitHub Actions runs full tests and then checks only changed contract directories.

Local dry-run of the same changed-contract check:
```bash
BASE_SHA=<older_commit_sha> HEAD_SHA=<newer_commit_sha> bash scripts/ci/check-changed-contracts.sh
```

## Contract Service API (Read-Only)
Run service (default SQLite):
```bash
cd /path/to/data-contract-governance/contract-service
mvn spring-boot:run
```

Run service with PostgreSQL in local profile (SSL disabled by default):
```bash
cd /path/to/data-contract-governance/contract-service
SPRING_PROFILES_ACTIVE="local" \
CHECKS_DB_URL="jdbc:postgresql://localhost:5432/contracts" \
CHECKS_DB_USERNAME="contracts_user" \
CHECKS_DB_PASSWORD="change-me" \
mvn spring-boot:run
```

Run service with PostgreSQL in prod profile (SSL enabled + strict mode by default):
```bash
cd /path/to/data-contract-governance/contract-service
SPRING_PROFILES_ACTIVE="prod" \
CHECKS_DB_URL="jdbc:postgresql://db.internal.example:5432/contracts" \
CHECKS_DB_USERNAME="contracts_user" \
CHECKS_DB_PASSWORD="change-me" \
CHECKS_DB_SSL_ROOT_CERT="/etc/ssl/certs/db-root.crt" \
mvn spring-boot:run
```

Service check-store hardening (env-configurable):
- Check-store schema is managed via shared Flyway migrations in `contract-core/src/main/resources/db/migration` (used by both CLI and service), replacing runtime `CREATE TABLE` DDL.
- `checks.db.pool.maximum-size` and `checks.db.pool.minimum-idle` tune HikariCP pooling.
- `checks.db.pool.connection-timeout`, `checks.db.pool.validation-timeout`, and `checks.db.query-timeout` enforce request/DB time bounds.
- `checks.db.ssl.enabled=true` enables PostgreSQL SSL params (`sslmode`, optional cert paths).
- `checks.db.enforce-secure-postgres=true` (enabled by `prod` profile) requires strict SSL mode (`verify-ca` or `verify-full`) for PostgreSQL URLs.
- `checks.db.fail-fast-startup=true` (enabled by `prod` profile) fails app startup if check-store DB init fails.
- `checks.db.password` can come from `CHECKS_DB_PASSWORD`, or leave it blank and set `checks.db.password-env` to read from a separate secret env key at runtime.
- `checks.db.username-env` / `checks.db.password-env` now fail fast when referenced env vars are missing/blank.

Production observability:
- `/actuator/health` includes check-store pool details (`poolActiveConnections`, `poolIdleConnections`, `poolThreadsAwaitingConnection`).
- `/actuator/metrics` exposes gauges: `check_store.pool.connections.active`, `check_store.pool.connections.idle`, `check_store.pool.connections.pending`, `check_store.pool.connections.total`, `check_store.pool.connections.max`.

PostgreSQL smoke-test for migrations:
```bash
export CHECKS_DB_URL="jdbc:postgresql://localhost:5432/contracts"
export CHECKS_DB_USERNAME="contracts_user"
export CHECKS_DB_PASSWORD="change-me"
export PSQL_URL="postgresql://contracts_user:change-me@localhost:5432/contracts"

java -jar contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar \
  check-compat \
  --base contracts/orders.created/v1.json \
  --candidate contracts/orders.created/v2.json \
  --mode BACKWARD \
  --record-jdbc-url "$CHECKS_DB_URL" \
  --record-db-user "$CHECKS_DB_USERNAME" \
  --record-db-password "$CHECKS_DB_PASSWORD" \
  --contract-id orders.created \
  --commit-sha postgres-migration-test
```
Then validate:
```bash
psql "$PSQL_URL" -c "select version, description, success from flyway_schema_history order by installed_rank;"
psql "$PSQL_URL" -c "select run_id, contract_id, status, created_at from check_runs order by created_at desc limit 5;"
```

Endpoints:
```bash
curl http://localhost:8080/contracts
curl http://localhost:8080/contracts/orders.created
curl http://localhost:8080/contracts/orders.created/versions
curl http://localhost:8080/contracts/orders.created/versions/v1
curl http://localhost:8080/checks
curl http://localhost:8080/checks/run-1
curl "http://localhost:8080/checks/page?limit=20&offset=0"
curl "http://localhost:8080/checks?contractId=orders.created"
```

OpenAPI / Swagger UI:
```bash
http://localhost:8080/swagger-ui/index.html
http://localhost:8080/v3/api-docs
```

Embedded UI routes:
```bash
http://localhost:8080/ui
http://localhost:8080/ui/contracts
http://localhost:8080/ui/contracts/orders.created
http://localhost:8080/ui/checks/run-1
```

UI/security toggles (local-first defaults):
- `APP_UI_ENABLED=true` enables embedded UI routes.
- `APP_SECURITY_ENABLED=false` keeps local workflow frictionless.
- When `APP_SECURITY_ENABLED=true`, `/ui/**` and `/checks/**` require HTTP Basic auth.
- Configure basic auth credentials with:
  - `APP_SECURITY_USERNAME`
  - `APP_SECURITY_PASSWORD`

Example secure run:
```bash
cd contract-service
APP_SECURITY_ENABLED=true \
APP_SECURITY_USERNAME=demo \
APP_SECURITY_PASSWORD=demo-secret \
mvn spring-boot:run
```

## One-Command Demo (Windows PowerShell)
```powershell
cd <repo-root>
.\scripts\demo\make-demo.ps1
```
This script:
- builds CLI fat jar
- records one compatibility check in SQLite
- starts `contract-service`
- prints Swagger URL and sample API outputs
- stops service automatically

## Local Demo (macOS/Linux)
One-command local demo against local PostgreSQL:

```bash
cd /path/to/data-contract-governance
bash scripts/demo/run-local-demo.sh
```

Required env vars:

```bash
export TEST_POSTGRES_JDBC_URL="jdbc:postgresql://localhost:5432/contracts"
export TEST_POSTGRES_USERNAME="<your_pg_user>"
export TEST_POSTGRES_PASSWORD="<your_pg_password>"
```

Helpful docs:

- [Local Quickstart](quickstart-local.md)
- [Demo Walkthrough](docs/demo-walkthrough.md)
- [Week 7 Exit Checklist](docs/week7-exit-checklist.md)
- [Week 8 Stabilization Checklist](docs/week8-stabilization-checklist.md)

## Sample Contracts
- [orders.created metadata](contracts/orders.created/metadata.yaml)
- [orders.created v1](contracts/orders.created/v1.json)
- [orders.created v2](contracts/orders.created/v2.json)

## Project Docs
- [Requirements](docs/Requirements.md)
- [System Design](docs/SystemDesign.md)
- [Architecture Decisions](adr/ArchitectureDecisionRecord.md)
- [Local Quickstart](quickstart-local.md)
- [Demo Walkthrough](docs/demo-walkthrough.md)
- [Week 7 Exit Checklist](docs/week7-exit-checklist.md)
- [Week 8 Stabilization Checklist](docs/week8-stabilization-checklist.md)
