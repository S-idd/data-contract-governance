# Postgres Backup and Restore Runbook (DCG)

- Last updated: `2026-03-31`
- Scope: `contracts` database, `dcg_dev` style per-environment schemas

## 1. Preconditions

1. PostgreSQL service is reachable.
2. You have a role with backup and restore privileges.
3. `pg_dump`, `pg_restore`, and `psql` are installed.

Required environment variables:

```bash
export PGHOST=localhost
export PGPORT=5432
export PGDATABASE=contracts
export PGUSER=<postgres_user>
export PGPASSWORD=<postgres_password>
export DCG_SCHEMA=dcg_dev
```

## 2. Backup

Create a timestamped backup directory:

```bash
mkdir -p backups
STAMP="$(date +%Y%m%d_%H%M%S)"
```

Schema-only backup:

```bash
pg_dump \
  --format=custom \
  --schema="$DCG_SCHEMA" \
  --file="backups/dcg_${DCG_SCHEMA}_${STAMP}.dump"
```

Optional plain-SQL backup for human inspection:

```bash
pg_dump \
  --format=plain \
  --schema="$DCG_SCHEMA" \
  --file="backups/dcg_${DCG_SCHEMA}_${STAMP}.sql"
```

## 3. Restore Drill (Required)

Create a temporary restore database:

```bash
RESTORE_DB="contracts_restore_${STAMP}"
createdb -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" "$RESTORE_DB"
```

Restore backup:

```bash
pg_restore \
  --verbose \
  --clean \
  --if-exists \
  --dbname="$RESTORE_DB" \
  "backups/dcg_${DCG_SCHEMA}_${STAMP}.dump"
```

Verify restored objects:

```bash
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$RESTORE_DB" -c \
  "select schemaname, tablename from pg_tables where schemaname = '$DCG_SCHEMA' order by tablename;"

psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$RESTORE_DB" -c \
  "select count(*) as check_runs from $DCG_SCHEMA.check_runs;"

psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$RESTORE_DB" -c \
  "select version, description, success from $DCG_SCHEMA.flyway_schema_history order by installed_rank;"
```

Cleanup restore drill database:

```bash
dropdb -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" "$RESTORE_DB"
```

## 4. Migration Safety During Release

1. Always run migrations with `checks.db.fail-fast-startup=true` in production.
2. Always pin `currentSchema` in JDBC URL and set `CHECKS_DB_EXPECTED_SCHEMA` to the same value.
3. Roll forward with a new migration for fixes; do not edit old applied migrations.

Example startup settings:

```bash
export SPRING_PROFILES_ACTIVE=prod
export CHECKS_DB_URL="jdbc:postgresql://db.internal:5432/contracts?currentSchema=dcg_prod"
export CHECKS_DB_EXPECTED_SCHEMA="dcg_prod"
```

## 5. Incident Rollback Strategy

DCG uses Flyway versioned migrations. Preferred rollback is:

1. Stop deploy rollout.
2. Restore from latest successful backup to a recovery target.
3. Validate service health and key queries.
4. Deploy a forward-fix migration before reattempting rollout.

This project does not use destructive Flyway undo migrations; rollback is operational (backup/restore) plus forward fix.
