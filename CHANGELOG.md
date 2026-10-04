# Changelog

All notable user-facing changes are recorded here. This project follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and uses Semantic Versioning as
described in [the release policy](docs/release-and-versioning.md).

## [Unreleased]

### Added

- Open-source contribution, security, support, release, and production-boundary documentation.
- CI checks for secret scanning, dependency review, vulnerability scanning, and SBOM generation.

## [4.0.0-rc.1] - 2026-10-04 (source prerelease)

- Selected Java-led archive version for native macOS ARM64 and Linux x64 on WSL2; Compose is excluded.
- Rust remains Cargo 0.1.0, embedded at a pinned commit for shadow-only, asynchronous, logging-only, fail-open inference; Java remains authoritative.
- Update Jackson core/databind and companion modules to 2.22.3 to resolve the full dependency scan findings.
- Integrate Ubuntu evaluation health-probe, Linux process-identity, managed shutdown, bundled notification fixture, and 23-check database/API matrix fixes.
- Add Apple Silicon Milestone 1, Bash 3.2 shutdown compatibility, continuous memory telemetry and PASS/REVIEW/FAIL qualification.
- Retain IEMS PID/backend records on shutdown timeout and clean up partial startup failures, covered by regressions on Linux and macOS CI.
- Publish a source checkpoint, not newly qualified binary archives or GA. Full clean-source Ubuntu 24.04/WSL2 acceptance and physical 8GB Mac qualification remain outstanding; earlier bundle evidence does not transfer automatically.
- Existing release-pins.json and frozen evaluation archives retain their original provenance; they are not binaries built from this release tag.

## [4.0.0-alpha.1] - Historical local-demo prerelease candidate

### Changed

- Align all Maven reactor project/parent versions with the V4 alpha publication decision.
- Reconcile release/support policy and executable artifact references. Historical release tags
  remain unchanged; the former unreleased 0.1.0 heading was stale, not a published release.
- Package assembly, platform acceptance and publication remain pending.

### Added

- Java/Spring Boot data-contract compatibility tooling, CLI, service, SDK, Maven/Gradle plugins,
  and Docker Compose demo.

### Known limitations

- MySQL is beta and is not production-approved until provider-specific durability and failover
  validation is complete.
