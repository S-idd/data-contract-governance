# 4.0.0-alpha.1 local-demo prerelease

Java 21 CLI/service with a pinned Rust shadow-inference executable and four frozen artifacts.
No examples, JDK, development databases, credentials or compiler caches belong in the archive.
Compilation evidence is not extracted-package acceptance or production certification.
This is an unpublished local-demo candidate, not a production deployment or stable release.

## Locked build inputs

Source upgrade committed (2026-09-15): Maven now selects embedded Tomcat 10.1.59 for
core, EL and WebSocket. Full Java reactor verification passed (251 tests, 19 skipped,
zero failures/errors). Existing candidate archives still bundle 10.1.55; the pins and
historical dispositions below describe those archives, not a newly released fixed binary.
The committed/pushed next-build pin is listed below; regenerated/retested artifacts are still required.

Release scope (user decision, 2026-09-15): native macOS ARM64 and Linux x64 on
AlmaLinux/WSL2 only. Intel macOS and Linux ARM64 are excluded, not awaiting acceptance
for this prerelease. No bare-metal Linux or general Linux-distribution support is claimed.
This supersedes the historical four-platform acceptance matrix below. The current Step 7
Mac candidate passed native tests; a passing operator report identifies the current WSL2
candidate. External-state/final manual-process cleanup clarifications remain outstanding.
Existing archives are unchanged; these notes apply to the next assembly.

- Version: `4.0.0-alpha.1`; intended tag: `v4.0.0-alpha.1` (not created).
- Java source for next assembly: `4ca0fa42c769749f37fd1d5306bbf5b1c0054aa0` (Tomcat 10.1.59, pushed).
- Existing Step 7 archives retain source `dac3ed509d03e1bef75c47b497ca80bbdd1f2e04` and Tomcat 10.1.55; rebuild and retest before publication.
- Rust source and frozen policy/models for next assembly: `32ca579095ed5b91749b8c33999556624e58758f` (Parquet 60, pushed).
- Existing Step 7 archives retain Rust source `ef819fe58865c643a240ee067cf61d506f04a778`; rebuild before publication.
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

## Security disposition added 2026-09-14 (next assembly)

Current candidates bundle Tomcat 10.1.55. CVE-2026-65182, CVE-2026-65905 and
CVE-2026-68525 remain open and explicitly deferred pending a tested upgrade. Static review
found no application-defined container security constraints, Tomcat DIGEST or FORM auth;
DCG uses Spring Security Basic authentication and disables Spring form login. These findings
limit apparent applicability but are not proof of unreachability or permission to publish.
GitHub suggests 10.1.58; Apache says its release vote failed and directs users to 10.1.59.
Java 21 is within Tomcat 10.1's documented Java support; integration compatibility remains
untested. Upgrade core/el/websocket together, approve a new Java pin and regenerate/retest
all release artifacts before public release, or obtain an explicit reviewed risk decision.
[Apache advisory](https://tomcat.apache.org/security-10.html).

Rust repository Dependabot visibility was unavailable (API reported alerts disabled), not
verified clean. No alerts were dismissed. This note is committed for the next assembly;
existing tested Step 7 archives were not silently modified. Detailed assessment is in
repository `docs/local-alpha-security-review.md`.

## Rollback procedure

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
