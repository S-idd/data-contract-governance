# Architecture Decision Record (ADR) - Data Contract and Schema Governance V1

## ADR-001: Git Repository as Source of Truth
- Status: Accepted
- Date: 2026-02-27

Context:
- We need a low-cost, auditable, open-source approach for contract management.
- Team workflow is already PR-driven.

Decision:
- Store all contracts as files in a Git repository under `contracts/`.
- Each contract contains `metadata.yaml` and versioned schema files (`v1.json`, `v2.json`, ...).

Consequences:
- Positive:
  - Strong audit trail via commits and PR history.
  - No paid dependency and minimal infrastructure.
  - Easy local development.
- Negative:
  - Requires indexing logic for service reads.
  - Scaling may require optimization for large repositories.

## ADR-002: Control Plane Enforcement via CLI + CI
- Status: Accepted
- Date: 2026-02-27

Context:
- Primary problem is preventing breaking changes before deployment.
- Runtime broker interception increases complexity and cost.

Decision:
- Enforce schema governance at development and CI stages.
- Use CLI commands (`lint`, `diff`, `check-compat`) in local workflow and PR pipelines.

Consequences:
- Positive:
  - Fast to implement, low operational risk.
  - Compatible with existing developer workflows.
- Negative:
  - Invalid messages can still appear at runtime if process is bypassed.

## ADR-003: JSON Schema as the Only Contract Format in V1
- Status: Accepted
- Date: 2026-02-27

Context:
- Multi-format support in V1 would delay delivery and increase rule complexity.

Decision:
- Support JSON Schema only in V1.
- Freeze schema standard to draft `2020-12`.

Consequences:
- Positive:
  - Reduced complexity and faster MVP.
  - Clear and deterministic compatibility checks.
- Negative:
  - Avro/Protobuf users must wait for later phases.

## ADR-004: Java 21 + Spring Boot 3.x Core Stack
- Status: Accepted
- Date: 2026-02-27

Context:
- Team skill is strongest in Java/Spring Boot.
- Requirement is open-source and local-friendly.

Decision:
- Build all components in Java.
- Use Spring Boot for service APIs.
- Use Maven multi-module structure.

Consequences:
- Positive:
  - Faster development due to familiarity.
  - Strong ecosystem and maintainability.
- Negative:
  - Need custom compatibility rule engine implementation.

## ADR-005: Rule-Based Compatibility Engine with Pluggable Rules
- Status: Accepted
- Date: 2026-02-27

Context:
- Need deterministic checks now and extensibility later.

Decision:
- Implement compatibility as rule evaluation over semantic diffs.
- Baseline rules:
  - Field removed -> BREAKING
  - Type changed -> BREAKING
  - Required field added -> BREAKING
  - Optional field added -> ALLOWED
  - Enum value removed -> BREAKING
  - Enum value added -> WARNING (default)

Consequences:
- Positive:
  - Explicit governance behavior.
  - Easy to add new rules later.
- Negative:
  - Initial implementation effort for diff/rule model.

## ADR-006: Compatibility Modes = BACKWARD, FORWARD, FULL
- Status: Accepted
- Date: 2026-02-27

Context:
- Different teams require different compatibility guarantees.

Decision:
- Support three modes at contract level:
  - BACKWARD
  - FORWARD
  - FULL
- Default mode: BACKWARD.

Consequences:
- Positive:
  - Flexible governance by contract.
- Negative:
  - More test cases needed for mode behavior.

## ADR-007: Check Result Persistence Using SQLite in V1
- Status: Accepted
- Date: 2026-02-27

Context:
- Need queryable and auditable check history.
- JSON files are simple but harder to query reliably as volume grows.

Decision:
- Use SQLite as check-store in V1.
- Persist one record per check run with breaking changes and warnings.

Consequences:
- Positive:
  - Better queryability than flat files.
  - Still lightweight and local-first.
- Negative:
  - Requires schema migration/versioning discipline.

## ADR-008: Read-Only Contract Service in V1
- Status: Accepted
- Date: 2026-02-27

Context:
- Write operations are already handled via Git PR workflow.

Decision:
- Expose read APIs only in V1:
  - `GET /contracts`
  - `GET /contracts/{contractId}`
  - `GET /contracts/{contractId}/versions`
  - `GET /contracts/{contractId}/versions/{version}`
  - `GET /checks?contractId=&commitSha=`
  - `GET /health`

Consequences:
- Positive:
  - Smaller attack surface and simpler service.
  - Faster implementation.
- Negative:
  - No service-driven mutation workflows in V1.

## ADR-009: CI Scope Limited to Changed Contracts
- Status: Accepted
- Date: 2026-02-27

Context:
- Full-repo checks can become slow as contract count increases.

Decision:
- CI detects changed directories under `contracts/`.
- Run lint and compatibility checks only for impacted contracts.

Consequences:
- Positive:
  - Faster CI and lower compute usage.
- Negative:
  - Requires reliable changed-path detection logic.

## ADR-010: Authentication Policy by Environment
- Status: Accepted
- Date: 2026-02-27

Context:
- Local developer usage should be frictionless.
- Shared environments require basic access control.

Decision:
- Local mode: authentication disabled by default.
- Shared deployment: bearer token required.

Consequences:
- Positive:
  - Better developer experience locally.
  - Reasonable baseline security when shared.
- Negative:
  - Environment-specific configuration must be documented clearly.

## Summary of Locked Decisions
- Source of truth: Git file repository.
- Enforcement point: local + CI control plane.
- Schema format: JSON Schema draft 2020-12.
- Stack: Java 21, Spring Boot 3.x, Maven.
- Rule behavior: explicit pluggable engine, enum addition defaults to WARNING.
- Compatibility modes: BACKWARD default, plus FORWARD/FULL.
- Check-store: SQLite.
- Service scope: read-only APIs.
- CI strategy: changed-contract-only checks.
- Auth strategy: none local, token in shared environments.
