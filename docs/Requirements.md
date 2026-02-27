# Requirements - Data Contract and Schema Governance (V1)

## 1. Purpose
Build a low-cost, open-source tool that prevents breaking event schema changes before they reach production.

## 2. Goals
- Detect breaking schema changes during local development and CI.
- Enforce versioned contract ownership and compatibility rules.
- Provide auditable pass/fail results tied to commits.
- Keep architecture lightweight and laptop-friendly.

## 3. Non-Goals (V1)
- No broker-side runtime interception.
- No multi-format support beyond JSON Schema.
- No enterprise approval workflow engine.
- No Kubernetes-first deployment requirements.

## 4. Users and Stakeholders
- Developer: edits contract schemas and validates changes.
- Platform Maintainer: defines governance rules and enforces CI gate.
- Service Consumer Team: views schema history and compatibility status.

## 5. Constraints
- 100% open-source tools.
- No paid SaaS dependency required.
- Primary stack: Java 21 + Spring Boot.
- Must run on modest local hardware.

## 6. Functional Requirements

### FR-1 Contract Repository Structure
- System shall use a Git-backed file structure for contracts.
- Each contract shall have:
  - `metadata.yaml`
  - versioned schema files (`v1.json`, `v2.json`, ...)

Acceptance criteria:
- Invalid folder or file naming must fail lint checks.

### FR-2 Schema Linting
- System shall validate JSON Schema syntax and metadata completeness.
- CLI command: `contract lint`.

Acceptance criteria:
- Invalid JSON schema returns non-zero exit code with actionable errors.
- Missing required metadata fields returns non-zero exit code.

### FR-3 Schema Diff
- System shall produce semantic differences between base and candidate schema versions.
- CLI command: `contract diff --base <version> --candidate <version>`.

Acceptance criteria:
- Output lists added/removed/changed fields and enum deltas.

### FR-4 Compatibility Check
- System shall evaluate compatibility using configured mode:
  - `BACKWARD`
  - `FORWARD`
  - `FULL`
- CLI command: `contract check-compat --base <version> --candidate <version>`.

V1 rule baseline:
- Remove field -> breaking
- Type change -> breaking
- Add optional field -> allowed
- Add required field -> breaking
- Remove enum value -> breaking
- Add enum value -> allowed (or warning, configurable)

Acceptance criteria:
- Breaking change yields `FAIL` and non-zero exit code.
- Non-breaking change yields `PASS` and zero exit code.

### FR-5 CI Gate Integration
- System shall support CI usage (GitHub Actions compatible).
- CI shall fail pull request checks on breaking changes.

Acceptance criteria:
- A PR with breaking schema change is blocked by CI status check.

### FR-6 Check Result Persistence
- System shall persist check results (file-based JSON or SQLite in V1).
- Each check record shall include:
  - run ID
  - contract ID
  - base and candidate versions
  - status (PASS/FAIL)
  - breaking changes/warnings
  - commit SHA
  - timestamp

Acceptance criteria:
- Check history can be queried for a given contract and commit.

### FR-7 Read APIs (Contract Service)
- Spring Boot service shall expose read APIs:
  - `GET /contracts`
  - `GET /contracts/{contractId}`
  - `GET /contracts/{contractId}/versions`
  - `GET /contracts/{contractId}/versions/{version}`
  - `GET /checks?contractId=&commitSha=`
  - `GET /health`

Acceptance criteria:
- Endpoints return JSON with stable response schema and proper status codes.

## 7. Non-Functional Requirements

### NFR-1 Performance
- Single contract compatibility check should complete within a few seconds on local machine.

### NFR-2 Determinism
- Same inputs must always produce same pass/fail output.

### NFR-3 Reliability
- Tool should fail clearly with actionable messages; no silent pass on parser errors.

### NFR-4 Resource Efficiency
- Must run locally without requiring cluster infrastructure.

### NFR-5 Auditability
- Every check decision must be traceable to commit, rules, and timestamp.

### NFR-6 Extensibility
- Rule engine should allow adding new compatibility rules without major redesign.

## 8. Data Entities
- Contract: `contractId`, `ownerTeam`, `domain`, `compatibilityMode`, `status`
- ContractVersion: `contractId`, `version`, `schemaPath`, `createdBy`, `createdAt`
- CheckRun: `runId`, `contractId`, `baseVersion`, `candidateVersion`, `status`, `breakingChanges`, `warnings`, `commitSha`, `createdAt`

## 9. Assumptions
- Contracts are versioned in Git.
- Teams accept PR-based governance workflow.
- JSON Schema draft version is fixed for V1 (to be specified in design).

## 10. Risks and Mitigations
- False positives in compatibility checks -> keep initial rules explicit and configurable.
- Repo scale causing slower CI -> run checks only for changed contract directories.
- Inconsistent version naming -> enforce strict naming in lint.

## 11. Definition of Done (V1)
- CLI supports `lint`, `diff`, `check-compat` with documented exit codes.
- CI can block breaking schema PRs.
- Service exposes required read APIs.
- Check runs are persisted and queryable.
- Basic docs exist for local setup and CI integration.

## 12. Out-of-Scope Reconfirmation
- No paid integrations (Slack, proprietary governance tools) in V1.
- No runtime broker plugin enforcement in V1.
- No Avro/Protobuf support in V1.
