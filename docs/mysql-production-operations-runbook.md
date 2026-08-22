# MySQL Production Operations Runbook

## Connection-pool budget

Set a connection allocation for DCG before deployment:

`CHECKS_DB_POOL_MAX_SIZE × CHECKS_DB_POOL_REPLICA_COUNT <= CHECKS_DB_POOL_DATABASE_CONNECTION_BUDGET`

The budget is the portion of MySQL `max_connections` assigned to DCG after reserving capacity for the managed service, monitoring, break-glass administration, backups, and migration. The production template uses two replicas × ten connections with a 24-connection DCG budget.

Hikari publishes Micrometer metrics, including acquisition latency and connection usage under the `hikaricp.connections.*` metric family. Alert before saturation on pending connections, acquisition timeout/error rate, and sustained active connections near the per-replica maximum. A release is not accepted until a load run records acquisition latency and returns all pools to zero active connections after work completes.

The production profile exposes Prometheus-format metrics at `/actuator/prometheus`. Health and info remain public for the platform probe; metrics require the configured operations role and the endpoint must be reachable only from the private scraper network. Start with [prometheus-mysql-alerts.yml](../config/observability/prometheus-mysql-alerts.yml), then bind provider-specific metrics for replication, disk, backup freshness, and PITR-log freshness before general availability.

## Separate database identities

The service uses `CHECKS_DB_USERNAME_ENV` and `CHECKS_DB_PASSWORD_ENV` at runtime. Flyway uses the distinct `CHECKS_DB_MIGRATION_USERNAME_ENV` and `CHECKS_DB_MIGRATION_PASSWORD_ENV` identity only during startup migration.

With `CHECKS_DB_ENFORCE_SEPARATE_MIGRATION_CREDENTIALS=true`, a MySQL deployment fails startup when migration credentials are absent or match the runtime identity. Provision accounts using [mysql-production-roles.sql.example](../config/mysql-production-roles.sql.example), restricted to the workload network identity rather than `%` where the platform supports it.

Before production rollout, capture `SHOW GRANTS` for both identities. The runtime account may have only `SELECT`, `INSERT`, `UPDATE`, and `DELETE` on the DCG schema; it must not have `ALTER`, `CREATE`, `DROP`, `INDEX`, or global grants. The migration account has the required schema DDL/DML privileges and must not be used by the service after migration.

## Backups, PITR, and isolated restore

Use a managed MySQL topology that provides encrypted automated backups, encrypted in-transit replication, point-in-time recovery from retained binary logs, and a separate-account or separate-project restore target. Configure retention to exceed the signed-off recovery-point objective (RPO), then record the provider, backup retention, binary-log/PITR retention, encryption key, and restore target in the release evidence.

Perform every restore into a new target first. Record:

1. Drill start/end time and target timestamp.
2. Requested recovery point, recovered point, and calculated RPO.
3. Time until the target is queryable and the DCG application-level validation passes (RTO).
4. Successful Flyway history, table/index verification, and known check-run lookup.
5. Operator, provider/region, backup identity, and promotion decision.

For local, provider-independent verification, build the service and run:

```bash
./mvnw -pl contract-service -am package
bash scripts/demo/run-mysql-recovery-drill.sh
```

The drill proves logical backup, restore to a separate database, Flyway history, and an application read. It is not evidence that a managed provider has encrypted backups or PITR enabled; capture that provider configuration separately before production promotion.

## PostgreSQL/MySQL capacity baseline

Run the reproducible local comparison from the repository root:

```bash
DCG_BENCHMARK_OPERATIONS=500 \
  bash scripts/perf/run-database-side-by-side-benchmark.sh
```

The harness starts disposable PostgreSQL 16 and MySQL 8.4 containers, applies the production migration chain to both, then performs the same queued-run write and indexed paginated-read workload. It records throughput, p95/p99 operation latency, and the required no-leak pool snapshot in `docs/verification/database-side-by-side-latest.md`. It is deliberately an explicit P1 operation—not a normal unit test—and must be repeated against the chosen production-like topology with CPU, I/O, error, lock/deadlock, and query-plan capture before using it for capacity acceptance.
