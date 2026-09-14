# 4.0.0-alpha.1 local-demo prerelease

Java 21 CLI/service with a pinned Rust shadow-inference executable and four frozen artifacts.
No examples, JDK, development databases, credentials or compiler caches belong in the archive.
Compilation evidence is not extracted-package acceptance or production certification.
This is an unpublished local-demo candidate, not a production deployment or stable release.

## Locked build inputs

- Version: `4.0.0-alpha.1`; intended tag: `v4.0.0-alpha.1` (not created).
- Java source: `dac3ed509d03e1bef75c47b497ca80bbdd1f2e04`.
- Rust source and frozen policy/models: `ef819fe58865c643a240ee067cf61d506f04a778`.
- Build/acceptance JDK: Eclipse Temurin `21.0.12.1+1`, supplied separately.
- Rust: `1.96.0`, compiler commit `ac68faa20c58cbccd01ee7208bf3b6e93a7d7f96`, locked dependencies.

The selected Java reactor recorded 231 tests: 212 passed, 19 skipped, no failures/errors.
All four Rust target builds completed. Compilation does not establish runtime support.
Packaging changes do not replace the Java source pin; actual packaging inputs, executable
and JAR hashes are recorded separately in `build-info.json`.

## Acceptance status at candidate preparation (2026-09-14)

- macOS ARM64: the Step 5 archive passed native extracted-package acceptance, including
  loopback startup, authentication, AI outage/recovery, repeat lifecycle and clean shutdown.
  That evidence identifies the older archive checksum; revised documentation does not
  automatically transfer acceptance to a new archive.
- Linux x64: WSL2 laptop testing pending; WSL2 is not bare-metal Linux certification.
- macOS x64 and Linux ARM64: built, matching-machine acceptance pending.
- Minimum macOS/glibc versions remain unverified. Linux builders used Bookworm/glibc 2.36.

## Dependency evidence and publication gates

`sbom.cdx.json` is CycloneDX 1.6, derived from resolved Maven dependencies, target-filtered
Cargo metadata, Spring Boot injected resources and the Rust standard library. Cargo
build-only components are marked excluded. `THIRD-PARTY-NOTICES.txt` preserves upstream
texts and source-pinned supplemental licenses. The unbundled JDK and host OS libraries
are external prerequisites, not payload files.

No license-text inventory entries are missing; this is not redistribution approval.
MySQL Connector/J, Logback and Jakarta Annotation license/exception obligations require
review before public distribution, including any required corresponding-source arrangements.
Technical validation is not a vulnerability scan, signed attestation or legal clearance.
Finish target-host acceptance, redistribution/security review and final asset review before
tagging or publishing.

## Rollback

1. Run the current package's bin/stop using its original DCG_DATA_DIR. Confirm both processes
   stopped before touching state. Do not delete PID records to bypass a shutdown failure.
2. Back up the entire stopped data directory, including contracts, credentials and SQLite
   database/WAL files. Store the backup privately outside both package and data directories.
3. Keep the previous verified archive and its matching pre-upgrade data backup. Extract to
   a separate directory and verify checksums.
4. Restore that backup to a separate absolute data directory and point DCG_DATA_DIR there
   when starting the previous version. Never run an older binary against a migrated database
   unless backward compatibility has been separately verified.
5. Check status and a known contract check. Preserve the failed-version data for diagnosis.

Stop before changing package paths. No automatic schema downgrade, destructive cleanup,
remote deployment or automatic update is implemented.
