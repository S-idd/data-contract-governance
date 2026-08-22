# Data Contract Governance (V1)

Open-source Java/Spring Boot tooling to prevent breaking schema changes before merge/deploy.

> **Public beta:** PostgreSQL is the production-standard metadata path. SQLite is limited to
> single-node production-lite deployments. MySQL and S3 remain beta; managed MySQL durability,
> PITR, and live failover need provider-specific validation before a production claim. See the
> [support policy](docs/support-policy.md) and [production limitations](docs/production-limitations.md).

Open-source project information: [Contributing](CONTRIBUTING.md),
[Security](SECURITY.md), [Code of Conduct](CODE_OF_CONDUCT.md),
[Changelog](CHANGELOG.md), [release policy](docs/release-and-versioning.md), and the
[Apache-2.0 license](LICENSE).

## Start Here

- **Main Docker demo (PostgreSQL):** [Compose Quickstart](docs/quickstart-compose.md). This is the normal demo and the recommended first run.
- **Database compatibility demo:** [PostgreSQL, SQLite, and MySQL presenter guide](docs/database-compatibility-demo.md).
- **Native local PostgreSQL:** [How to run DCG locally](docs/how-to-run.md). Use this only when you already manage PostgreSQL outside Docker.
- **S3 artifact backend:** [S3 beta guide](docs/how-to-s3-beta.md). This is separate from the normal demo and requires AWS configuration.

V4 Spring Boot demo: `examples/dcg-spring-boot-realworld-demo/README.md`.

V4 recovery drill: `docs/version4-recovery-and-incident-runbook.md`.
Database compatibility live demo: `docs/database-compatibility-demo.md`.
V4 production-readiness entry points: `docs/Architecture-v4.md`, `docs/version4-production-readiness-release-plan.md`, and `examples/spring-boot-realworld-demo/README.md` for the separate Spring Boot validation rehearsal.

Metadata database support: PostgreSQL (production standard), SQLite (local development and single-node production-lite), and MySQL (beta). DCG selects one configured metadata database per running instance; it does not connect to all databases simultaneously.

Run the main DCG UI/API with SQLite or MySQL in Docker: `bash scripts/demo/run-sqlite-compose-demo.sh` or `bash scripts/demo/run-mysql-compose-demo.sh`. The complete presenter flow is in `docs/database-compatibility-demo.md`.

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

## Enterprise JSON Schema Support

DCG validates and classifies the Draft 2020-12 assertion, applicator, core, and annotation keywords used by enterprise contracts. It supports nested objects, array item schemas, local `#/$defs/...` references, `allOf`, discriminated `oneOf` branches, `if`/`then`/`else`, and common constraints. Compatibility results use full field paths such as `lineItems[].quantity` and reject breaking nested changes, including tighter bounds, patterns, formats, branch removal, and stricter conditional rules.

For complex applicators such as `anyOf`, `not`, `patternProperties`, `contains`, `dependentSchemas`, and unevaluated-property rules, DCG records a canonical schema restriction. A candidate addition or change to one of these restrictions fails compatibility conservatively rather than being silently approved. Annotation-only changes do not affect compatibility. The governance diff supports local JSON Pointer references only; cross-document references and `$dynamicRef` are rejected explicitly until their resolution semantics are implemented.

## Build integrations

The Maven and Gradle plugins are offline-first. Their optional reporting imports an immutable local evidence artifact, with authenticated verification, version-skew visibility, and replay support. See [build integrations](docs/build-integrations.md) and the [evidence ADR](docs/adr/2026-08-20-evidence-import-verification-replay.md).

DCG has offline-first Maven and Gradle integrations. They run `contract-core` locally, write a JSON evidence report, and only then optionally import that exact artifact with a bounded remote call.

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
psql -h localhost -U contracts_user -d contracts -c "create schema if not exists dcg_dev;"
SPRING_PROFILES_ACTIVE="local" \
CHECKS_DB_URL="jdbc:postgresql://localhost:5432/contracts?currentSchema=dcg_dev" \
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

Run service with MySQL in local profile:
```bash
cd /path/to/data-contract-governance/contract-service
SPRING_PROFILES_ACTIVE="local" \
CHECKS_DB_URL="jdbc:mysql://localhost:3306/contracts?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
CHECKS_DB_USERNAME="contracts_user" \
CHECKS_DB_PASSWORD="change-me" \
mvn spring-boot:run
```

Run service with SQLite production-lite guardrails:
```bash
cd /path/to/data-contract-governance/contract-service
SPRING_PROFILES_ACTIVE="sqlite-prod-lite" \
CHECKS_DB_PATH="/var/lib/dcg/checks.db" \
APP_SECURITY_ENABLED=true \
mvn spring-boot:run
```

Service check-store hardening (env-configurable):
- Check-store schema is managed via Flyway migrations in `contract-core/src/main/resources/db/migration` (PostgreSQL/SQLite) and `contract-core/src/main/resources/db/migration-mysql` (MySQL), replacing runtime `CREATE TABLE` DDL.
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
export CHECKS_DB_URL="jdbc:postgresql://localhost:5432/contracts?currentSchema=dcg_dev"
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
psql "$PSQL_URL" -c "select version, description, success from dcg_dev.flyway_schema_history order by installed_rank;"
psql "$PSQL_URL" -c "select run_id, contract_id, status, created_at from dcg_dev.check_runs order by created_at desc limit 5;"
```

3-DB metadata test matrix:
```bash
cd /path/to/data-contract-governance

export TEST_POSTGRES_JDBC_URL="jdbc:postgresql://localhost:5432/contracts?currentSchema=dcg_dev"
export TEST_POSTGRES_USERNAME="<your_pg_user>"
export TEST_POSTGRES_PASSWORD="<your_pg_password>"

export TEST_MYSQL_JDBC_URL="jdbc:mysql://localhost:3306/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
export TEST_MYSQL_USERNAME="<your_mysql_user>"
export TEST_MYSQL_PASSWORD="<your_mysql_password>"

./mvnw -pl contract-service -am \
  -Dtest=CheckRunStoreSqliteContractTest,CheckRunStorePostgresContractTest,CheckRunStoreMySqlContractTest,CheckRunStorePostgresPathTest,CheckRunStoreMySqlPathTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
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
- When `APP_SECURITY_ENABLED=true`, `/ui/**` and ordinary `/checks/**` routes require HTTP Basic auth. Production `POST /checks/evidence` instead requires a CI-issued OIDC Bearer token with an explicitly authorized contract/repository/ref mapping; see [build integrations](docs/build-integrations.md).
- Write routes (POST/PUT/PATCH/DELETE) require the `WRITER` role by default.
- Configure basic auth credentials with:
  - `APP_SECURITY_USERNAME`
  - `APP_SECURITY_PASSWORD`
- Configure roles with:
  - `APP_SECURITY_ROLES` (comma-separated, default `USER,WRITER`)
  - `APP_SECURITY_WRITE_ROLE` (default `WRITER`)

Example secure run:
```bash
cd contract-service
APP_SECURITY_ENABLED=true \
APP_SECURITY_USERNAME=demo \
APP_SECURITY_PASSWORD=demo-secret \
APP_SECURITY_ROLES=USER,WRITER \
mvn spring-boot:run
```

## Main Docker Demo (PostgreSQL)

Fresh-machine quickstart (<10 min):

```bash
cd /path/to/data-contract-governance
bash scripts/demo/run-compose-demo.sh
```

Low-bandwidth startup (hotspot/4G):

```bash
DCG_COMPOSE_PULL_POLICY=never \
DCG_COMPOSE_BUILD_ENABLED=false \
bash scripts/demo/run-compose-demo.sh
```

Manual path:

```bash
cp config/compose.live-demo.env.example .env.live-demo
# Edit .env.live-demo and set DCG_DB_* and DCG_APP_* credentials.
docker compose --env-file .env.live-demo -f docker-compose.yml up --build -d
curl -fsS http://localhost:8080/actuator/health
```

Stop:
```bash
docker compose --env-file .env.live-demo -f docker-compose.yml down
```

## S3 Artifact Demo Script
For Week 13 S3 beta artifact backend testing, use:

```bash
cd /path/to/data-contract-governance
scripts/aws/s3-artifact-demo.sh setup --profile dcg-s3
scripts/aws/s3-artifact-demo.sh seed-contract
scripts/aws/s3-artifact-demo.sh verify
scripts/aws/s3-artifact-demo.sh cleanup --yes
```

Full beta runbook: [Week 13 S3 Beta Stabilization Runbook](docs/week13-s3-beta-runbook.md)

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

More guides:

- [Local Quickstart](quickstart-local.md)
- [Demo Walkthrough](docs/demo-walkthrough.md)
- [CLI Walkthrough](docs/cli-walkthrough.md)
- [Architecture v4](docs/Architecture-v4.md)
- [AWS Account Safety](docs/aws-account-safety.md)

## Sample Contracts
- [orders.created metadata](contracts/orders.created/metadata.yaml)
- [orders.created v1](contracts/orders.created/v1.json)
- [orders.created v2](contracts/orders.created/v2.json)

## Project Docs
- [Requirements](docs/Requirements.md)
- [System Design](docs/SystemDesign.md)
- [Architecture v4](docs/Architecture-v4.md)
- [Architecture v3](docs/Architecture-v3.md)
- [Architecture FAQ](docs/Architecture-FAQ.md)
- [Release and planning archive](docs/version4-production-readiness-release-plan.md)
- [Architecture Decisions](adr/ArchitectureDecisionRecord.md)
- [Week 3 Storage Interface Spec](docs/week3-storage-interface-spec.md)
- [Week 3 DB Compatibility Test Plan](docs/week3-db-compatibility-test-plan.md)
- [Week 4 Postgres Readiness Checklist](docs/week4-postgres-readiness-checklist.md)
- [Postgres Backup and Restore Runbook](docs/postgres-backup-restore-runbook.md)
- [Week 5 SQLite Prod-Lite Checklist](docs/week5-sqlite-prod-lite-checklist.md)
- [SQLite Prod-Lite Runbook](docs/sqlite-prod-lite-runbook.md)
- [Week 6 Docker Compose Production Baseline](docs/week6-docker-compose-production-baseline.md)
- [Week 7 Feedback Log Template](docs/week7-feedback-log-template.md)
- [Local Quickstart](quickstart-local.md)
- [Demo Walkthrough](docs/demo-walkthrough.md)
- [Week 7 Exit Checklist](docs/week7-exit-checklist.md)
- [Week 8 Stabilization Checklist](docs/week8-stabilization-checklist.md)
- [Week 8 MySQL Phase 2 Design](docs/week8-mysql-phase2-design.md)
- [Week 9 MySQL Implementation Checklist](docs/week9-mysql-implementation-checklist.md)
- [Week 10 MySQL Beta Stabilization](docs/week10-mysql-beta-stabilization.md)
- [Week 11 S3 Artifact RFC](docs/week11-s3-artifact-rfc.md)
- [Week 12 S3 Implementation Checklist](docs/week12-s3-implementation-checklist.md)
- [Week 13 S3 Beta Stabilization Runbook](docs/week13-s3-beta-runbook.md)
- [Week 13 S3 Beta Launch Post](docs/week13-s3-beta-launch-post.md)
- [Week 13 S3 Beta User Onboarding Session](docs/week13-s3-beta-onboarding-session.md)
- [AWS Account Safety](docs/aws-account-safety.md)
