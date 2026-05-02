# RFC: Week 2 System Design Freeze (v3)

- RFC ID: `RFC-2026-W2-SYSTEM-DESIGN-FREEZE`
- Date: `2026-03-26`
- Status: `Approved`
- Owners: `DCG maintainers`
- Related:
  - `docs/Architecture-v3.md`
  - `docs/Architecture-FAQ.md`
  - `contract-service/src/main/java/com/ideas/contracts/service/CheckRunRepository.java`
  - `contract-service/src/main/java/com/ideas/contracts/service/ContractCatalogService.java`
  - `contract-service/src/main/java/com/ideas/contracts/service/ContractWriteService.java`

## 1. Purpose
Freeze the v3 storage and reliability architecture so implementation can proceed without scope churn.

Week 2 scope covered by this RFC:
1. Final architecture for `MetadataStore` and `ArtifactStore`.
2. Support tiers (Postgres production standard, SQLite production-lite).
3. Draft SLO/SLA targets for production usage.
4. Explicit non-goals.

## 2. Decision Summary (Frozen)
1. `MetadataStore` is the transactional system-of-record for check execution state, logs, and audit entries.
2. `ArtifactStore` is the contract artifact system-of-record for `metadata.yaml` and `vN.json` schema files.
3. Postgres is the only production-standard metadata backend for v3 Phase 1.
4. SQLite is explicitly supported only for local and production-lite single-node deployments.
5. Public API surface remains stable while storage adapters evolve behind interfaces.
6. No additional backend commitments (MySQL/S3/Mongo) are made in Week 2 beyond already documented phased intent.

## 3. Context and Problem
The project currently has two implicit seams:
1. `CheckRunRepository` already acts as a metadata persistence boundary.
2. Contract read/write paths in `ContractCatalogService` and `ContractWriteService` currently use filesystem access directly.

Without a freeze now, backend expansion risks coupling business logic to storage implementation details.

## 4. Architecture Boundaries

### 4.1 MetadataStore Boundary
Responsibilities:
1. Queue lifecycle: create, claim, state transition (`QUEUED` -> `RUNNING` -> terminal).
2. Check run query model: list/filter/page/detail.
3. Check run logs append/read.
4. Audit log persistence.
5. Store health and pool diagnostics.

Frozen interface contract (conceptual):
```java
public interface MetadataStore {
  CheckRunCreateResponse createQueuedRun(CheckRunCreateRequest request);
  Optional<QueuedCheckRun> claimNextQueuedRun();
  boolean completeRun(String runId, String status, List<String> breakingChanges, List<String> warnings);
  boolean requeueRun(String runId);
  void appendLog(String runId, String level, String message);

  List<CheckRunResponse> listRuns(String contractId, String commitSha);
  CheckRunPageResponse listRunsPage(CheckRunQuery query);
  Optional<CheckRunResponse> findRunById(String runId);
  List<CheckRunLogResponse> listRunLogs(String runId);

  void recordAuditLog(AuditLogEntry entry);
  HealthSnapshot health();
}
```

Mapping to current code:
1. `CheckRunRepository` is accepted as the current port for `MetadataStore`.
2. `CheckRunStore` is the current adapter implementation.
3. Rename/refactor to `MetadataStore` is optional and non-blocking for Week 2 approval.

### 4.2 ArtifactStore Boundary
Responsibilities:
1. List contracts.
2. Read contract metadata (`metadata.yaml`).
3. List versions and read schema payloads (`vN.json`).
4. Create contract folder + metadata + initial schema.
5. Create new version schema with monotonic version guarantees.

Frozen interface contract (conceptual):
```java
public interface ArtifactStore {
  List<String> listContracts();
  Optional<ContractMetadata> readMetadata(String contractId);
  List<String> listVersions(String contractId);
  Optional<JsonNode> readSchema(String contractId, String version);

  void createContract(CreateContractRequest request);
  void createVersion(String contractId, CreateContractVersionRequest request);
}
```

Mapping to current code:
1. Filesystem reads in `ContractCatalogService` map to ArtifactStore read capabilities.
2. Filesystem writes in `ContractWriteService` map to ArtifactStore write capabilities.
3. Week 3+ implementation should extract a `FilesystemArtifactStore` adapter and inject it into both services.

## 5. Support Tiers (Frozen)

### 5.1 Tier A: Production Standard
1. Metadata backend: Postgres (required).
2. Artifact backend: Filesystem (Phase 1), S3 optional only after Phase 3 delivery.
3. Topology: multi-instance API/runner with managed Postgres and backup/restore drills.
4. Support expectation: full operational target and SLO/SLA draft applies.

### 5.2 Tier B: Production-Lite
1. Metadata backend: SQLite with local persistent disk.
2. Artifact backend: local filesystem.
3. Topology: single node only (single writer process).
4. Mandatory controls:
   - WAL mode enabled.
   - Busy timeout configured.
   - Backup + periodic restore verification documented.
5. Explicit limit: no HA or horizontal write scaling claims.

### 5.3 Tier C: Local Developer
1. Metadata backend: SQLite default.
2. Artifact backend: local filesystem.
3. Support expectation: best-effort developer experience, not uptime guarantees.

## 6. Reliability Targets (Draft)

### 6.1 SLO Draft (Tier A only)
1. API availability: `>= 99.5%` monthly for `/contracts`, `/checks`, `/runs` endpoints.
2. Queue submission latency (`POST /checks`): p95 `<= 300 ms`.
3. Check history query latency (`GET /checks`, `GET /checks/page`): p95 `<= 500 ms`.
4. Queue freshness: p95 time from `QUEUED` to `RUNNING` `<= 30 seconds` under normal load.
5. Compatibility execution time: p95 `<= 5 seconds` for schemas <= 1 MB.
6. Durability objective: acknowledged metadata writes survive process restart.

### 6.2 SLA Draft
1. No external commercial SLA is committed in Week 2.
2. Internal operating target for Tier A: `99.0%` monthly service availability.
3. Tier B (SQLite production-lite) is excluded from uptime SLA commitments.

### 6.3 Error Budget Policy (Draft)
1. Monthly error budget for Tier A availability SLO: `0.5%` downtime.
2. If budget burn > 50% before day 15 of the month, feature rollout pauses and reliability work is prioritized.

## 7. Security and Compliance Baseline (Confirmed)
1. Write endpoints remain role-protected.
2. Audit logging remains mandatory for create/update/check workflows.
3. DB credentials must come from env/secret manager, never committed.
4. Migration changes require backward-compatible rollout plan.

## 8. Non-Goals (Week 2 Freeze)
1. Runtime payload interception in brokers or API gateways.
2. Multi-region active-active architecture.
3. HA guarantees for SQLite production-lite.
4. Immediate MySQL/S3 implementation during Week 2.
5. MongoDB implementation commitment.
6. Avro/Protobuf expansion in this phase.
7. Rebuilding full schema registry feature parity with tools like Apicurio in this milestone.

## 9. Implementation Implications
1. Preserve existing API contracts and error payload shape while refactoring internals.
2. Keep Flyway migrations as the canonical metadata schema evolution path.
3. Add adapter contract tests to enforce parity across Postgres and SQLite semantics.
4. Introduce explicit storage capability tests:
   - queue claim semantics
   - pagination ordering
   - idempotent terminal state writes
   - audit log append/read
5. Plan a follow-up ADR to capture any naming refactor (`CheckRunRepository` -> `MetadataStore`) if adopted.

## 10. Risks and Mitigations
1. Risk: SQLite used outside intended limits.
   Mitigation: label Tier B as production-lite everywhere, add startup warning in non-local profile.
2. Risk: future adapter work causes behavior drift.
   Mitigation: shared compatibility test suite for all metadata adapters.
3. Risk: SLO targets too aggressive for current capacity.
   Mitigation: mark as draft, validate during Week 6 reliability phase, adjust with measured baselines.

## 11. Approval Checklist (Week 2 Exit)
Approval is complete only when all are true:
1. This RFC status is changed from `Proposed for approval` to `Approved`.
2. Maintainer review confirms boundaries for `MetadataStore` and `ArtifactStore`.
3. Support tier limits are documented in operator-facing docs.
4. SLO/SLA draft is accepted as initial target set.
5. Non-goals are accepted to prevent scope expansion.

## 12. Deferred Questions (Not Blocking Week 2 Approval)
1. Whether to rename `CheckRunRepository` immediately or defer until adapter extraction.
2. Whether artifact writes should move to atomic temp-file + rename in the first extraction PR.
3. Exact Tier A SLO values after Week 6 measurement baselines.
