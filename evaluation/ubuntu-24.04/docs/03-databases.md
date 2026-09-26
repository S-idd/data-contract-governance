# SQLite, PostgreSQL, and MySQL

SQLite requires no container. `check-contract.sh ... sqlite` writes DCG check history to `workspace/checks.db`; `start-iems.sh sqlite` writes IEMS data to `workspace/iems-data/iems.db`.

`database-up.sh` starts pinned major versions `postgres:16` and `mysql:8.0`, bound only to loopback ports `54329` and `33069`. It creates separate application and governance databases:

| Engine | DCG check history | IEMS application |
|---|---|---|
| PostgreSQL | `dcg_history` | `iems_app` |
| MySQL | `dcg_history` | `iems_app` |

Generated credentials are stored with restrictive permissions in `docker/.env`. CLI commands reference environment variable names, so passwords do not appear in command output or process arguments.

Check container state:

```bash
docker compose --env-file docker/.env -f docker/compose.yaml ps
```

Stop containers while preserving demo data:

```bash
./scripts/database-down.sh
```

Remove only this bundle's disposable container volumes:

```bash
./scripts/database-reset-demo-data.sh --confirm-disposable-data
```

That reset does not remove `workspace/`, the accepted package, IEMS JAR, external databases, or unrelated containers.
