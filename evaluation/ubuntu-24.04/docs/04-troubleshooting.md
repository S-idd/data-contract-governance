# Troubleshooting

## `verify-install.sh` reports a checksum failure

Do not run the affected executable. Re-extract the original archive and verify again. The writable `workspace/` and generated `docker/.env` are intentionally excluded from the immutable bundle manifest.

## Java is missing or the wrong version

```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk
java -version
```

## Docker reports a Podman template error

This guide uses `docker compose`, not `docker info --format ...`. Install Docker Compose v2 or `podman-compose`. Do not use Docker-specific Go templates with Podman's compatibility wrapper.

## Port 8080, 8081, or 8090 is occupied

```bash
ss -ltnp | grep -E ':(8080|8081|8090)\b'
./scripts/stop-iems.sh
./scripts/stop-dcg.sh
```

The stop scripts signal only processes whose command lines match this bundle.

## DCG starts but AI is unavailable

```bash
./scripts/status-dcg.sh
tail -n 100 workspace/logs/rust.log
```

The deterministic result remains authoritative. Confirm the archive architecture is Linux x86-64 and that port 8081 is free.

## IEMS exits before becoming healthy

```bash
tail -n 150 workspace/state/iems/application.log
```

For PostgreSQL or MySQL, confirm `database-up.sh` completed and both containers are healthy.

## A contract check returns exit 1

That is the expected rejection code for a breaking candidate. Read the reported breaking changes. Exit 2 indicates an execution or persistence problem.

## Preserve evidence

Copy `workspace/` to a private location and generate checksums. It may contain schemas, history, generated passwords, and application data; do not upload it publicly.

For the complete runner, every command has a numbered private log beside `results.json`. An `INCOMPLETE` result records the exact failed check and attempts scoped cleanup before returning.
