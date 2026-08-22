# Database Compatibility Live Demo

Use this guide to show where DCG stores data and how its metadata store works with PostgreSQL, SQLite, and MySQL.

## What DCG Connects To

DCG does not connect to every SQL database at once. Each running DCG instance is configured with **one metadata database**. The same metadata-store interface and migration process support these database engines:

| Database | DCG support level | Recommended use in the demo |
| --- | --- | --- |
| PostgreSQL | Production standard | Main live demo |
| SQLite | Local development and single-node production-lite | Short portability demonstration |
| MySQL | Beta | Compatibility demonstration; do not present as GA or the only database for a critical deployment |

MySQL, PostgreSQL, and SQLite are all SQL databases, but they use different SQL dialects. DCG selects the right JDBC driver and Flyway migration chain for the configured JDBC URL. It is not a generic claim that every SQL database is supported.

## What Is Stored Where

```text
DCG service
├── Metadata database (PostgreSQL, SQLite, or MySQL)
│   ├── check runs and their logs
│   ├── audit records
│   ├── notification-delivery records
│   └── Flyway migration history
└── Artifact store (filesystem by default, S3 in the separate S3 beta)
    ├── metadata.yaml
    └── immutable versioned schema files
```

This distinction is the important presentation point: database choice changes where operational metadata is stored; it does not change the contract API or compatibility-check workflow.

## Before the Demo

From the repository root, create the local credential file. Do not put real passwords in `config/`; files there are committed templates.

```bash
cp config/compose.live-demo.env.example .env.live-demo
chmod 600 .env.live-demo
```

Edit `.env.live-demo` and choose the credentials used by the main PostgreSQL demo:

```env
DCG_DB_USERNAME=your_database_user
DCG_DB_PASSWORD=your_database_password
DCG_APP_USERNAME=your_demo_user
DCG_APP_PASSWORD=your_demo_password
```

The main PostgreSQL script reads `.env.live-demo` by default. To use another local PostgreSQL demo file, explicitly set `DCG_COMPOSE_ENV_FILE=<path-to-your-local-env-file>`. Use the S3 beta launcher separately; do not mix its configuration with the normal database demo.

## Part 1: Main PostgreSQL Demo

Start the main DCG application and PostgreSQL:

```bash
bash scripts/demo/run-compose-demo.sh
```

The script authenticates to DCG with `DCG_APP_USERNAME` and `DCG_APP_PASSWORD`, submits a compatibility check, and prints the UI URL. Open `http://localhost:8080/ui` and show the completed check run.

Then, from another terminal, inspect the same metadata in PostgreSQL. This proves that the UI/API result is durable database metadata, not only an in-memory screen result:

```bash
set -a
source .env.live-demo
set +a

docker exec dcg-postgres \
  psql -U "$DCG_DB_USERNAME" -d contracts \
  -c "select run_id, contract_id, status, created_at from dcg_prod.check_runs order by created_at desc limit 10;"

docker exec dcg-postgres \
  psql -U "$DCG_DB_USERNAME" -d contracts \
  -c "select version, description, success from dcg_prod.flyway_schema_history order by installed_rank;"
```

Presenter message: **PostgreSQL is DCG's production-standard metadata database. DCG writes the check history, logs, audit data, and migration history there.**

Do not change `DCG_DB_USERNAME` or `DCG_DB_PASSWORD` after PostgreSQL has initialized its named volume. For a disposable local reset, run `docker compose --env-file .env.live-demo -f docker-compose.yml down -v`, then start the demo again. This deletes the local demo database volume.

## Part 2: SQLite Portability Demo

SQLite has no server process: it stores the metadata in one local database file. Run the main DCG service in Docker with its SQLite Docker volume:

```bash
bash scripts/demo/run-sqlite-compose-demo.sh
```

The script creates local-only `.env.sqlite-demo` from `config/compose.sqlite-demo.env.example` if needed, starts the full UI/API with the `sqlite-prod-lite` profile, and submits a check run. The SQLite database is persisted in Docker volume `dcg-sqlite-demo_dcg_sqlite_data` at `/var/lib/dcg/checks.db` inside the service container.

Inspect the live metadata target:

```bash
curl -fsS http://localhost:8080/actuator/health
docker exec dcg-sqlite-service ls -lh /var/lib/dcg/checks.db
```

Presenter message: **The application workflow is unchanged; only the metadata-store connection changes. SQLite is suitable for local use and deliberate single-node production-lite deployments, not multi-node high availability.**

## Part 3: MySQL Compatibility Demo

Run the main DCG service and MySQL in Docker:

```bash
bash scripts/demo/run-mysql-compose-demo.sh
```

The script creates local-only `.env.mysql-demo` from `config/compose.mysql-demo.env.example` if needed, starts MySQL 8.4 and the full DCG UI/API, and submits a check run. Show the same metadata directly in MySQL:

```bash
docker exec dcg-mysql sh -c \
  'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -N -B contracts \
  -e "select contract_id, status, count(*) from check_runs group by contract_id, status;"'
```

Presenter message: **DCG supports MySQL as a beta metadata backend through the same API and metadata-store boundary. It is demonstrated as compatible, but not yet claimed as production-standard or GA.**

## Closing Summary

Use this exact statement in the demo:

> DCG uses PostgreSQL, SQLite, or MySQL to store operational metadata. PostgreSQL is the production-standard option, SQLite is the single-node production-lite option, and MySQL is currently beta. Contract definitions and schema versions are stored separately in the filesystem by default, or in S3 for the separate S3 beta.

## Cleanup

Stop the currently selected Docker demo stack but retain its data. For example, the main PostgreSQL stack:

```bash
docker compose --env-file .env.live-demo -f docker-compose.yml down
```

For the Docker SQLite and MySQL demos, replace the command with the `Stop:` command printed by each launcher. Their volumes are retained unless you explicitly use `down -v`.
