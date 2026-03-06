# Local Development Rules (No Docker)

## Hard Constraint
- Docker and Testcontainers are not allowed for this project.
- All development, testing, and demos must run on local host services only.

## Required Local Services
- PostgreSQL running locally (example: `localhost:5432`).
- SQLite available via JDBC (file-based, no external service).

## Standard Local Postgres Defaults
- Host: `localhost`
- Port: `5432`
- Database (default for tests): `postgres`
- Username/password must be provided from local env or JVM properties.

## Local Environment Variables
- `TEST_POSTGRES_JDBC_URL` (example: `jdbc:postgresql://localhost:5432/postgres`)
- `TEST_POSTGRES_USERNAME`
- `TEST_POSTGRES_PASSWORD`

Optional inline JVM properties (equivalent):
- `-Dtest.postgres.jdbc-url=...`
- `-Dtest.postgres.username=...`
- `-Dtest.postgres.password=...`

## Allowed Test Strategy
- Unit tests:
  - must not require Docker.
  - may use local SQLite and local Postgres.
- Integration tests:
  - must run against local service + local Postgres/SQLite.
  - any auth/network failure scenario should be simulated locally (bad role, bad port, schema mismatch).

## Disallowed Patterns
- Any `org.testcontainers` dependency.
- Any test path that depends on container runtime.
- Any docs/commands that assume Docker is installed.

## Canonical Commands

Build:
```bash
mvn -pl contract-service -am -DskipTests test-compile
```

Run Postgres-path tests:
```bash
mvn -pl contract-service -am \
  -Dtest=CheckRunStorePostgresPathTest,CheckControllerPostgresSuccessIntegrationTest,CheckControllerPostgresAuthFailureIntegrationTest,CheckControllerPostgresNetworkFailureIntegrationTest,CheckControllerPostgresSchemaMismatchIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

Run full service tests:
```bash
mvn -pl contract-service -am -Dsurefire.failIfNoSpecifiedTests=false test
```

UI/security smoke (local):
```bash
cd contract-service
APP_UI_ENABLED=true APP_SECURITY_ENABLED=false mvn spring-boot:run
# open http://localhost:8080/ui
```

## Expected Log Behavior
- For failure-mode tests, ERROR logs are expected (auth/network/schema mismatch).
- Test success is determined by Maven summary:
  - `Failures: 0`
  - `Errors: 0`
  - `BUILD SUCCESS`

## Data and Secrets Rules
- Never commit real credentials.
- Never hardcode passwords in source files.
- Use env variables for local secrets.
- Sanitize JDBC URLs in logs when credentials are present.

## Operational Readiness Rules
- Every new backend behavior requires:
  - unit test
  - integration test
  - updated docs if config/endpoint behavior changes
- Every user-facing error path must return structured API error payloads.

## Definition of Local-Ready
- A developer can:
  - set three Postgres env vars,
  - run backend tests,
  - run the service locally,
  - access API/UI without Docker.
