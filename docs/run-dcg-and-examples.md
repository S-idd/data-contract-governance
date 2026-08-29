# Run DCG and its examples

`scripts/dcg.sh` is the entry point for the supported local demos. It does not run every flow in
one command because the Compose demos share port `8080`, some flows require external credentials,
and recovery drills intentionally create disposable infrastructure.

Start with:

```bash
bash scripts/dcg.sh sequence
```

## Recommended sequence

| Step | Command | Expected output |
| --- | --- | --- |
| 1 | `bash scripts/dcg.sh test` | Full Maven test results. |
| 2 | `bash scripts/dcg.sh cli-pass` | CLI compatibility `PASS`. |
| 3 | `bash scripts/dcg.sh cli-breaking` | Intentional CLI failure with breaking changes; the launcher treats this as a successful demonstration. |
| 4 | `bash scripts/dcg.sh postgres` | PostgreSQL-backed health URL, UI, Swagger UI, and a sample check URL. |
| 5 | `bash scripts/dcg.sh stop postgres` | Stops the PostgreSQL Compose demo without deleting its volume. |
| 6 | `bash scripts/dcg.sh sqlite` | SQLite-backed health URL, UI, and sample check URL. |
| 7 | `bash scripts/dcg.sh stop sqlite` | Stops the SQLite Compose demo. |
| 8 | `bash scripts/dcg.sh mysql` | MySQL 8.4 beta demo health URL, UI, and sample check URL. |
| 9 | `bash scripts/dcg.sh stop mysql` | Stops the MySQL Compose demo. |
| 10 | **Project: `examples/spring-boot-realworld-demo`** — `bash scripts/dcg.sh example-v4` | Prints the three-terminal Spring Boot, SDK, UI, and webhook rehearsal. |

The launcher creates local `.env.live-demo`, `.env.sqlite-demo`, or `.env.mysql-demo` only if it
does not already exist. Each is ignored by Git and restricted to the local user. It does not read
or copy a root/config environment file. Delete a local demo file only when you intentionally want
new local demo credentials and a fresh database initialization.

## Optional flows

### S3 artifact beta

Run `bash scripts/dcg.sh s3`. On first use it creates an ignored `.env.s3-beta` with placeholders
and stops. Supply a bucket, region, and least-privilege AWS credentials yourself, then rerun it.
This remains a beta integration and must not use production credentials for a demo.

### Recovery drills

```bash
bash scripts/dcg.sh recovery postgres
bash scripts/dcg.sh recovery sqlite
bash scripts/dcg.sh recovery mysql
bash scripts/dcg.sh recovery s3
```

These are isolated drills, not a replacement for managed-provider backup, PITR, or failover
validation. The MySQL drill demonstrates application reconnect, idempotent retry, and restore;
it does not prove managed primary-to-replica failover.

### Capacity and deployment checks

```bash
bash scripts/dcg.sh benchmark
bash scripts/dcg.sh private-verify
```

The benchmark needs reachable local PostgreSQL and MySQL instances with the credentials described
by its script. `private-verify` is a static manifest check; it does not deploy to a cluster.

## Spring Boot examples

### Project: `examples/spring-boot-realworld-demo`

```bash
bash scripts/dcg.sh example-v4
```

This is the complete current V4 rehearsal: order API, contract-service, SDK submissions,
compatible and intentionally breaking checks, and webhook delivery.

### Project: `examples/dcg-spring-boot-realworld-demo`

```bash
bash scripts/dcg.sh example-starter
```

This independent validation-starter demo shows runtime payload validation as well as CLI/API
compatibility checks.

Use separate terminals for long-running services. Stop a Compose database demo before starting a
Spring Boot example that uses port `8080`.
