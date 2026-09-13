# Local prerelease packaging specification

Status: prerequisite decisions locked, 2026-09-13. No binaries built or prerelease published. Assembly, launchers and CI automation are explicitly deferred.

## 1. Publication version decision

Use `4.0.0-alpha.1` everywhere: Maven project/parent versions, release title and artifact filenames. The Git tag is `v4.0.0-alpha.1`. Later prereleases advance `alpha.N`, then `rc.N` when acceptance gates pass; the stable target is `4.0.0`. Never move historical tags.

Evidence: tags `v1.0` (d62c493d7b702b0d23af8e46276090b32e26b309), `V2.0` (21a372e4740831fb7419c30fff0720337070e6d3), and `v3.0` (7e3467a7b72c081a22298a00b577b64c22889195) all retain Maven `0.1.0-SNAPSHOT`. The current POM also retains it. POM history traces that version to the initial project; it was not advanced alongside published tags. `v3.0` is an ancestor of the current Java baseline, not a disconnected release line. Its May 23, 2026 release notes describe this same product and name V4 as the next phase. The contradictory 0.x policy in `docs/release-and-versioning.md` was added later, in c965fa787fdf925c1c4561988c4f938f382c8cd7 on August 22.

Conclusion: Maven was stale; there is no evidence that v3.0 was abandoned. Continuing the documented V4 work as an alpha avoids regressing published version numbers and does not claim release-candidate readiness before package tests exist. All reactor project/parent versions, version/support documentation and changelog are now aligned with this decision. Renaming snapshot JARs is not version alignment.

Release-history reference: https://github.com/S-idd/data-contract-governance/releases/tag/v3.0

## Runtime distribution

Target macOS and Linux. Build and smoke-test each architecture before advertising support; the proposed matrix is ARM64 and x86_64 on both systems. Require an installed Java 21 runtime. Recipients should not need Maven, Cargo, Docker, or either source checkout.

## 2. Definitive macOS ARM64 manifest

Archive: `dcg-4.0.0-alpha.1-macos-arm64.tar.gz`. Its sole top-level directory is `dcg-4.0.0-alpha.1-macos-arm64/`. Every regular file beneath that directory is listed below. “Planned” means a specification, not an existing or verified deliverable.

| Exact relative filename | Source / status |
| --- | --- |
| `lib/contract-cli-4.0.0-alpha.1-all.jar` | Planned Maven shaded CLI output after version alignment |
| `lib/contract-service-4.0.0-alpha.1.jar` | Planned Spring Boot executable JAR after version alignment |
| `bin/dcgaimodel` | Planned Rust release executable, target `aarch64-apple-darwin` |
| `bin/dcg` | Planned CLI launcher |
| `bin/start` | Planned paired-service launcher |
| `bin/stop` | Planned owned-process shutdown |
| `bin/status` | Planned status command |
| `model/data/inference/frozen-v9/policy-packs-v5-compositional.json` | Rust frozen policy, exact copy |
| `model/data/experiments/v9-multiclass-cpu-100e/models/seed-20260826-normal-family-split.json` | Rust seed model, exact copy |
| `model/data/experiments/v9-multiclass-cpu-100e/models/seed-20260827-normal-family-split.json` | Rust seed model, exact copy |
| `model/data/experiments/v9-multiclass-cpu-100e/models/seed-20260828-normal-family-split.json` | Rust seed model, exact copy |
| `config/application-local-demo.properties.example` | Planned credential-free local configuration template |
| `contracts/policy-packs.json` | Java repository file, exact copy |
| `contracts/orders.created/metadata.yaml` | Java repository sample metadata, exact copy |
| `contracts/orders.created/v1.json` | Java repository sample schema, exact copy |
| `contracts/orders.created/v2.json` | Java repository sample schema, exact copy |
| `README.md` | Planned standalone installation and operation instructions |
| `RELEASE-NOTES.md` | Planned alpha limitations and rollback instructions |
| `LICENSE` | Java repository root LICENSE |
| `licenses/dcgaimodel-LICENSE` | Rust repository root LICENSE |
| `THIRD-PARTY-NOTICES.txt` | Planned aggregated required dependency notices |
| `sbom.cdx.json` | Planned CycloneDX JSON for packaged Java and Rust runtime dependencies |
| `build-info.json` | Planned provenance record described below |
| `SHA256SUMS` | Planned internal checksum manifest |

The Rust Dockerfile identifies **four frozen files total**, not four plus three: one compositional policy and the three seeds above. Preserve their repository-relative `data/` paths beneath `model/`. The separate Java `contracts/policy-packs.json` is also required. The sample metadata selects the baseline policy. No additional model files are assumed.

Verified source SHA-256 values at the pinned revisions:

| File | SHA-256 |
| --- | --- |
| Rust `policy-packs-v5-compositional.json` | `8f82b058f81ace43c89180803c7ec26ac734b84d0092036a77115688337e1bb6` |
| Rust `seed-20260826-normal-family-split.json` | `5da2fedbee5d1b3c84c79cb75e2cd10c0b3462066b66571570e93fb7ccd84988` |
| Rust `seed-20260827-normal-family-split.json` | `bd464322c272b8ec1d5ab88605022d4a48e3738b63ab1791a4c7e56f37222ac8` |
| Rust `seed-20260828-normal-family-split.json` | `24ec9e4e758370093c228af34e3b05ac65820b3fd413ca80a269206ce1edd2c0` |
| Java `contracts/policy-packs.json` | `83cf0b7c20fee50f7090529505d2981d0eec731ff40ec4c5f5b31fe016cc6bad` |

Internal `SHA256SUMS` uses lowercase 64-digit SHA-256, two ASCII spaces, POSIX relative filename, LF newline, one file per line, sorted by filename in byte order. Include every regular payload file except `SHA256SUMS` itself. An external release attachment also named `SHA256SUMS` uses the same format for archive basenames. Final binary/archive digests cannot be populated before builds.

Maven project/parent versions are now aligned to `4.0.0-alpha.1`, producing the CLI/service filenames listed above without renaming snapshot JARs. Planned package files, dependency notices and SBOM coverage still require implementation/verification. If dependency licenses require additional separate files, update this manifest before assembly acceptance.

## 3. Locked source and toolchain inputs

- Java source preparation baseline: `71e0823ac359f63c536ef19f0b79352ba81c1ef5` in `S-idd/data-contract-governance`. This includes the pushed SecurityConfig change. Version and future packaging edits necessarily produce a new final build commit; record that exact commit before any release build, never silently build a moving branch or dirty tree.
- Java build and acceptance-test distribution: **Eclipse Temurin OpenJDK HotSpot 21.0.12.1+1**, compiling with Java release 21. macOS ARM64 upstream asset: `OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12.1_1.tar.gz`. Other targets use the corresponding `x64_mac`, `x64_linux` or `aarch64_linux` asset at the same release. Verify upstream archive checksums before use and record them in provenance. JDK download/verification has not been performed here.
- Upstream Java release: https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.12.1%2B1 . Installed local Oracle Java 21.0.10 is not the locked release build distribution. Recipients install Java 21 separately; no JDK is bundled. Exact Temurin build is the acceptance baseline, not a claim that every Java 21 vendor has been tested.
- Rust source and frozen artifact revision: **`ef819fe58865c643a240ee067cf61d506f04a778`** in the companion `dcgaimodel` repository. Build the executable and copy all four frozen files from this same immutable commit, not `WEEK1`.
- Rust prerelease compiler: **1.96.0** (`ac68faa20`, 2026-05-25); use the explicit toolchain and Cargo lockfile (`--locked`). Record full `rustc -Vv` and Cargo version. The repository's `stable` toolchain and Dockerfile's Rust 1.88 builder are not this explicit native-package build assumption; do not inherit either implicitly. No existing binary is claimed to have been built under this pin.
- `build-info.json` must record publication version, Java preparation and final build SHAs, Rust SHA, target triple, Java vendor/full version and archive digest, full Rust/Cargo versions, source artifact hashes, and any packaging transformations. The final build SHA and binary hashes are build-time evidence, not invented planning values.

Target mapping: `macos-arm64` → `aarch64-apple-darwin`; `macos-x64` → `x86_64-apple-darwin`; `linux-x64` → `x86_64-unknown-linux-gnu`; `linux-arm64` → `aarch64-unknown-linux-gnu`. Minimum macOS and Linux/glibc versions remain test-gated; do not advertise compatibility until measured on the chosen builders and clean target systems.

Exclude example applications, source trees, compiler caches, build reports, development databases and local environment files. Retain examples in the source repository.

## Execution contract

Bind Java and Rust to loopback on different ports. Resolve packaged paths relative to the launcher, independent of the current directory. Keep mutable database, logs and process state in an explicit user data directory. Validate Java availability and port conflicts. Shutdown must target only processes started by the package. Java checks remain authoritative and Rust inference remains asynchronous, log-only and fail-open.

The current application defaults use relative contract/database paths; future launchers must override them. Proposed Rust invocation is `dcgaimodel serve-shadow-inference --artifact-root <absolute-package-root>/model --bind 127.0.0.1:8081`; Java uses loopback port 8080. No launcher is implemented by this document.

## Release gates still open

- Version alignment is complete. Record the exact resulting Java build commit in the Desktop plan after committing (a commit cannot contain its own SHA). Any subsequent Java build-input changes require explicitly replacing that pin.
- Implement archive assembly and launchers.
- Acquire and checksum-verify the exact Java distribution; validate the pinned Rust compiler against the lockfile and native targets.
- Run extracted-package tests outside source checkouts on each supported platform.
- Verify successful checks, inference, model outage/recovery, repeat startup and shutdown.
- Generate per-package SBOMs, checksums and license notices.
- Review final assets and release notes before publication.

The working progress checklist is on the maintainer's Desktop as `local-prerelease-plan.md`.
