# Week 6: Docker/Compose Production Baseline

- Week: `6`
- Date: `2026-04-24`
- Status: `In progress (implementation complete, validation pending)`
- Exit target: `Fresh machine compose run works in <10 minutes`

This baseline is **production-leaning**, not just a demo image:

1. Multi-stage build with Java 21 runtime.
2. Non-root container user.
3. Service + Postgres health checks.
4. Persistent Postgres volume.
5. Security enabled by default in compose.
6. Hardened service container runtime (`no-new-privileges`, `cap_drop`, read-only root FS).

## 1) Prerequisites

1. Docker Desktop (running).
2. Repo checked out locally.

## 2) Fastest Fresh-Machine Path (<10 Minutes)

```bash
cd /path/to/data-contract-governance
bash scripts/demo/run-compose-demo.sh
```

The script:

1. Creates local-only `.env.live-demo` from `config/compose.live-demo.env.example` if missing.
2. Runs `docker compose up --build -d`.
3. Waits for service health.
4. Submits a sample check run using HTTP Basic auth.
5. Prints UI/API/run URLs.

For unstable/slow internet, run in low-bandwidth mode:

```bash
DCG_COMPOSE_PULL_POLICY=never \
DCG_COMPOSE_BUILD_ENABLED=false \
bash scripts/demo/run-compose-demo.sh
```

## 3) Manual Path

```bash
cp config/compose.live-demo.env.example .env.live-demo
chmod 600 .env.live-demo
docker compose --env-file .env.live-demo -f docker-compose.yml up --build -d
```

Wait for healthy containers:

```bash
docker compose --env-file .env.live-demo -f docker-compose.yml ps
```

## 4) Verify Service Health

```bash
curl -fsS http://localhost:8080/actuator/health
```

Open UI:

- `http://localhost:8080/ui`

Demo-only credentials (from `config/compose.live-demo.env.example`):

- username: `dcg-compose-admin`
- password: `dcg-compose-demo-password`

## 5) Verify Write Path

```bash
curl -u dcg-compose-admin:dcg-compose-demo-password \
  -H "Content-Type: application/json" \
  -d '{"contractId":"orders.created","baseVersion":"v1","candidateVersion":"v2","mode":"BACKWARD","commitSha":"compose-local","triggeredBy":"compose"}' \
  http://localhost:8080/checks
```

## 6) Shutdown

```bash
docker compose --env-file .env.live-demo -f docker-compose.yml down
```

To also remove DB state:

```bash
docker compose --env-file .env.live-demo -f docker-compose.yml down -v
```

## 7) Runtime Hardening in Compose

Already enabled in the service container:

1. `security_opt: no-new-privileges:true`
2. `cap_drop: [ALL]`
3. `read_only: true` with `tmpfs` at `/tmp`
4. Explicit Java memory percentages via `JAVA_OPTS`

## 8) Production Rollout Gaps (must-do before external prod)

1. Move secrets to a secret manager (do not use `.env` defaults).
2. Use managed Postgres with TLS and strict SSL:
   - `CHECKS_DB_ENFORCE_SECURE_POSTGRES=true`
   - `CHECKS_DB_SSL_ENABLED=true`
   - `CHECKS_DB_SSL_MODE=verify-full`
3. Remove host Postgres port exposure if external DB clients are not needed.
4. Publish signed, versioned images to registry (no local tags for prod).
5. Add backup automation and scheduled restore drills.

## 9) Week 6 Artifacts

1. `.dockerignore`
2. `docker/contract-service.Dockerfile`
3. `docker-compose.yml`
4. `config/compose.live-demo.env.example`
5. `scripts/demo/run-compose-demo.sh`
6. `docs/quickstart-compose.md`
