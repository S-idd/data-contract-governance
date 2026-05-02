# Week 3 Spec: Phase 1 Storage Interfaces and Migration Policy

- Spec ID: `SPEC-2026-W3-STORAGE-FOUNDATION`
- Date: `2026-03-26`
- Status: `Approved`
- Scope: `Phase 1 foundation`
- Related:
  - `docs/week2-system-design-freeze-rfc.md`
  - `docs/Architecture-v3.md`
  - `contract-service/src/main/java/com/ideas/contracts/service/CheckRunRepository.java`
  - `contract-service/src/main/java/com/ideas/contracts/service/CheckRunStore.java`
  - `contract-service/src/main/java/com/ideas/contracts/service/ContractCatalogService.java`
  - `contract-service/src/main/java/com/ideas/contracts/service/ContractWriteService.java`
  - `contract-core/src/main/resources/db/migration`

## 1. Purpose
Define the Phase 1 storage contracts and migration policy so implementation can proceed consistently across supported metadata databases.

## 2. Frozen Contracts

### 2.1 MetadataStore (transactional metadata)
The metadata port is frozen as the behavioral contract for:
1. check run queue state machine.
2. check run query/read APIs.
3. run logs.
4. audit logs.
5. health and pool diagnostics.

Reference API (aligned with current `CheckRunRepository`):
```java
public interface MetadataStore {
  record HealthSnapshot(boolean available, String reason) {}
  record PoolSnapshot(
      int totalConnections,
      int activeConnections,
      int idleConnections,
      int threadsAwaitingConnection,
      int maximumPoolSize,
      int minimumIdle,
      long connectionTimeoutMs) {}
  record QueuedCheckRun(
      String runId,
      String contractId,
      String baseVersion,
      String candidateVersion,
      String mode,
      String commitSha,
      String triggeredBy) {}

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
  int backfillLegacyRuns(Function<String, String> modeResolver, String defaultTriggeredBy, String defaultMode);

  String configuredDbTarget();
  PoolSnapshot poolSnapshot();
  HealthSnapshot healthSnapshot();
}
```

Behavioral guarantees:
1. `claimNextQueuedRun` must provide single-claimer semantics.
2. `completeRun` must only transition non-terminal runs and return `false` otherwise.
3. `appendLog` is best effort but must throw on persistence failure.
4. `listRunsPage` ordering is `created_at DESC`, deterministic for equal timestamps via `run_id DESC` tie-breaker.
5. All validation failures are explicit `IllegalArgumentException` or `CheckRunStoreException` style failures; no silent fallback.

### 2.2 ArtifactStore (contract artifacts)
The artifact port is frozen for filesystem-backed Phase 1 behavior and future S3 adapter compatibility.

Reference API:
```java
public interface ArtifactStore {
  List<String> listContracts();
  Optional<ContractMetadata> readMetadata(String contractId);
  List<String> listVersions(String contractId);
  Optional<JsonNode> readSchema(String contractId, String version);

  void createContract(CreateContractRequest request);
  void createVersion(String contractId, CreateContractVersionRequest request);

  void invalidate(String contractId);
}
```

Behavioral guarantees:
1. Contract IDs are lowercase dot-separated (`^[a-z0-9]+(\\.[a-z0-9]+)*$`).
2. Version naming is `vN` and must be strictly monotonic (`v1`, `v2`, `v3`, ...).
3. `createContract` is all-or-nothing for metadata + initial schema.
4. `createVersion` writes candidate schema then validates lint and compatibility (strict mode gate respected).
5. Reads are eventually consistent with in-process cache invalidation semantics.

## 3. Adapter Model (Phase 1)
1. `JdbcMetadataStore` adapter supports Postgres and SQLite through SQL dialect-aware queries.
2. `FilesystemArtifactStore` adapter supports local disk artifact storage.
3. Business services (`CheckRunner`, controllers, catalog/write services) depend only on storage ports, not backend-specific details.

## 4. Migration Policy (Metadata DB)

### 4.1 Canonical Location
1. All metadata schema migrations live in `contract-core/src/main/resources/db/migration`.
2. CLI and service both apply exactly the same migration set.

### 4.2 Versioning Rules
1. Naming: `V<integer>__<description>.sql`.
2. Migrations are append-only after merge; no edits to already applied versions.
3. Rollbacks are forward-fix migrations, not history rewrites.

### 4.3 Backward-Compatible Evolution Rules
1. Expand-then-contract pattern:
   - add nullable columns/indexes first.
   - deploy code using new + old paths.
   - remove deprecated columns only in a later migration after verification.
2. Avoid destructive DDL in the same release as behavior changes.
3. Data backfills must be idempotent and resumable.

### 4.4 Cross-Backend SQL Rules
1. Keep SQL portable between Postgres and SQLite where possible.
2. When dialect branching is required, isolate via query constants and test both paths.
3. Required parity areas:
   - queue claim and transition behavior
   - pagination semantics
   - JSON/text serialization consistency for warnings and breaking changes

### 4.5 Startup and Safety
1. `fail-fast-startup=true` in production profiles.
2. Schema mismatch must surface as startup failure in Tier A.
3. Tier B may run deferred init only if explicitly configured and documented.

## 5. Compatibility Contract Between Ports
1. Artifact writes and metadata writes are separate transactions by design.
2. For contract creation/version writes, audit logs in metadata are best effort and must not corrupt artifact state.
3. On partial failure in artifact writes, cleanup must be attempted and surfaced as explicit error.

## 6. Definition of Done for Week 3 Spec
1. Storage contracts are documented and accepted.
2. Migration policy is documented and accepted.
3. Compatibility test plan is linked and accepted.

Linked test plan:
- `docs/week3-db-compatibility-test-plan.md`
