# Version alignment plan — decision required for a possible RC

This is an evidence-only plan. No release scheme is selected here. No manifests, build inputs, binaries, archives, images, tags, or existing evidence were changed. The preceding [license inventory](01-LICENSE-INVENTORY.md) found **no final `v0.1.0-rc.1` archives**; all 26 located tarballs are `4.0.0-alpha.1` outputs.

## Verified starting point

| Source | Observed value | Consequence |
|---|---|---|
| Java `pom.xml` and all 10 module/example parent POMs | `4.0.0-alpha.1` | Maven and JAR names are V4 alpha, not 0.1.0 RC. |
| Rust `Cargo.toml` and `Cargo.lock` root package | `dcgaimodel` `0.1.0` | Crate is not marked RC; Rust already has a local `v0.1.0` tag. |
| Java `scripts/release/release-pins.json` | `version: 4.0.0-alpha.1`; Java build `d54a518c3b308d1c54a440f016d81086e0a73155`; Rust `32ca579095ed5b91749b8c33999556624e58758f` | Assembler derives its version and expected source revisions from this file; changing a filename alone will fail validation. |
| Existing `build-info.json` files under Downloads/Desktop | 33 inspected: 18 use the newer pinned Java/Rust pair, 15 the older pair; **all** say `4.0.0-alpha.1` | Generated provenance belongs to alpha builds; it must not be hand-renamed into RC provenance. |
| `packaging/local/bin/dcg` | `VERSION=4.0.0-alpha.1`; default data directory includes that version | Launcher JAR lookup and state isolation are version-sensitive. |
| Docker Compose | `dcg/contract-service:local`, `dcg/dcgaimodel:local`, `postgres:16`, `mysql:8.4` | These are mutable tags, not immutable RC image identities. |
| Dockerfiles/toolchains | Java Dockerfile copies `contract-service-4.0.0-alpha.1.jar`; Rust Dockerfile uses `rust:1.88-bookworm`, while release evidence pins Rust 1.96.0; `rust-toolchain.toml` says `stable` | A Compose/container RC needs its own toolchain and provenance reconciliation, not merely tag changes. |

The Java release policy in `docs/release-and-versioning.md` treats the earlier v1/v2/v3 tags as one product line and explicitly targets V4 alpha, then V4 RC. A proposed Java `0.1.0-rc.1` would reverse that documented direction. Conversely, a Rust `0.1.0-rc.1` sorts **before** its already-tagged `0.1.0` stable version. These are risks to resolve, not decisions made by this document.

## 1. Release-identity choices — select one before edits

| Choice | Git/release identity | Exact two local-demo archive names if selected | Main risk/open decision |
|---|---|---|---|
| **A — one unified RC** | `v0.1.0-rc.1` in both repositories; Maven and Rust package versions `0.1.0-rc.1` | `dcg-0.1.0-rc.1-macos-arm64.tar.gz`; `dcg-0.1.0-rc.1-linux-x64.tar.gz` | Java would move from V4 to 0.x despite v3.0/V4 history; Rust RC would postdate its stable `v0.1.0` tag. Requires explicit version-line rationale. |
| **B — separate Java/Rust RCs** | Java `v4.0.0-rc.1`/Maven `4.0.0-rc.1`; Rust `v0.1.0-rc.1`/Cargo `0.1.0-rc.1` | Combined Java-led package: `dcg-4.0.0-rc.1-macos-arm64.tar.gz`; `dcg-4.0.0-rc.1-linux-x64.tar.gz` | Two source tags/identities must map to one combined package; Rust's stable `v0.1.0` tag still precedes its proposed RC. Decide whether a **separate** Rust publication exists; if so, its proposed names are `dcgaimodel-0.1.0-rc.1-macos-arm64.tar.gz` and `dcgaimodel-0.1.0-rc.1-linux-x64.tar.gz` and need their own manifest/test contract. |
| **C — another version** | User must state exact Java version/tag, Rust version/tag, and combined-package version/tag. One possible family is Java `4.0.0-rc.1` with Rust remaining `0.1.0` at a new pinned commit, but this is **not** selected. | Exact filenames cannot truthfully be fixed until the combined-package version and platform scope are chosen. For a stated combined version `X`, the assembler's existing convention would yield `dcg-X-macos-arm64.tar.gz` and `dcg-X-linux-x64.tar.gz`. | Preserve coherent historical version ordering and say whether Rust is released independently or only embedded. |

The above assumes the previously tested two-platform scope: native macOS ARM64 and Linux x64 on AlmaLinux/WSL2. Adding other platforms would require a separate support and acceptance decision. A tag spelling is not sufficient evidence of a matching binary: the POM, Cargo metadata if applicable, pin manifest, JAR metadata, provenance, SBOM, notices, archive roots and filenames must agree.

## 2. Required edits **after** a choice is confirmed

This is a change map, not authorization to edit. An item marked **conditional** is needed only if that deliverable/scope is chosen. Historical alpha records should be preserved and annotated as historical, not silently rewritten.

| Area | Files/outputs to reconcile | Exact reason |
|---|---|---|
| Java Maven release identity | Java root `pom.xml`; `contract-core/pom.xml`, `contract-cli/pom.xml`, `contract-service/pom.xml`, `contract-validation-spring-boot-starter/pom.xml`, `contract-sdk/pom.xml`, `contract-build-support/pom.xml`, `contract-maven-plugin/pom.xml`, `contract-gradle-plugin/pom.xml`, `examples/dcg-spring-boot-realworld-demo/pom.xml`, `examples/spring-boot-realworld-demo/pom.xml` | All 11 project/parent declarations currently embed `4.0.0-alpha.1`. The `${project.version}` dependency references should remain derived; dependency versions should not change accidentally as part of product renaming. |
| Rust crate identity **if Rust version changes** | Rust `Cargo.toml`, `Cargo.lock` (`[[package]] name = "dcgaimodel"`), Rust `CHANGELOG.md` and release-facing README/metadata as applicable | `0.1.0` is currently encoded in both manifests; any RC version requires a regenerated lock entry and a documented relationship to existing `v0.1.0`. Never simply rename a binary. |
| Core/embedded user-facing version text | Java `contract-core/src/main/java/com/ideas/contracts/core/CompatibilityEngineIdentity.java` (development fallback), `contract-service/src/main/java/com/ideas/contracts/service/UiController.java` (CLI snippet) | Both contain literal `4.0.0-alpha.1` in observable behavior. |
| Local archive launcher and package documents | `packaging/local/bin/dcg`, `packaging/local/README.md`, `packaging/local/RELEASE-NOTES.md` | Launcher `VERSION`, default data path, JAR lookup, package title, support wording and rollback must match selected version. Other launchers source `bin/dcg` rather than defining a separate version. |
| Pin/provenance/assembly pipeline | `scripts/release/release-pins.json`; `scripts/release/collect-dependency-evidence.py`; `scripts/release/test_local_packaging.py`; `scripts/release/README.md`; `scripts/release/stage-local-inputs.py` and `scripts/release/assemble-local.py` **review/verify** | The pin manifest owns version and exact Java/Rust commits; collector hardcodes alpha app/component refs and JAR path; tests have alpha fixture expectations; staging emits generated `build-info.json` and status. The assembler already derives `VERSION`, JAR names and archive names from pins, so it may need no source edit, but its validations and 24-file manifest must be rechecked. New source revisions, hashes, logs, SBOMs, notices and build-info files must be **generated from actual new builds**. Do not modify old generated evidence in place. |
| Dockerfile/container pipeline **if images/Compose included** | Java `docker/contract-service.Dockerfile`; Rust `Dockerfile`, `rust-toolchain.toml`/build invocation; `docker-compose.yml`, `docker-compose.sqlite.yml`, `docker-compose.mysql.yml`, `docker-compose.shadow-inference.yml`; image build/publish workflow and environment templates as needed | Java Dockerfile copies an alpha-named JAR. Rust Dockerfile currently builds with 1.88 and floating `stable` override risk, unlike pinned 1.96. Compose uses mutable `:local`, `postgres:16`, `mysql:8.4`. Decide image registry/names, target architectures, immutable digests and whether Compose builds from source or consumes published images. If Compose is out of scope, do not present it as an RC asset. |
| Build, CI and demo entry points with literal alpha JAR/plugin names | `scripts/dcg.sh`; `scripts/ci/check-changed-contracts.sh`, `scripts/ci/demo-cli.ps1`; `.github/workflows/evidence-oidc-import.yml`; `scripts/demo/make-demo.ps1`, `run-local-demo.sh`, `run-sync-model-comparison.sh`, `run-sqlite-recovery-drill.sh`, `run-postgres-recovery-drill.sh`, `run-mysql-recovery-drill.sh`, `run-s3-recovery-drill.sh`; both example projects' `scripts/run-*.sh` files containing `4.0.0-alpha.1` | Their paths/coordinates would point to absent alpha artifacts after a new-version reactor build. Keep demonstration behavior the same unless separately approved. `scripts/perf/run-database-side-by-side-benchmark.sh` and MySQL recovery script also default to `mysql:8.4`; update to immutable digest only if Compose/container scope requires it. |
| Current release/support documentation | `docs/release-and-versioning.md`, `docs/support-policy.md`, root `README.md`, `CHANGELOG.md`, `docs/build-integrations.md`, `docs/Usermanual.md`, `docs/cli-walkthrough.md`, `docs/policy-packs.md`, `docs/version4-production-readiness-release-plan.md` | These contain current alpha version claims, usage commands, Maven/Gradle coordinates or support language. Set exact RC scope and distinguish local demo from production. |
| Historical release evidence/documentation | `docs/local-prerelease-packaging.md`, `docs/pre-release-cleanup-trivy-review.md`, `docs/release-notes-week8-rc.md`, `docs/week7-phase1-release-notes.md`, earlier archive checksums/reports, `release-evidence/v0.1.0-rc.1/01-LICENSE-INVENTORY.md` | Keep historical alpha checksums/version claims intact; add a prospective RC note or supersession pointer where necessary. An old acceptance report cannot be repurposed for new bytes. |

Additional text-only matches from the repository search (for review, not automatic replacement) include Rust experimental JSON/model data containing `0.1.0` strings and Rust `docs/oracle-data-generation.md`; these are data/provenance or historical material, not established crate-publication version knobs. Audit their meaning before editing. Tool/dependency versions (Java 21, Temurin 21.0.12.1+1, rustc 1.96.0, Tomcat 10.1.59, Spring Boot 3.5.15, etc.) must **not** be mechanically rewritten to match the product version.

Generated output names must be selected again from the chosen version: `contract-cli-<Java-version>-all.jar`, `contract-service-<Java-version>.jar`, `dcg-<combined-version>-<platform>.tar.gz`, platform-local `SHA256SUMS`, and fresh `build-info.json`, `sbom.cdx.json`, `THIRD-PARTY-NOTICES.txt`. The exact two platform archive names for choices A/B are in §1. Both old source pins and the staged `build-info.json` files under Downloads/Desktop currently describe alpha builds, so changing only `release-pins.json` would fail `stage-local-inputs.py`'s pinned-export checks.

## 3. Dependency and notice review before any final build/publication

The versions below are **observed in the latest alpha SBOMs and JARs**, not promises about a future RC. They remain exact review targets if dependencies are unchanged; a new RC SBOM must confirm them again.

| Dependency / source | Exact observed version | Where present | Review question |
|---|---|---|---|
| MySQL Connector/J (`com.mysql:mysql-connector-j`) | **9.7.0** | Java CLI shaded classes and service nested JAR; direct dependency in both POMs | GPLv2/Universal FOSS Exception applicability, corresponding source and notices for the chosen distribution. |
| Flyway MySQL (`org.flywaydb:flyway-mysql`) | **11.7.2** | CLI/service; direct dependency in both POMs | Verify its own license, dependency closure and notices; it is separate from Connector/J. |
| MySQL Docker image | **`mysql:8.4` tag**; immutable digest **not recorded** | `docker-compose.mysql.yml`; demo/performance defaults | Decide whether this image is in release scope. A mutable tag is insufficient for an immutable Compose release; image redistribution/source duties depend on distribution mode. |
| Logback classic and core | **1.5.34** in latest alpha (`1.5.32` in older alpha outputs) | Service JAR/SBOM; root POM override | Choose/document applicable dual-license route, notices and source availability. Do not use older alpha notices without checking exact JAR bytes. |
| Jakarta Annotation API | **2.1.1** | Service JAR/SBOM | Confirm EPL-2.0/secondary-license notices and source-availability method. |

The existing alpha `THIRD-PARTY-NOTICES.txt` files are **reference material only** for an RC. If exact dependency versions and artifact hashes remain identical, their license text can be cross-checked/reused as source input, but the RC aggregate notices, SBOM, license audit and provenance must be regenerated and reviewed against the new exact JARs/Rust binary. An alpha notice or zero-finding scan is not human legal approval. The `scripts/release/license-sources.json` supplemental-source pins must be revalidated if Rust dependencies change.

## 4. Compose/image-scope decision

| Choice | Conditional asset and required evidence | Risk/tradeoff |
|---|---|---|
| **No Compose archive** | Publish only the two selected-version platform tarballs and checksums. State explicitly that source-tree Compose files/images are not release assets. | Keeps the previously alpha-tested local-demo platform scope, but the new RC bytes still require fresh acceptance tests. Does not deliver a containerized binary experience. MySQL image remains a repo/demo reference, not bundled. |
| **Include Compose** | After selecting A: proposed `dcg-0.1.0-rc.1-compose.tar.gz`; after selecting B: proposed `dcg-4.0.0-rc.1-compose.tar.gz`. Define its exact file list, platform/registry scope, image names and per-platform immutable `@sha256:` digests for Java, Rust, PostgreSQL and (if offered) MySQL; record source/build provenance, SBOMs, notices, checksums, Compose smoke/rollback evidence. If distributing image tarballs instead of registry references, define and review those **additional** assets separately. | No Compose archive or verified immutable image set exists today; mutable `:local`, `postgres:16`, `mysql:8.4` and unaligned Docker build toolchains cannot be presented as RC-pinned. This substantially widens testing and redistribution scope. |

This document deliberately does not decide whether Compose belongs in the final release or whether Docker image tags should be replaced. Those are user release-scope decisions, not clerical edits.

## Decision requested from the owner

Confirm **A, B, or an exact C identity**, whether Rust receives an independent release/tag, whether the two-platform scope stays unchanged, and **Compose yes/no** (including registry versus image-tar distribution if yes). Only after those choices can implementation, exact-version builds, fresh platform acceptance, license sign-off and final asset verification be planned. Nothing in this document authorizes those actions.

Version alignment status: DECISION REQUIRED  
Final RC archives: NOT BUILT  
Human legal approval: PENDING  
Tagging and publication: NOT AUTHORIZED
