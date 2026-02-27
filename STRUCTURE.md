# Project Structure

## Root
- `contracts/`: Versioned data contracts (source of truth).
- `contract-core/`: Core schema lint/diff/compatibility engine.
- `contract-cli/`: CLI wrapper for local/CI checks.
- `contract-service/`: Spring Boot read API service.
- `.github/workflows/`: CI workflows.
- `scripts/ci/`: helper scripts for pipeline tasks.
- `docs/`: requirements and system design documentation.
- `adr/`: architecture decision records.
- `build-logic/`: shared build conventions/plugins (future use).

## Java Module Layout
Each module follows standard Maven layout:
- `src/main/java`
- `src/main/resources` (service module)
- `src/test/java`

## Contract Repository Layout
Expected per-contract format under `contracts/`:

```txt
contracts/
  <contract-id>/
    metadata.yaml
    v1.json
    v2.json
```

## Naming Conventions
- Contract IDs: lowercase dot-separated (`orders.created`).
- Schema versions: `v1.json`, `v2.json`, ...
- Metadata file: exactly `metadata.yaml`.

## Notes
- V1 uses JSON Schema draft 2020-12.
- Compatibility enforcement is done via local CLI + CI.
