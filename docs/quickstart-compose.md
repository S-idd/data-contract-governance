# Quickstart (Docker Compose, <10 Minutes)

This is the fastest "fresh machine" path for running Data Contract Governance with:

- `contract-service`
- PostgreSQL
- sample contracts mounted from `./contracts`

This is a reproducible **local demo** only. It publishes ports to the local host and uses
demo credentials from an ignored local environment file; it is not a production deployment.

## 1) Prerequisites

1. Docker Desktop is installed and running.
2. You are in the repository root.

## 2) One-Command Start

```bash
cd /path/to/data-contract-governance
bash scripts/demo/run-compose-demo.sh
```

What the script does:

1. Creates local-only `.env.live-demo` from `config/compose.live-demo.env.example` if missing.
2. Builds and starts the compose stack.
3. Waits for `/actuator/health`.
4. Submits a sample compatibility check (`orders.created`, `v1 -> v2`).
5. Prints UI and API URLs.

Expected first run time on a fresh machine:

- 4 to 10 minutes (depends on image pull/build speed).

Low-bandwidth mode (recommended on hotspot/4G):

```bash
DCG_COMPOSE_PULL_POLICY=never \
DCG_COMPOSE_BUILD_ENABLED=false \
bash scripts/demo/run-compose-demo.sh
```

Notes:

- `DCG_COMPOSE_PULL_POLICY=never` avoids pulling newer images.
- `DCG_COMPOSE_BUILD_ENABLED=false` skips local rebuild during startup.

## 3) Open the App

- UI: `http://localhost:8080/ui`
- Swagger: `http://localhost:8080/swagger-ui/index.html`

The initial local-demo credentials are in `.env.live-demo`, created from `config/compose.live-demo.env.example`:

- Username: `dcg-compose-admin`
- Password: `dcg-compose-demo-password`

## 4) Manual Path (if you prefer explicit commands)

```bash
cp config/compose.live-demo.env.example .env.live-demo
# Edit .env.live-demo and set your DCG_DB_* and DCG_APP_* values.
docker compose --env-file .env.live-demo -f docker-compose.yml up --build -d
curl -fsS http://localhost:8080/actuator/health
```

Submit a sample check run:

```bash
curl -fsS -u dcg-compose-admin:dcg-compose-demo-password \
  -H "Content-Type: application/json" \
  -d '{
    "contractId":"orders.created",
    "baseVersion":"v1",
    "candidateVersion":"v2",
    "mode":"BACKWARD",
    "commitSha":"compose-manual",
    "triggeredBy":"compose-quickstart"
  }' \
  http://localhost:8080/checks
```

## 5) Stop

```bash
docker compose --env-file .env.live-demo -f docker-compose.yml down
```

Reset DB volume too:

```bash
docker compose --env-file .env.live-demo -f docker-compose.yml down -v
```

## 6) Reproducibility check

Before changing the Compose files, validate that Docker can resolve the exact stack without
starting it:

```bash
docker compose --env-file .env.live-demo -f docker-compose.yml config >/dev/null
```

Supported tool and database versions are listed in the [support policy](support-policy.md).
For production deployment constraints, including MySQL provider validation, read
[production limitations](production-limitations.md).
