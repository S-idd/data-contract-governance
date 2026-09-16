# Version alignment results — selected scheme C

This records uncommitted, active-source version alignment only. Both repository roots were verified with `git rev-parse --show-toplevel` and both worktrees were clean before editing. No tag, push, publication, upload, final archive, final scan, or real third-party notice file was created. Historical alpha archives and evidence were not changed.

## Selected identity and exact values

| Item | Previous | Current / selected |
|---|---|---|
| Java reactor, CLI/service JARs, launchers, active docs, release version | `4.0.0-alpha.1` | `4.0.0-rc.1` |
| Future Java tag | `v4.0.0-alpha.1` was the prior plan | `v4.0.0-rc.1` **planned only; not created** |
| Rust Cargo version | `0.1.0` | `0.1.0`, unchanged; no independent Rust release |
| Rust source pin | `32ca579095ed5b91749b8c33999556624e58758f` | Same exact commit; embedded shadow-only component |
| Java build commit pin | `d54a518c3b308d1c54a440f016d81086e0a73155` | **Still the prior alpha SHA, not a valid RC build pin.** Replace with the exact post-alignment Java commit after one is made. No future SHA was invented. |
| Supported target map | macos-arm64, macos-x64, linux-arm64, linux-x64 | macos-arm64 (`aarch64-apple-darwin`), linux-x64 (`x86_64-unknown-linux-gnu`) only |
| Compose asset | Possible in previous planning | Excluded; Compose files untouched |

The current pin's Java SHA demonstrably contains `<version>4.0.0-alpha.1</version>` in its root POM. Until the pin is corrected, `stage-local-inputs.py` would reject any newly exported RC source, and the assembly pipeline must not be used to claim RC provenance. The current pre-edit Java HEAD was `68aa161426bfa6380dc63406c3e141a875cb4c6d`; it is **not** the future Java build commit.

Expected future filenames, derived from the active assembler and pins (not yet created):

- `contract-cli-4.0.0-rc.1-all.jar`
- `contract-service-4.0.0-rc.1.jar`
- `dcg-4.0.0-rc.1-macos-arm64.tar.gz`
- `dcg-4.0.0-rc.1-linux-x64.tar.gz`

No Compose archive and no standalone Rust archive are in this asset set. Java remains authoritative; the Rust integration remains asynchronous, logging-only, shadow-only and fail-open. No dependency coordinates or versions, including MySQL Connector/J and Flyway MySQL, were changed.

## Every changed file

All paths are relative to the verified Java repository root. No Rust file changed.

| Purpose | Files | Old → new |
|---|---|---|
| Maven reactor version | `pom.xml`; `contract-core/pom.xml`; `contract-cli/pom.xml`; `contract-service/pom.xml`; `contract-validation-spring-boot-starter/pom.xml`; `contract-sdk/pom.xml`; `contract-build-support/pom.xml`; `contract-maven-plugin/pom.xml`; `contract-gradle-plugin/pom.xml`; `examples/dcg-spring-boot-realworld-demo/pom.xml`; `examples/spring-boot-realworld-demo/pom.xml` | Project/parent `4.0.0-alpha.1` → `4.0.0-rc.1` in all 11. No dependency version edits. |
| Embedded version/CLI text | `contract-core/src/main/java/com/ideas/contracts/core/CompatibilityEngineIdentity.java`; `contract-service/src/main/java/com/ideas/contracts/service/UiController.java` | Version fallback and CLI JAR example alpha → RC. No inference behavior changed. |
| Package launcher/templates | `packaging/local/bin/dcg`; `packaging/local/README.md`; `packaging/local/RELEASE-NOTES.md` | Launcher version/state directory and active package instructions alpha → RC; historical alpha candidate notes retained and labeled as historical. |
| Pin and release tooling | `scripts/release/release-pins.json`; `scripts/release/collect-dependency-evidence.py`; `scripts/release/test_local_packaging.py`; `scripts/release/README.md` | Active release version, JAR paths and SBOM refs alpha → RC; target map narrowed to two; packaging test translates the historical alpha layout to the active version without rewriting the historical specification. Java SHA remains unresolved. |
| CI/build entry points | `.github/workflows/evidence-oidc-import.yml`; `scripts/dcg.sh`; `scripts/ci/check-changed-contracts.sh`; `scripts/ci/demo-cli.ps1` | Active Maven plugin/JAR references alpha → RC. |
| Demo entry points | `scripts/demo/make-demo.ps1`; `scripts/demo/run-local-demo.sh`; `scripts/demo/run-mysql-recovery-drill.sh`; `scripts/demo/run-postgres-recovery-drill.sh`; `scripts/demo/run-s3-recovery-drill.sh`; `scripts/demo/run-sqlite-recovery-drill.sh`; `scripts/demo/run-sync-model-comparison.sh`; `examples/dcg-spring-boot-realworld-demo/scripts/run-breaking-path.sh`; `examples/dcg-spring-boot-realworld-demo/scripts/run-happy-path.sh`; `examples/spring-boot-realworld-demo/scripts/run-breaking-path.sh`; `examples/spring-boot-realworld-demo/scripts/run-dcg-service.sh`; `examples/spring-boot-realworld-demo/scripts/run-happy-path.sh`; `examples/spring-boot-realworld-demo/scripts/run-webhook-receiver.sh` | Active CLI/service/example JAR references alpha → RC. MySQL image/dependencies untouched. |
| Active user/release docs | `README.md`; `CHANGELOG.md`; `docs/Usermanual.md`; `docs/build-integrations.md`; `docs/cli-walkthrough.md`; `docs/policy-packs.md`; `docs/release-and-versioning.md`; `docs/support-policy.md`; `docs/version4-production-readiness-release-plan.md` | Active commands/coordinates/version/support scope alpha → selected RC. Historical alpha changelog entry retained; RC is not described as tested or published. |
| This new result record | `release-evidence/v0.1.0-rc.1/03-VERSION-ALIGNMENT-RESULTS.md` | New report; neither earlier evidence file edited. |

The Java Dockerfile retains an alpha JAR path because Compose/container assets were expressly excluded and Docker/Compose files were not authorized for this change. Do not treat source-tree container configuration as RC-ready. The historical `docs/local-prerelease-packaging.md` still records the alpha manifest; it was not rewritten.

## Safe validation performed

| Command | Result |
|---|---|
| `git rev-parse --show-toplevel` and `git status --porcelain=v1` in both repositories, before editing | Correct roots; both clean. |
| `./mvnw -B -ntp -o validate` | PASS: all 11 Maven reactor modules modeled as `4.0.0-rc.1`; no JAR built. |
| `cargo +1.96.0 metadata --locked --offline --no-deps --format-version 1` | PASS: Rust package remains `dcgaimodel` `0.1.0`. |
| `python3 -m unittest discover -s scripts/release -p 'test_local_packaging.py' -v` | Initial run 15/16 passed; historical manifest version mismatch found. After test alignment, PASS: 16/16. Synthetic temporary fixture archives only; no release archive. |
| `python3 scripts/release/assemble-local.py --help` | PASS: platform choices are exactly `{macos-arm64,linux-x64}`. Assembler was not invoked to package files. |
| `git diff --name-only -- '*.sh' | while IFS= read -r file; do bash -n "$file" || exit 1; done` | PASS: changed shell scripts parse. |
| `git diff --check` | PASS: no whitespace errors. |
| `cargo +1.96.0 fmt --all -- --check` | NOT RUN TO COMPLETION: pinned toolchain lacks `cargo-fmt`/rustfmt. No Rust source changed. |

## Remaining blockers and required follow-up

1. Commit the Java alignment only after reviewing this diff. Record its exact resulting full SHA; update `java_build_commit` in `scripts/release/release-pins.json` in a subsequent change, and record the pin-manifest revision/hash used for builds. Do not substitute the old alpha SHA or a guessed future value.
2. Keep Rust pinned at `32ca579095ed5b91749b8c33999556624e58758f`; verify that exact commit and frozen artifact hashes again when building. No Rust tag/version change is needed.
3. Build genuinely new RC JARs and Rust binaries only in a later authorized step; generate new provenance, SBOM and reviewed aggregate notices for their actual bytes. Existing alpha JARs, SBOMs, notices, scans and acceptance reports cannot be relabeled.
4. Run fresh macOS ARM64 and WSL2 Linux x64 extracted-package acceptance and release/security/legal review before any tag or publication. MySQL Connector/J 9.7.0, Flyway MySQL 11.7.2, Logback and Jakarta Annotation API still require redistribution review. No human legal approval has been recorded.

Version alignment: BLOCKED
Final archives: NOT BUILT
THIRD-PARTY-NOTICES.txt: NOT CREATED
MySQL legal review: PENDING
Human legal approval: PENDING
Tagging and publication: NOT AUTHORIZED
