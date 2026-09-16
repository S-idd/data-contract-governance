# Changelog

All notable user-facing changes are recorded here. This project follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and uses Semantic Versioning as
described in [the release policy](docs/release-and-versioning.md).

## [Unreleased]

### Added

- Open-source contribution, security, support, release, and production-boundary documentation.
- CI checks for secret scanning, dependency review, vulnerability scanning, and SBOM generation.

## [4.0.0-rc.1] - Unreleased local-demo release candidate

- Selected Java-led archive version for native macOS ARM64 and Linux x64 on WSL2; Compose is excluded.
- Rust remains Cargo 0.1.0, embedded at a pinned commit for shadow-only, asynchronous, logging-only, fail-open inference; Java remains authoritative.
- New RC archives, acceptance results, SBOMs, notices, legal approval, and tag remain pending.

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
