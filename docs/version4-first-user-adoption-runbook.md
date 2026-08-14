# Version 4 First-User Adoption Runbook

- Plan ID: `PLAN-2026-V4-ADOPTION`
- Status: `Ready for sessions`
- Related plan: `docs/version4-production-readiness-release-plan.md`
- Feedback template: `docs/week7-feedback-log-template.md`

## 1. Presentation Scope

Use the Compose/PostgreSQL flow for a first presentation. It shows the normal product path with the least setup risk: health, contract discovery, asynchronous compatibility checks, audit-backed history, and policy enforcement.

Do not run every database adapter live in the main presentation. SQLite, PostgreSQL, MySQL, and S3 have dedicated recovery drills; explain that the metadata-store interface supports them, then run only the drill a participant specifically wants to evaluate. This keeps first-user feedback about the product instead of Docker setup noise.

Real AWS S3 is an optional separate session after the facilitator supplies a non-production AWS profile and region. Do not paste credentials into the terminal history, source tree, chat, or feedback log.

## 2. Clean Start

Run from the repository root. This removes only DCG Compose resources, including its PostgreSQL volume and locally built service image.

```bash
bash scripts/demo/reset-docker-demo.sh --yes
cp config/compose.env.example .env
```

The checked-in Compose credentials are demonstration-only:

```bash
export BASE_URL="http://localhost:8080"
export DCG_AUTH_USER="dcg-compose-admin"
export DCG_AUTH_PASSWORD="dcg-compose-demo-password"
```

## 3. Green Product Flow

Start the service and database. The helper waits for health and submits one passing sample check.

```bash
STARTED_AT="$(date +%s)"
bash scripts/demo/run-compose-demo.sh
HEALTH_AT="$(date +%s)"
printf 'Time to health check: %ss\n' "$((HEALTH_AT - STARTED_AT))"
```

Verify the service, contract catalog, and a fresh compatible check:

```bash
curl -fsS "$BASE_URL/actuator/health"
curl -fsS -u "$DCG_AUTH_USER:$DCG_AUTH_PASSWORD" "$BASE_URL/contracts"

RUN_ID="$(
  curl -fsS -u "$DCG_AUTH_USER:$DCG_AUTH_PASSWORD" \
    -H 'Content-Type: application/json' \
    -d '{
      "contractId": "orders.created",
      "baseVersion": "v1",
      "candidateVersion": "v2",
      "mode": "BACKWARD",
      "commitSha": "first-user-green-demo",
      "triggeredBy": "presentation"
    }' \
    "$BASE_URL/checks" \
  | sed -n 's/.*"runId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
)"
printf 'Check run: %s\n' "$RUN_ID"

until curl -fsS -u "$DCG_AUTH_USER:$DCG_AUTH_PASSWORD" "$BASE_URL/checks/$RUN_ID" \
  | grep -q '"status":"PASS"'; do sleep 1; done
curl -fsS -u "$DCG_AUTH_USER:$DCG_AUTH_PASSWORD" "$BASE_URL/checks/$RUN_ID"
curl -fsS -u "$DCG_AUTH_USER:$DCG_AUTH_PASSWORD" "$BASE_URL/checks/$RUN_ID/logs"
```

Open these pages while talking through the result:

```text
http://localhost:8080/ui
http://localhost:8080/ui/contracts/orders.created
http://localhost:8080/ui/checks/<run-id>
http://localhost:8080/swagger-ui/index.html
```

## 4. Specific Feature: Policy Enforcement

This request intentionally changes `orderId` from a string to an integer. It should be rejected with HTTP `422`; it does not create `v3`.

```bash
curl -sS -i -u "$DCG_AUTH_USER:$DCG_AUTH_PASSWORD" \
  -H 'Content-Type: application/json' \
  -X POST "$BASE_URL/contracts/orders.created/versions" \
  -d '{
    "version": "v3",
    "schema": {
      "type": "object",
      "properties": {
        "orderId": {"type": "integer"},
        "status": {"type": "string"}
      },
      "required": ["orderId", "status"]
    }
  }'
```

Confirm the contract still exposes only the compatible versions:

```bash
curl -fsS -u "$DCG_AUTH_USER:$DCG_AUTH_PASSWORD" \
  "$BASE_URL/contracts/orders.created/versions"
```

## 5. Adapter Evidence

Use these only when someone asks to inspect a backend. Each is isolated and removes its temporary resources after success.

```bash
./mvnw -pl contract-service -am package
bash scripts/demo/run-sqlite-recovery-drill.sh
bash scripts/demo/run-postgres-recovery-drill.sh
bash scripts/demo/run-mysql-recovery-drill.sh
bash scripts/demo/run-s3-recovery-drill.sh
```

The recommended product story is PostgreSQL for a shared deployment, SQLite for single-node production-lite, MySQL as Beta, and S3 as Beta artifact storage. S3 needs a real AWS clean smoke and final support decision before a GA claim.

## 6. Optional Real S3 Session

After a facilitator has configured a non-production AWS profile, run:

```bash
aws sts get-caller-identity --profile <profile>
scripts/aws/s3-artifact-demo.sh setup --profile <profile> --region <region>
source /tmp/dcg-s3-demo.env
scripts/aws/s3-artifact-demo.sh seed-contract
scripts/aws/s3-artifact-demo.sh verify
scripts/aws/s3-artifact-demo.sh cleanup --yes
```

Use `docs/week13-s3-beta-onboarding-session.md` for the full S3 agenda and feedback prompts.

## 7. Feedback And Cleanup

Capture one feedback record per participant before discussing fixes:

```bash
cp docs/week7-feedback-log-template.md "/tmp/dcg-feedback-<participant>.md"
```

Record the time to health check, time to first successful check, assistance needed, friction, adoption signal, and ranked follow-up actions. Do not record credentials or other secret values.

After the session:

```bash
bash scripts/demo/reset-docker-demo.sh --yes
```
