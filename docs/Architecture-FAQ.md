# Architecture FAQ - Data Contract Governance (v3)

- Version: `v3`
- Date: `2026-03-25`
- Status: `Planning and communication reference`
- Related docs:
  - [Architecture v3](Architecture-v3.md)
  - [System Design V1](SystemDesign.md)
  - [Architecture Decisions (ADR)](../adr/ArchitectureDecisionRecord.md)

## 1. What is this system in one sentence?
It is a control-plane governance service that prevents breaking data contract changes before deployment by running compatibility checks and storing auditable results.

## 2. What problem does this architecture solve?
1. Teams change schemas quickly, but consumers can break silently.
2. CI pipelines need deterministic PASS/FAIL checks before merge or deploy.
3. Organizations need an audit trail of what was checked, by whom, and with what outcome.

## 3. Why is this a control-plane design and not runtime interception?
1. It integrates with existing Git and CI workflows without requiring broker-level interception.
2. It reduces initial operational complexity and adoption friction.
3. It gives fast governance value while keeping runtime systems unchanged.

## 4. Why keep contracts in Git?
1. Git provides version history and review workflow by default.
2. PRs naturally become governance checkpoints.
3. Teams already know how to collaborate in Git-based workflows.

## 5. Why separate `MetadataStore` and `ArtifactStore`?
1. Metadata is transactional and query-heavy (`check_runs`, logs, audit).
2. Artifacts are file/object oriented (schema and metadata files).
3. This boundary lets us add MySQL or S3 without rewriting policy logic.

## 6. Why Postgres as production primary?
1. Strong transaction semantics and broad operational maturity.
2. Better fit for queue state transitions and audit-log durability.
3. Rich production tooling for backups, observability, failover, and managed hosting.

## 7. Why keep SQLite in the architecture?
1. It gives near-zero friction for local development and demos.
2. It supports production-lite single-node deployments at low cost.
3. It shortens onboarding time for open-source users.

Important limit:
1. SQLite is supported for local and production-lite single-node setups.
2. SQLite is not the recommended backend for multi-node high-concurrency production.

## 8. Why MySQL in Phase 2?
1. MySQL is widely used in many organizations.
2. Supporting MySQL expands adoption while preserving the same governance logic.
3. Adding it through a storage interface reduces long-term maintenance risk.

## 9. Why S3 in Phase 3?
1. S3 is object storage, ideal for contract artifacts at scale.
2. It decouples artifact durability and lifecycle from transactional metadata.
3. It enables hybrid deployment patterns without replacing metadata databases.

## 10. Why MongoDB is demand-gated in Phase 4?
1. Mongo adds a different data model and operational tradeoffs.
2. Premature support increases complexity and test burden.
3. We only add it when real user demand and clear use cases justify maintenance cost.

## 11. How does the runtime flow work?
1. User/CI submits a check run to API.
2. API writes `QUEUED` run in metadata store.
3. Runner claims queued run and marks `RUNNING`.
4. Policy engine evaluates compatibility.
5. Runner writes final status, logs, and audit data.
6. API/UI returns queryable history and details.

## 12. Why this design is production-oriented
1. Explicit queue states (`QUEUED`, `RUNNING`, terminal states).
2. Durable audit logs for compliance and traceability.
3. Role-based write protection and request-level error contracts.
4. Health checks and metrics-first operations model.
5. Clear storage support matrix and phase-based expansion plan.

## 13. Postman Testing Guide

### 13.1 Base URLs
1. API base URL: `http://localhost:8080`
2. OpenAPI docs endpoint: `http://localhost:8080/v3/api-docs`
3. Swagger UI endpoint: `http://localhost:8080/swagger-ui/index.html`

### 13.2 Auth behavior
1. Ordinary local routes are open when `app.security.enabled=false` (the default local setting).
2. `POST /checks/evidence` is different: it is disabled by default, uses Basic authentication only when the explicit local/demo evidence mode is enabled, and requires CI-issued OIDC Bearer tokens in production.
3. Production OIDC evidence import validates issuer/signature/audience and an exact configured contract, repository, and ref policy; it retains verified workload provenance separately from `ciIdentity`.
4. Other protected write routes require a Basic Auth user with the `WRITER` role when `app.security.enabled=true`. Read routes under `/checks` and `/runs` require authentication in that mode.

### 13.3 Required headers
1. `Content-Type: application/json` for JSON POST routes.
2. Optional: `X-Request-ID: postman-<unique-id>` for request traceability.

## 14. POST Endpoints to Test in Postman

### 14.1 Queue a check run
- Method: `POST`
- URL: `/checks`
- Expected success: `202 Accepted`
- Body:
```json
{
  "contractId": "orders.created",
  "baseVersion": "v1",
  "candidateVersion": "v2",
  "mode": "BACKWARD",
  "commitSha": "postman-check-001",
  "triggeredBy": "postman"
}
```
Notes:
1. `baseVersion` and `candidateVersion` must differ.
2. `mode` must be one of `BACKWARD`, `FORWARD`, `FULL`.

### 14.2 Create a new contract
- Method: `POST`
- URL: `/contracts`
- Expected success: `201 Created`
- Body:
```json
{
  "contractId": "payments.completed",
  "ownerTeam": "payments-platform",
  "domain": "finance",
  "compatibilityMode": "BACKWARD",
  "policyPack": "baseline",
  "initialVersion": "v1",
  "schema": {
    "type": "object",
    "properties": {
      "paymentId": { "type": "string" },
      "amount": { "type": "number" },
      "currency": { "type": "string" }
    },
    "required": ["paymentId", "amount"]
  }
}
```
Notes:
1. `contractId` must be lowercase dot-separated format.
2. If contract already exists, expect `409 Conflict`.

### 14.3 Create contract version
- Method: `POST`
- URL: `/contracts/{contractId}/versions`
- Example URL: `/contracts/orders.created/versions`
- Expected success: `201 Created`
- Body:
```json
{
  "version": "v3",
  "schema": {
    "type": "object",
    "properties": {
      "orderId": { "type": "string" },
      "status": { "type": "string" },
      "region": { "type": "string" }
    }
  }
}
```
Notes:
1. Version must be incremental (`vN` expected next).
2. In strict mode, breaking changes return `422 Unprocessable Entity`.

### 14.4 Trigger UI check run (form submission endpoint)
- Method: `POST`
- URL: `/ui/contracts/{contractId}/checks`
- Example URL: `/ui/contracts/orders.created/checks`
- Body type: `x-www-form-urlencoded`
- Fields:
  - `baseVersion=v1`
  - `candidateVersion=v2`
  - `commitSha=postman-ui-001`
- Expected success: `302 Found` redirect to `/ui/checks/{runId}`
Notes:
1. This endpoint is form-oriented and returns HTML redirect behavior.

## 15. Recommended Postman Test Sequence
1. `GET /actuator/health` and verify service is up.
2. `POST /checks` to queue a run.
3. Capture returned `runId`.
4. `GET /checks/{runId}` to inspect status lifecycle.
5. `GET /checks/{runId}/logs` to inspect runner logs.
6. `POST /contracts` to create a new contract.
7. `POST /contracts/{contractId}/versions` to add a version.
8. `GET /contracts/{contractId}` and `GET /contracts/{contractId}/versions/{version}` to verify persistence.

## 16. Quick negative tests (important)
1. `POST /checks` with same `baseVersion` and `candidateVersion` -> expect `400`.
2. `POST /contracts` with invalid `contractId` case -> expect `400`.
3. `POST /contracts/{contractId}/versions` with non-increment version -> expect `400`.
4. `POST /contracts/{contractId}/versions` with strict-mode breaking change -> expect `422`.
5. If security enabled and no `WRITER` role on write routes -> expect `403`.

## 17. Common response expectations
1. Error payload shape includes: `timestamp`, `status`, `error`, `code`, `message`, `path`, `requestId`.
2. Check-run creation success includes: `runId`, `status`.
3. Paginated checks endpoint (`GET /checks/page`) includes: `items`, `limit`, `offset`, `hasMore`.

## 18. What to tell stakeholders simply
1. We use Git and CI to stop unsafe schema changes before deployment.
2. We store checks and audits in a transactional metadata backend.
3. Postgres is the production default, SQLite is local plus production-lite.
4. We designed interfaces so MySQL and S3 can be added without architectural rewrites.
