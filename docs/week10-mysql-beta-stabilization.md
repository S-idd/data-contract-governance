# Week 10: MySQL Stabilization and Beta

- Plan ID: `PLAN-2026-W10-MYSQL-BETA`
- Date: `2026-05-03`
- Status: `Implemented and locally verified (2026-05-13)`
- Scope: `load smoke tests, migration rollback readiness, docs, examples, beta announcement`
- Exit target: `MySQL beta published`

## 1. Prerequisite Status

Week 8 is complete: the MySQL design doc is approved and captures SQL dialect gaps, Flyway routing, and transaction/locking behavior.

Week 9 is fully exited in this checkout after a provisioned local run completed with zero 3-DB matrix skips (`Tests run: 17, Failures: 0, Errors: 0, Skipped: 0` on 2026-05-13).

## 2. Stabilization Coverage Added

Always-on local tests:

```bash
./mvnw -pl contract-service -am \
  -Dtest=CheckRunStoreLoadSmokeTest,CheckRunStoreMigrationRollbackTest,ArtifactKeyStrategyTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

Coverage:

1. `CheckRunStoreLoadSmokeTest` queues, claims, logs, completes, filters, and pages 120 SQLite-backed check runs.
2. `CheckRunStoreMigrationRollbackTest` proves a backup/restore rollback path returns the check store to a known-good migrated state after schema damage.
3. `ArtifactKeyStrategyTest` covers the Week 11 S3-ready key strategy added during the stabilization pass.

## 3. MySQL Beta Verification

Provision local MySQL 8.0 or 8.4, then run:

```bash
export TEST_MYSQL_JDBC_URL="jdbc:mysql://localhost:3306/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
export TEST_MYSQL_USERNAME="<local_mysql_admin_or_test_user>"
export TEST_MYSQL_PASSWORD="<local_mysql_password>"

./mvnw -pl contract-service -am \
  -Dtest=CheckRunStoreMySqlContractTest,CheckRunStoreMySqlPathTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

3-DB matrix:

```bash
export TEST_POSTGRES_JDBC_URL="jdbc:postgresql://localhost:5432/contracts?currentSchema=dcg_dev"
export TEST_POSTGRES_USERNAME="<local_pg_user>"
export TEST_POSTGRES_PASSWORD="<local_pg_password>"

./mvnw -pl contract-service -am \
  -Dtest=CheckRunStoreSqliteContractTest,CheckRunStorePostgresContractTest,CheckRunStoreMySqlContractTest,CheckRunStorePostgresPathTest,CheckRunStoreMySqlPathTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

The beta exit requires `BUILD SUCCESS` and zero skips for provisioned MySQL tests.

## 4. Migration Rollback Runbook

This project uses forward-only Flyway SQL migrations. Operational rollback means restore the database to the last known-good snapshot, then redeploy the previously working app build.

Minimum rollback drill:

1. Take a DB backup immediately before migration.
2. Run app startup or CLI recording so Flyway applies migrations.
3. Validate `flyway_schema_history`, `/actuator/health`, `/checks`, and a write path.
4. If rollback is needed, stop writers, restore the backup, redeploy the previous app build, and verify health and check history.

MySQL example:

```bash
mysqldump --single-transaction --routines --triggers \
  --host localhost --user "$TEST_MYSQL_USERNAME" --password \
  contracts > backup-before-dcg-migration.sql
```

Restore example:

```bash
mysql --host localhost --user "$TEST_MYSQL_USERNAME" --password \
  contracts < backup-before-dcg-migration.sql
```

## 5. Beta Announcement

Short announcement:

```text
MySQL beta support is available for the check history store.

What is included:
- MySQL-specific Flyway migration chain.
- Service and CLI routing for jdbc:mysql URLs.
- Metadata contract tests and path/failure-mode tests.
- Local smoke and rollback-readiness tests.

Beta requirements:
- Use MySQL 8.0 or 8.4 for verification.
- Run the 3-DB matrix before treating the build as release-ready.
- Keep PostgreSQL and SQLite configuration unchanged unless you are intentionally switching backends.
```

## 6. Exit Checklist

- [x] Local load smoke test added.
- [x] Migration rollback readiness test added.
- [x] MySQL beta verification command documented.
- [x] Beta announcement copy drafted.
- [x] MySQL tests run with zero skips against provisioned local MySQL.
- [x] 3-DB matrix published with zero failures and zero targeted external-DB skips.
