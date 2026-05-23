# Week 13: S3 Beta Stabilization Runbook

- Plan ID: `PLAN-2026-W13-S3-BETA`
- Scope: Docker-only smoke testing, S3 beta hardening, operator docs, onboarding-ready curls
- Exit target: `S3 beta`

## 1. Docker Credentials

The compose stack uses HTTP Basic auth when `APP_SECURITY_ENABLED=true`.

- App UI/API username: `admin`
- App UI/API password: `change-me`
- UI: `http://localhost:8080/ui`
- Swagger: `http://localhost:8080/swagger-ui/index.html`

Postgres runs in Docker and is separate from local Postgres or pgAdmin state.

- Host: `localhost`
- Port: `5432`
- Database: `contracts`
- Username: `contracts_app`
- Password: `change-me`
- Schema: `dcg_prod`

If pgAdmin is broken locally, inspect the Docker database directly:

```bash
docker exec dcg-postgres psql -U contracts_app -d contracts \
  -c "select count(*) as check_runs from dcg_prod.check_runs;" \
  -c "select count(*) as logs from dcg_prod.check_run_logs;" \
  -c "select count(*) as audit_logs from dcg_prod.audit_logs;"
```

## 2. Start Docker Stack

```bash
cp .env.compose.example .env
docker compose --env-file .env -f docker-compose.yml up --build -d
docker compose --env-file .env -f docker-compose.yml ps
```

Health is intentionally public:

```bash
curl -fsS http://localhost:8080/actuator/health
```

## 3. Docker API Smoke Curls

```bash
export BASE_URL="http://localhost:8080"
export DCG_AUTH_USER="${DCG_AUTH_USER:-admin}"
export DCG_AUTH_PASSWORD="${DCG_AUTH_PASSWORD:-change-me}"
export CONTRACT_ID="payments.completed.$(date +%s)"
```

Submit a check against the seeded sample contract:

```bash
RUN_ID="$(
  curl -fsS -u "$DCG_AUTH_USER:$DCG_AUTH_PASSWORD" \
    -X POST "$BASE_URL/checks" \
    -H "Content-Type: application/json" \
    -d '{
      "contractId": "orders.created",
      "baseVersion": "v1",
      "candidateVersion": "v2",
      "mode": "BACKWARD",
      "commitSha": "docker-check-001",
      "triggeredBy": "docker-curl"
    }' \
  | sed -n 's/.*"runId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
)"
echo "$RUN_ID"
```

Fetch the check and logs:

```bash
curl -fsS -u "$DCG_AUTH_USER:$DCG_AUTH_PASSWORD" "$BASE_URL/checks/$RUN_ID"
curl -fsS -u "$DCG_AUTH_USER:$DCG_AUTH_PASSWORD" "$BASE_URL/checks/$RUN_ID/logs"
```

Create a new contract:

```bash
curl -fsS -u "$DCG_AUTH_USER:$DCG_AUTH_PASSWORD" \
  -X POST "$BASE_URL/contracts" \
  -H "Content-Type: application/json" \
  -d "{
    \"contractId\": \"$CONTRACT_ID\",
    \"ownerTeam\": \"payments-platform\",
    \"domain\": \"finance\",
    \"compatibilityMode\": \"BACKWARD\",
    \"policyPack\": \"baseline\",
    \"initialVersion\": \"v1\",
    \"schema\": {
      \"type\": \"object\",
      \"properties\": {
        \"paymentId\": { \"type\": \"string\" },
        \"amount\": { \"type\": \"number\" },
        \"currency\": { \"type\": \"string\" }
      },
      \"required\": [\"paymentId\", \"amount\"]
    }
  }"
```

Add a compatible version and read it back:

```bash
curl -fsS -u "$DCG_AUTH_USER:$DCG_AUTH_PASSWORD" \
  -X POST "$BASE_URL/contracts/$CONTRACT_ID/versions" \
  -H "Content-Type: application/json" \
  -d '{
    "version": "v2",
    "schema": {
      "type": "object",
      "properties": {
        "paymentId": { "type": "string" },
        "amount": { "type": "number" },
        "currency": { "type": "string" },
        "region": { "type": "string" }
      },
      "required": ["paymentId", "amount"]
    }
  }'

curl -fsS -u "$DCG_AUTH_USER:$DCG_AUTH_PASSWORD" "$BASE_URL/contracts/$CONTRACT_ID"
curl -fsS -u "$DCG_AUTH_USER:$DCG_AUTH_PASSWORD" "$BASE_URL/contracts/$CONTRACT_ID/versions/v2"
```

## 4. S3 Beta Smoke

Create and harden a tiny S3 test bucket from the host:

```bash
scripts/aws/s3-artifact-demo.sh setup --profile dcg-s3 --region ap-south-1
source /tmp/dcg-s3-demo.env
```

The setup command enables Block Public Access, bucket-owner-enforced object ownership, SSE-S3 default encryption, and bucket versioning.

Start the same Docker service against S3. Provide AWS credentials to the container through environment variables or a runtime IAM role; do not commit secrets.

```bash
export CONTRACTS_ROOT="/tmp/dcg-contracts-cache"
export CONTRACTS_ARTIFACT_BACKEND="s3"
export CONTRACTS_ARTIFACT_S3_BUCKET="$DCG_S3_BUCKET"
export CONTRACTS_ARTIFACT_S3_REGION="$AWS_REGION"
export CONTRACTS_ARTIFACT_S3_PREFIX="$DCG_S3_PREFIX"
export CONTRACTS_ARTIFACT_S3_FALLBACK_ENABLED="false"
export CONTRACTS_ARTIFACT_S3_LOCAL_CACHE_ROOT="/tmp/dcg-contracts-cache"
export CONTRACTS_ARTIFACT_S3_SERVER_SIDE_ENCRYPTION="AES256"

docker compose --env-file .env -f docker-compose.yml up --build -d
curl -fsS "$DCG_SERVICE_URL/actuator/health"
scripts/aws/s3-artifact-demo.sh seed-contract
scripts/aws/s3-artifact-demo.sh verify
```

Expected S3 artifact keys:

```text
contracts/<contract-id>/metadata.yaml
contracts/<contract-id>/versions/v1/schema.json
contracts/<contract-id>/versions/v1/schema.sha256
contracts/<contract-id>/versions/v2/schema.json
contracts/<contract-id>/versions/v2/schema.sha256
```

Cleanup after the smoke:

```bash
scripts/aws/s3-artifact-demo.sh cleanup --yes
```

## 5. Weekly Success Metrics

Track these every week during beta:

- Product: activated users, returning users, time-to-first-check
- Quality: test pass rate, escaped defects, migration failures
- Reliability: p95 API latency, queue delay, error rate
- Community: stars, discussions, external contributors, docs clarity feedback
