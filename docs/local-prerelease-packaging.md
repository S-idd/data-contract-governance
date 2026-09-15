# Local prerelease packaging specification

Status (2026-09-15): release scope narrowed by the user to native macOS ARM64 and Linux x64 on AlmaLinux/WSL2 only. Mac acceptance passed and a passing WSL2 operator report was received. Report clarifications and redistribution/security review remain open; nothing published. Earlier four-platform build records below are historical, not publication commitments.

## 1. Publication version decision

Use `4.0.0-alpha.1` everywhere: Maven project/parent versions, release title and artifact filenames. The Git tag is `v4.0.0-alpha.1`. Later prereleases advance `alpha.N`, then `rc.N` when acceptance gates pass; the stable target is `4.0.0`. Never move historical tags.

Evidence: tags `v1.0` (d62c493d7b702b0d23af8e46276090b32e26b309), `V2.0` (21a372e4740831fb7419c30fff0720337070e6d3), and `v3.0` (7e3467a7b72c081a22298a00b577b64c22889195) all retain Maven `0.1.0-SNAPSHOT`. The POM retained it before the Step 1 alignment to 4.0.0-alpha.1. POM history traces that version to the initial project; it was not advanced alongside published tags. `v3.0` is an ancestor of the current Java baseline, not a disconnected release line. Its May 23, 2026 release notes describe this same product and name V4 as the next phase. The contradictory 0.x policy in `docs/release-and-versioning.md` was added later, in c965fa787fdf925c1c4561988c4f938f382c8cd7 on August 22.

Conclusion: Maven was stale; there is no evidence that v3.0 was abandoned. Continuing the documented V4 work as an alpha avoids regressing published version numbers and does not claim release-candidate readiness before package tests exist. All reactor project/parent versions, version/support documentation and changelog are now aligned with this decision. Renaming snapshot JARs is not version alignment.

Release-history reference: https://github.com/S-idd/data-contract-governance/releases/tag/v3.0

## Runtime distribution

Publish only `dcg-4.0.0-alpha.1-macos-arm64.tar.gz` for native Apple Silicon macOS and
`dcg-4.0.0-alpha.1-linux-x64.tar.gz` for the tested AlmaLinux/WSL2 x64 environment.
Intel macOS and Linux ARM64 are explicitly excluded; their existing local archives are
retained as build evidence and must not be attached to this prerelease. Their tests are
not release gates. WSL2 evidence does not certify bare-metal Linux or other distributions.
Require installed Java 21; recipients need no Maven, Cargo, Docker or source checkout.
This scope decision supersedes broader platform requirements in historical sections below.

## 2. Definitive macOS ARM64 manifest

Archive: `dcg-4.0.0-alpha.1-macos-arm64.tar.gz`. Its sole top-level directory is `dcg-4.0.0-alpha.1-macos-arm64/`. Every regular file beneath that directory is listed below. “Planned” means a specification, not an existing or verified deliverable.

| Exact relative filename | Source / status |
| --- | --- |
| `lib/contract-cli-4.0.0-alpha.1-all.jar` | Planned Maven shaded CLI output after version alignment |
| `lib/contract-service-4.0.0-alpha.1.jar` | Planned Spring Boot executable JAR after version alignment |
| `bin/dcgaimodel` | Planned Rust release executable, target `aarch64-apple-darwin` |
| `bin/dcg` | Implemented CLI launcher and shared shell functions |
| `bin/start` | Implemented paired-service launcher |
| `bin/stop` | Implemented owned-process shutdown |
| `bin/status` | Implemented status command |
| `model/data/inference/frozen-v9/policy-packs-v5-compositional.json` | Rust frozen policy, exact copy |
| `model/data/experiments/v9-multiclass-cpu-100e/models/seed-20260826-normal-family-split.json` | Rust seed model, exact copy |
| `model/data/experiments/v9-multiclass-cpu-100e/models/seed-20260827-normal-family-split.json` | Rust seed model, exact copy |
| `model/data/experiments/v9-multiclass-cpu-100e/models/seed-20260828-normal-family-split.json` | Rust seed model, exact copy |
| `config/application-local-demo.properties.example` | Implemented credential-free template; copied once to private runtime data |
| `contracts/policy-packs.json` | Java repository file, exact copy |
| `contracts/orders.created/metadata.yaml` | Java repository sample metadata, exact copy |
| `contracts/orders.created/v1.json` | Java repository sample schema, exact copy |
| `contracts/orders.created/v2.json` | Java repository sample schema, exact copy |
| `README.md` | Implemented standalone installation and operation instructions |
| `RELEASE-NOTES.md` | Implemented alpha limitations and rollback instructions |
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

- Java source preparation baseline: `71e0823ac359f63c536ef19f0b79352ba81c1ef5`. Final Java build commit for the next assembly (Tomcat 10.1.59): `4ca0fa42c769749f37fd1d5306bbf5b1c0054aa0`, pushed to origin/main. This supersedes `dac3ed509d03e1bef75c47b497ca80bbdd1f2e04`, which still describes the existing Step 7 archives. Build fresh Java JARs from the new immutable revision; do not relabel old binaries. Packaging-only files are independently hashed; any later Java build-input change requires a new pin and retesting.
- Java build and acceptance-test distribution: **Eclipse Temurin OpenJDK HotSpot 21.0.12.1+1**, compiling with Java release 21. macOS ARM64 upstream asset: `OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12.1_1.tar.gz`. Other targets use the corresponding `x64_mac`, `x64_linux` or `aarch64_linux` asset at the same release. Verify upstream archive checksums before use and record them in provenance. All four upstream archives were downloaded and checksum-verified in Step 2; macOS ARM64 was reverified before the Step 4 diagnostic build.
- Upstream Java release: https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.12.1%2B1 . Installed local Oracle Java 21.0.10 is not the locked release build distribution. Recipients install Java 21 separately; no JDK is bundled. Exact Temurin build is the acceptance baseline, not a claim that every Java 21 vendor has been tested.
- Rust source and frozen artifact revision: **`ef819fe58865c643a240ee067cf61d506f04a778`** in the companion `dcgaimodel` repository. Build the executable and copy all four frozen files from this same immutable commit, not `WEEK1`.
- Rust prerelease compiler: **1.96.0** (`ac68faa20`, 2026-05-25); use the explicit toolchain and Cargo lockfile (`--locked`). Record full `rustc -Vv` and Cargo version. The repository's `stable` toolchain and Dockerfile's Rust 1.88 builder are not this explicit native-package build assumption; do not inherit either implicitly. All four native targets compiled under this explicit pin in Step 3; compilation does not imply runtime acceptance.
- `build-info.json` must record publication version, Java preparation and final build SHAs, Rust SHA, target triple, Java vendor/full version and archive digest, full Rust/Cargo versions, source artifact hashes, and any packaging transformations. The final build SHA and binary hashes are build-time evidence, not invented planning values.

Target mapping: `macos-arm64` → `aarch64-apple-darwin`; `macos-x64` → `x86_64-apple-darwin`; `linux-x64` → `x86_64-unknown-linux-gnu`; `linux-arm64` → `aarch64-unknown-linux-gnu`. Minimum macOS and Linux/glibc versions remain test-gated; do not advertise compatibility until measured on the chosen builders and clean target systems.

Exclude example applications, source trees, compiler caches, build reports, development databases and local environment files. Retain examples in the source repository.

## Execution contract

Bind Java and Rust to loopback on different ports. Resolve packaged paths relative to the launcher, independent of the current directory. Keep mutable database, logs and process state in an explicit user data directory. Validate Java availability and port conflicts. Shutdown must target only processes started by the package. Java checks remain authoritative and Rust inference remains asynchronous, log-only and fail-open.

The launchers override relative contract/database paths. Rust invocation is `dcgaimodel serve-shadow-inference --artifact-root <absolute-package-root>/model --bind 127.0.0.1:8081`; Java uses loopback port 8080, including Actuator on the same listener. Credentials, copied config/contracts, SQLite, logs and PID/start-time/command records live outside the archive under DCG_DATA_DIR. Initial paired startup requires model readiness; later model outage is reported as degraded without stopping Java. No forced kills or deletion of persistent data are performed.

## 4. Implemented assembly and validation

Sources: `packaging/local/bin/{dcg,start,stop,status}`, `packaging/local/config/`, package README/release notes, and `scripts/release/assemble-local.py`. See [maintainer instructions](../scripts/release/README.md) for exact invocation and the required build-info input fields.

Assembly allowlists all 24 regular files in section 2, validates embedded Maven versions and native CPU/file format, requires matching input hashes and pinned source/toolchain metadata, reads frozen/source files directly from the pinned Git commits, and creates normalized tar.gz content plus internal/external SHA256SUMS. Output must be an empty directory; existing archives are not overwritten. Java/JDK compilation, Rust compilation, SBOM generation and publication are not hidden assembly steps. SBOM/notices must be real reviewed inputs; format checks are not a legal or completeness attestation.

Validation on 2026-09-13: 13 unit tests cover manifest identity, synthetic archive round trips, deterministic output/modes, checksums, wrong versions/architectures, missing and symlink inputs, provenance tampering, missing SBOM ecosystems, shell syntax, Java-major rejection and stale-PID safety. Real Java CLI/service JARs were built from the pinned Java commit using verified Temurin 21.0.12.1+1; the selected Maven reactor reported 231 tests, 212 passed, 19 skipped, zero failures/errors. The diagnostic macOS ARM64 layout uses the previously validated native Rust executable and pinned frozen models. Runtime smoke covers paths with spaces and a different working directory, CLI, readiness, repeat startup, authentication, Rust outage reporting while Java remains healthy, repeated shutdown, restart and retained credentials/contracts/database. Full cross-platform extracted-release acceptance is not claimed.

Step 4 used synthetic archives only inside unit tests. Step 5 now has real local-demo archives with actual binaries and generated dependency evidence; no fabricated SBOM/license inputs were used. All four JDK archives were checksum-verified in Step 2. The Linux builds were repeated with explicit Rust 1.96.0 overrides in Step 5 because a pinned Docker image alone does not override the repository's floating toolchain file. Minimum supported OS/glibc and redistribution/license review remain open gates.

## 5. Actual local packages — 2026-09-14

Output directory on the maintainer machine: `/Users/siddarthkanamadi/Desktop/dcg-local-prerelease-4.0.0-alpha.1`. Each platform subdirectory contains its tar.gz and external SHA256SUMS. All archives contain exactly the 24 section-2 regular files (23 internal checksum entries, excluding SHA256SUMS itself). The layout is identical across platforms apart from the top-level platform name, Rust executable and platform-specific provenance/SBOM.

| Archive filename | SHA-256 |
| --- | --- |
| dcg-4.0.0-alpha.1-macos-arm64.tar.gz | 75bbc6fe9386cb74ca87da3792eae483de6bd77a688eebeaef5b8080a995fa82 |
| dcg-4.0.0-alpha.1-macos-x64.tar.gz | fd9345e4fd1c95f155d0590c2b5229ed8e7d5a6f94ad59ebefbc81239d1c4f57 |
| dcg-4.0.0-alpha.1-linux-arm64.tar.gz | e89204fb7c72204c4539f6f9f3aaa68b270d1199803e166f1c354bdcfd956c22 |
| dcg-4.0.0-alpha.1-linux-x64.tar.gz | 3580ec599071df9601292fc8b9f995cd8ea395608077215e84adbcac925374e9 |

Each archive passed external checksum validation, extraction, all 23 internal checksums,
exact file allowlist/mode checks, CycloneDX 1.6 schema validation and provenance hash checks.
macOS ARM64 additionally passed the live smoke runner against the actual extracted archive.
The other three archives are built/checksummed, not runtime-certified. Linux uses Debian
Bookworm/glibc 2.36 builders; minimum OS compatibility is not inferred from this.

The SBOM includes 143 Maven components (including Boot-injected launcher/jarmode), 164 Cargo
components on macOS or 163 on Linux, and the Rust standard library. Build-only Cargo nodes
are marked excluded. Notices include source-pinned supplemental licenses and no unresolved
license-text inventory entries. MySQL Connector/J, Logback and Jakarta Annotation have
named/copyleft/exception licensing requiring review before public redistribution; this is
not legal approval. Full report and precise build/log locations are recorded in the Desktop
output directory's STEP-5-REPORT.md and the Desktop tracking plan.

## Release gates still open

Step 6 native macOS ARM64 acceptance passed on 2026-09-14 outside source checkouts,
including actual run-specific AI prediction/failure/recovery observations and unchanged Java
authoritative results, recovery without restarting Java, repeated lifecycle tests, port/PID
cleanup and SQLite/package integrity. The four launcher source filenames are now extensionless;
their bytes and every Step 5 archive hash are unchanged. A standalone target-host runner is
provided at `scripts/release/test-extracted-package.py`.

User availability is an Apple Silicon Mac and a Windows laptop with WSL, not an Intel Mac.
The WSL laptop test has not run in this task; its report must explicitly identify WSL2 rather
than bare-metal Linux. Intel macOS and Linux ARM64 remain untested without matching machines.
Full Step 6 is therefore incomplete; do not promote build/CI/emulation results to real-host
runtime support claims. See the Desktop output folder's STEP-6-REPORT.md and WSL test kit.

- Version alignment is complete. Record the exact resulting Java build commit in the Desktop plan after committing (a commit cannot contain its own SHA). Any subsequent Java build-input changes require explicitly replacing that pin.
- All four local archives assembled; public publication remains separate from this local build.
- JDK acquisition/checksums and Rust target compilation completed; retain and recheck provenance before release builds.
- Run extracted-package tests outside source checkouts on each supported platform.
- Verify successful checks, inference, model outage/recovery, repeat startup and shutdown.
- Per-package SBOMs, checksums and notices generated and technically validated; complete redistribution review before publication.
- Review final assets and release notes before publication.

The working progress checklist is on the maintainer's Desktop as `local-prerelease-plan.md`.

## Step 7 technical review — 2026-09-14

Finalized package README/release notes and reassembled four separate candidates in the
Desktop output folder's `step7-candidates/`. Real Step 5 SBOMs/notices/build evidence remain;
packaging hashes were recomputed. All 17 regression tests, four external/92 internal hashes,
CycloneDX 1.6 schemas, dependency references and provenance checks passed. Only README,
release notes, build-info and checksums changed. See `STEP-7-REPORT.md` for candidate hashes.
Original archives/test evidence remain untouched. Step 6, redistribution/security review
and final packaging commit remain open. Test the exact selected candidate before publishing;
use the new Linux x64 archive/checksum pair for WSL2. No tag/publication performed.
