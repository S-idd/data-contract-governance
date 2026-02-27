# System Design - Data Contract and Schema Governance (V1)

## 1. Design Objectives
- Prevent breaking schema changes before merge/deploy.
- Keep runtime lightweight and local-first.
- Use Git as source of truth.
- Ensure deterministic, auditable decisions.

## 2. Scope and Boundaries
In scope (V1):
- JSON Schema validation, diff, compatibility checks.
- CLI-based local and CI execution.
- Read-only Spring Boot APIs for contracts/check history.
- Check result persistence to file store or SQLite.

Out of scope (V1):
- Broker-side enforcement/interception.
- Multi-format contracts (Avro/Protobuf).
- Paid integrations and enterprise workflow engine.

## 3. Architecture Overview
Primary components:
1. `contracts-repo` (Git)
2. `contract-core` (Java library)
3. `contract-cli` (Java command-line tool)
4. `contract-service` (Spring Boot read API)
5. `check-store` (JSON files or SQLite)
6. `ci-runner` (GitHub Actions or equivalent)

Logical model:
- Control plane only. Contract validation happens in local dev and CI.
- Contract service is read-focused and does not mutate schemas in V1.

## 4. Component Responsibilities

### 4.1 contracts-repo
- Stores all contract folders, metadata, and versioned schema files.
- Defines ownership and compatibility mode per contract.

Expected structure:
```txt
contracts/
  orders.created/
    metadata.yaml
    v1.json
    v2.json
  payment.completed/
    metadata.yaml
    v1.json
```

### 4.2 contract-core
Modules:
- `SchemaLoader`: parses JSON schema and metadata.
- `Linter`: validates folder/file naming, metadata completeness, schema syntax.
- `DiffEngine`: computes semantic deltas (fields/types/required/enums).
- `CompatibilityEngine`: applies selected mode and rules.
- `RuleSet`: pluggable compatibility rule definitions.

Output contract:
- Deterministic `CompatibilityResult` containing `PASS/FAIL`, breaking changes, warnings.

### 4.3 contract-cli
Commands:
- `contract lint`
- `contract diff --base <version> --candidate <version>`
- `contract check-compat --base <version> --candidate <version>`

CLI responsibilities:
- Resolve contract paths.
- Invoke core engine.
- Print human-readable and JSON output.
- Return stable exit codes.

Exit codes:
- `0`: success/pass.
- `1`: compatibility fail.
- `2`: invalid usage/input.
- `3`: internal/runtime error.

### 4.4 contract-service (Spring Boot)
- Exposes read APIs for contracts and historical check runs.
- Rebuilds read index from repo files at startup and on interval/manual refresh.
- Reads `check-store` for run history.

### 4.5 check-store
V1 options:
- Option A: JSON file append store (simplest).
- Option B: SQLite (better queryability).

Persisted per check run:
- runId, contractId, baseVersion, candidateVersion, status
- breakingChanges, warnings
- commitSha, branch, triggeredBy, createdAt

### 4.6 ci-runner
- Detects changed contract paths in PR.
- Executes CLI commands for changed contracts only.
- Fails CI status on breaking changes.

## 5. Data Model

### 5.1 Contract
- `contractId` (string, unique)
- `ownerTeam` (string)
- `domain` (string)
- `compatibilityMode` (`BACKWARD|FORWARD|FULL`)
- `status` (`ACTIVE|DEPRECATED`)

### 5.2 ContractVersion
- `contractId` (string)
- `version` (`vN`)
- `schemaPath` (string)
- `createdBy` (string)
- `createdAt` (timestamp)

### 5.3 CheckRun
- `runId` (uuid)
- `contractId` (string)
- `baseVersion` (string)
- `candidateVersion` (string)
- `status` (`PASS|FAIL`)
- `breakingChanges` (array)
- `warnings` (array)
- `commitSha` (string)
- `createdAt` (timestamp)

## 6. Compatibility Rule Engine Design

### 6.1 Rule Interface
Each rule implements:
- `id()`
- `evaluate(baseSchema, candidateSchema, context)`
- returns `RuleOutcome` with `severity` (`BREAKING|WARNING|INFO`) and evidence

### 6.2 Baseline Rules (V1)
- Field removed -> BREAKING
- Field type changed -> BREAKING
- Required field added -> BREAKING
- Optional field added -> INFO/ALLOW
- Enum value removed -> BREAKING
- Enum value added -> WARNING or ALLOW (config flag)

### 6.3 Modes
- `BACKWARD`: candidate must accept previously valid payloads.
- `FORWARD`: existing consumers should tolerate candidate payload semantics.
- `FULL`: both backward and forward checks pass.

## 7. Sequence Flows

### 7.1 Local Developer Flow
1. Developer edits `contracts/<contractId>/vN.json`.
2. Runs `contract lint`.
3. Runs `contract check-compat --base v(N-1) --candidate vN`.
4. Fixes issues before push.

### 7.2 Pull Request Flow
1. CI identifies changed contract directories.
2. For each contract, CI runs lint + compatibility.
3. On failure, PR status becomes failed.
4. On pass, merge allowed.

### 7.3 Post-Merge Read Flow
1. CI (or hook) writes `CheckRun` to check-store.
2. Service reads latest repo + check-store.
3. Users query APIs for latest versions and check history.

## 8. API Design (V1)

### 8.1 GET /contracts
Returns summary list.

Response fields:
- `contractId`, `ownerTeam`, `domain`, `compatibilityMode`, `latestVersion`, `status`

### 8.2 GET /contracts/{contractId}
Returns contract details and metadata.

### 8.3 GET /contracts/{contractId}/versions
Returns ordered versions and schema references.

### 8.4 GET /contracts/{contractId}/versions/{version}
Returns exact schema payload + metadata.

### 8.5 GET /checks?contractId=&commitSha=
Returns matching check runs (filter optional).

### 8.6 GET /health
Returns service health and index status.

Error contract:
- Standard JSON error body with `code`, `message`, `timestamp`, `path`.

## 9. Storage and Indexing Strategy
- Source of truth: file system Git checkout.
- Service maintains in-memory index keyed by `contractId`.
- Index refresh strategy:
  - Startup full scan.
  - Scheduled scan (for V1 simplicity).
- Check runs loaded from check-store with indexed lookup by `contractId` and `commitSha`.

## 10. CI Integration Design
Minimal GitHub Actions job:
1. Checkout code.
2. Detect changed paths under `contracts/`.
3. Build CLI (`mvn -q -DskipTests package`).
4. Run `contract lint` on changed contracts.
5. Run compatibility check per changed contract.
6. Fail step on non-zero exit code.

Optimization:
- Skip unaffected contracts to reduce runtime.

## 11. Security and Access Model
- Write control delegated to Git permissions.
- Service is read-only by default.
- Optional bearer token auth for API in shared environments.
- No secret material stored in contract metadata/schema files.

## 12. Observability
- Structured logs: `runId`, `contractId`, `status`, `durationMs`.
- Metrics:
  - `checks_total`
  - `checks_failed_total`
  - `check_duration_ms`
  - `contracts_index_size`
- Health endpoint includes index refresh timestamp.

## 13. Failure Scenarios and Handling
- Invalid JSON schema: fail lint with exact file/line if available.
- Missing base version: fail with clear error and remediation text.
- Corrupt metadata: fail lint and block CI.
- Check-store write failure: CI fails; service remains read-only functional.
- Service index stale: serve last good snapshot and mark degraded health.

## 14. Tradeoffs and Decisions
1. File-based repo as source of truth over DB-first model.
- Pros: simple, auditable, cheap.
- Cons: indexing complexity at scale.

2. Control plane CI enforcement over runtime enforcement.
- Pros: easier adoption, lower risk/cost.
- Cons: runtime invalid messages still possible outside governed process.

3. JSON Schema only in V1.
- Pros: faster delivery and lower complexity.
- Cons: excludes Avro/Protobuf users initially.

## 15. Scalability Path (Post-V1)
- Add Avro/Protobuf adapters via same rule interface.
- Add webhook/event notifications (open-source alternatives).
- Replace file check-store with PostgreSQL if query volume grows.
- Add policy packs per domain/team.

## 16. Open Questions to Freeze Before Coding
- JSON Schema draft version for V1 (recommended: 2020-12).
- Enum addition default severity (ALLOW vs WARNING).
- Check-store selection for V1 (JSON files vs SQLite).
- Authentication default for service in local vs shared deployment.
