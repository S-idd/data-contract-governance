# Version alignment results — selected scheme C

This records the committed active-source version alignment and its separate source-pin update. Both repository roots were verified with `git rev-parse --show-toplevel` and both worktrees were clean before editing. No tag, push, publication, upload, final archive, final scan, or real third-party notice file was created. Historical alpha archives and evidence were not changed.

Java source/alignment commit: `994770c97ed00d00b1a6bf974344a6c68c31d656` (`release: align Java version to 4.0.0-rc.1`). The release-pin commit is the commit containing this update to `03-VERSION-ALIGNMENT-RESULTS.md` and `scripts/release/release-pins.json`; obtain its exact SHA with `git rev-parse --verify HEAD` after committing. A commit cannot contain its own full SHA in its tree without changing that SHA, so that SHA is recorded in the final execution report, not fabricated here.

## Selected identity and exact values

| Item | Previous | Current / selected |
|---|---|---|
| Java reactor, CLI/service JARs, launchers, active docs, release version | `4.0.0-alpha.1` | `4.0.0-rc.1` |
| Future Java tag | `v4.0.0-alpha.1` was the prior plan | `v4.0.0-rc.1` **planned only; not created** |
| Rust Cargo version | `0.1.0` | `0.1.0`, unchanged; no independent Rust release |
| Rust source pin | `32ca579095ed5b91749b8c33999556624e58758f` | Same exact commit; embedded shadow-only component |
| Java build commit pin | `d54a518c3b308d1c54a440f016d81086e0a73155` | `994770c97ed00d00b1a6bf974344a6c68c31d656`, the exact committed RC source revision. |
| Supported target map | macos-arm64, macos-x64, linux-arm64, linux-x64 | macos-arm64 (`aarch64-apple-darwin`), linux-x64 (`x86_64-unknown-linux-gnu`) only |
| Compose asset | Possible in previous planning | Excluded; Compose files untouched |

The prior pin's Java SHA demonstrably contained `<version>4.0.0-alpha.1</version>` in its root POM. The new pin resolves to the committed root POM with `<version>4.0.0-rc.1</version>`. The earlier Java HEAD was `68aa161426bfa6380dc63406c3e141a875cb4c6d`; it is not the RC source revision. No package is accepted solely because the pin now resolves: actual RC build outputs and staged provenance remain unavailable.

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
| Pin and release tooling | `scripts/release/release-pins.json`; `scripts/release/collect-dependency-evidence.py`; `scripts/release/test_local_packaging.py`; `scripts/release/README.md` | Active release version, JAR paths and SBOM refs alpha → RC; target map narrowed to two; packaging test translates the historical alpha layout to the active version without rewriting the historical specification. Java SHA is now pinned to the first commit. |
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
| `./mvnw -B -ntp -o validate` after Java pin edit | PASS: all 11 Maven modules remain `4.0.0-rc.1`; no JAR built. |
| `python3 -m unittest discover -s scripts/release -p 'test_local_packaging.py' -v` after Java pin edit | PASS: 16/16 tests; only synthetic temporary fixtures. |
| `python3 scripts/release/stage-local-inputs.py --help` | PASS: staging accepts only `{macos-arm64,linux-x64}`; no staging output produced. |
| Read-only assembler/pin preflight using `assembly.git_file`, SHA-256, and assertions against `release-pins.json` | PASS: exact Java source commit contains RC root POM, Rust commit matches, Java policy checksum matches, all four frozen Rust artifact hashes match, and only two targets are configured. Pin-manifest SHA-256: `78839ffe1ead841766b2da54b3ab50a2cd83a3a1e4abcd49c473e612af8dba5e`. Full `stage-local-inputs.py` cannot run without actual RC build logs/JARs/binary and is deferred. |

## Remaining blockers and required follow-up

1. Record the exact release-pin commit after this separate commit lands. The Java source SHA is already pinned to the first commit; do not substitute a branch name or historical alpha SHA. `scripts/release/README.md` line 27 still says the pin is pending: that was true in the first commit but is stale after this update. The user restricted the second commit to only the pin file and this report, so correct that active documentation in a separately authorized follow-up before relying on it.
2. Keep Rust pinned at `32ca579095ed5b91749b8c33999556624e58758f`; verify that exact commit and frozen artifact hashes again when building. No Rust tag/version change is needed. The pinned Rust toolchain lacks `cargo-fmt`/rustfmt locally; install that component and run the formatting check in a later authorized validation step without changing the toolchain version.
3. Build genuinely new RC JARs and Rust binaries only in a later authorized step; generate new provenance, SBOM and reviewed aggregate notices for their actual bytes. Existing alpha JARs, SBOMs, notices, scans and acceptance reports cannot be relabeled.
4. Run fresh macOS ARM64 and WSL2 Linux x64 extracted-package acceptance and release/security/legal review before any tag or publication. MySQL Connector/J 9.7.0, Flyway MySQL 11.7.2, Logback and Jakarta Annotation API still require redistribution review. No human legal approval has been recorded.

Version alignment: BLOCKED
Final archives: NOT BUILT
THIRD-PARTY-NOTICES.txt: NOT CREATED
MySQL legal review: PENDING
Human legal approval: PENDING
Tagging and publication: NOT AUTHORIZED
