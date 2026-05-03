# Week 9: MySQL Implementation Checklist

- Plan ID: `PLAN-2026-W9-MYSQL-IMPLEMENTATION`
- Date: `2026-05-02`
- Status: `In Progress`
- Scope: `MySQL metadata adapter + integration/contract coverage`
- Exit target: `all tests green in 3-DB matrix`

## 1. Implementation Tasks

- [x] Add MySQL runtime dependencies for CLI and service.
- [x] Add MySQL-specific Flyway migration chain (`db/migration-mysql`, `V1..V6`).
- [x] Route service Flyway bootstrap by JDBC backend (`mysql` vs shared path).
- [x] Route CLI recorder Flyway bootstrap by JDBC backend.
- [x] Add MySQL metadata contract tests.
- [x] Add MySQL path/failure-mode tests (success, auth, network, schema-mismatch).
- [x] Run full validation matrix and capture results.

## 2. Test Environment Variables

```bash
export TEST_POSTGRES_JDBC_URL="jdbc:postgresql://localhost:5432/contracts?currentSchema=dcg_dev"
export TEST_POSTGRES_USERNAME="<your_pg_user>"
export TEST_POSTGRES_PASSWORD="<your_pg_password>"

export TEST_MYSQL_JDBC_URL="jdbc:mysql://localhost:3306/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
export TEST_MYSQL_USERNAME="<your_mysql_user>"
export TEST_MYSQL_PASSWORD="<your_mysql_password>"
```

Note:
1. Current Spring Boot 3.3.5 dependency line uses Flyway `10.10.0`.
2. For stable Week 9 verification, prefer MySQL `8.4` (or other Flyway-verified versions for this Flyway line).

## 3. Verification Commands

Full suite:

```bash
cd /path/to/data-contract-governance
./mvnw clean test
```

Targeted backend matrix:

```bash
cd /path/to/data-contract-governance
./mvnw -pl contract-service -am \
  -Dtest=CheckRunStoreSqliteContractTest,CheckRunStorePostgresContractTest,CheckRunStoreMySqlContractTest,CheckRunStorePostgresPathTest,CheckRunStoreMySqlPathTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

## 4. Exit Criteria

- [ ] SQLite tests pass with zero regressions.
- [ ] PostgreSQL tests pass with existing behavior unchanged.
- [ ] MySQL tests pass with zero skips on provisioned local MySQL.
- [ ] `BUILD SUCCESS` for full suite.

## 5. Validation Snapshot

Latest local run (2026-05-02):

```bash
./mvnw -pl contract-service,contract-cli -am test -Dsurefire.failIfNoSpecifiedTests=false
```

Observed result:

1. `BUILD SUCCESS`
2. `contract-service`: `Tests run: 81, Failures: 0, Errors: 0, Skipped: 15`
3. MySQL contract/path tests compiled and executed with explicit skip reasons when local MySQL was unavailable.
