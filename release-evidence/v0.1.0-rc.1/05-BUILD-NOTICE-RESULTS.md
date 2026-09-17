# RC build and notice results — 4.0.0-rc.1

These are actual RC build candidates, not release-approved assets. No tag, push, upload, publication, dependency change, Compose archive or Docker image distribution was performed. Historical alpha archives and evidence were not modified.

## Provenance and outputs

| Item | Result |
|---|---|
| Java source | `994770c97ed00d00b1a6bf974344a6c68c31d656`; Maven 4.0.0-rc.1; Temurin 21.0.12.1+1 macOS ARM64 archive verified against pinned SHA-256 |
| Rust source | `32ca579095ed5b91749b8c33999556624e58758f`; Cargo 0.1.0; rustc 1.96.0 commit `ac68faa20c58cbccd01ee7208bf3b6e93a7d7f96` |
| Targets | native macOS ARM64 (`aarch64-apple-darwin`); Linux x64 (`x86_64-unknown-linux-gnu`) for WSL2 |
| Java JARs | `contract-cli-4.0.0-rc.1-all.jar` SHA-256 `271c34c32ded6a8c08cdd8b9d4f083c2d37457c47f074f5703618be4ddb2ecb1`; `contract-service-4.0.0-rc.1.jar` SHA-256 `5a6bec25ef2d9022176e423ab4643fd88e92199804001ffbda99ca9b389ef712` |
| Rust binaries | macOS ARM64 SHA-256 `c004f10655f1516ed5d4ac90b9cbd2b4fc6c79f3b809447466f0adebfb0a906c`; Linux x64 SHA-256 `994e9adb98f5572e04ca0a169e5a80d9f86472d1d9c711438630daf8e6366cd4` |
| macOS archive | `release-output/v0.1.0-rc.1/dcg-4.0.0-rc.1-macos-arm64.tar.gz`; SHA-256 `48a8331652ed95331bc5d9760fb612daff9165cd662a945470a8abd0cf0cf97d` |
| Linux archive | `release-output/v0.1.0-rc.1/dcg-4.0.0-rc.1-linux-x64.tar.gz`; SHA-256 `1a5e80bb22940295207aefbbe70511df8634044205ed98e3e6fcf2659ab08701` |

Both archives were produced by the RC staging/assembly pipeline, not by renaming alpha outputs. External SHA256SUMS verification and all 23 internal checksum entries per archive passed. The 24-file inventory is in `ARCHIVE-CONTENTS.txt`; exact hashes are in `CHECKSUMS.txt`. Both contain platform-specific SBOMs, top-level third-party notices, the Rust license, four frozen Rust artifacts, and release documentation. No Compose/container/database images are packaged. The sole `4.0.0-alpha.1` text hit in readable archive files is an explicitly historical note in `RELEASE-NOTES.md`; active RC metadata has no alpha references.

## Validation performed

- `cargo +1.96.0 fmt --all -- --check`: **PASS**, after installing only the matching rustfmt component; toolchain unchanged.
- `cargo +1.96.0 test --locked`: **PASS**, including 313 unit tests and integration tests.
- `cargo +1.96.0 clippy --locked --all-targets -- -D warnings`: **PASS**.
- `cargo +1.96.0 build --release --locked --target aarch64-apple-darwin`: **PASS**. Linux x64 release build with Rust 1.96.0 and `--locked` in an existing amd64 builder: **PASS**.
- `./mvnw -B -ntp clean verify` with pinned Temurin 21 on pinned Java source: **PASS**, all 11 modules. CycloneDX aggregate Maven BOM generation: **PASS**. Target-filtered Cargo metadata collected for both platforms.
- Release staging/provenance and assembly validations: **PASS** for both targets; exact Java/Rust pins and input artifact hashes recorded in package `build-info.json`.
- macOS ARM64 extracted-package acceptance on native Mac Mini outside the checkout: **PASS**, 13/13 checks covering checksum/extraction, Java prerequisite, startup/auth, healthy AI, fail-open outage/recovery, repeat lifecycle, scoped shutdown, relocation and immutable package. Local runner report: `/private/tmp/dcg-rc1-accept.rNUZGa/run/acceptance-report.json`. The Linux x64 archive has **not** been run on the real WSL2 target; builder/container execution is not acceptance evidence. Two-platform acceptance remains **BLOCKED**.
- Trivy 0.72.0 `fs` with vulnerability, misconfiguration, secret and license scanners on both extracted archives: command **PASS**, but reported zero language-specific files / zero result records, so it cannot prove dependency coverage. Trivy `sbom` with vulnerability and license scanners on both generated SBOMs: command **PASS**, 0 reported vulnerabilities, 298 macOS / 297 Linux license records. The scanner warns that imported SBOMs may be inaccurate; this is not security clearance. Final security review remains **BLOCKED**.

## Notice audit and release blockers

Root-level `THIRD-PARTY-NOTICES.txt` files were generated in both repositories; each platform archive contains its own notice. The inventory records 143 Maven components and 151 macOS / 150 Linux Cargo components (295 / 294 total, counting the Rust stdlib entry). It names exact versions, declared licenses, registry/source URLs when available and extracted license text. However, **notice completeness is BLOCKED**: Jakarta Annotation API 2.1.1 GPL/classpath-exception text is an invalid `404: Not Found`; structured copyright/attribution is unavailable for 295/294 entries; three upstream source URLs are unresolved; corresponding-source instructions have not been reviewed. The notices explicitly mark uncertainty rather than inventing facts.

MySQL Connector/J 9.7.0 and Flyway MySQL 11.7.2 are in the final Java artifacts. Logback classic/core 1.5.34 and Jakarta Annotation API 2.1.1 also require review. MySQL Connector/J's GPLv2/Universal FOSS Exception 1.0 position needs a human legal determination for these exact JAR forms; see `LEGAL-REVIEW-REQUEST.md`. No MySQL driver was removed. The MySQL 8.4 image is outside this package and is not cleared.

Required next work: correct and revalidate notice texts/attribution/source obligations, obtain human legal approval, run the exact Linux x64 archive on the user's WSL2 host and retain its acceptance report, then review scan coverage and any resulting rebuilt archive/checksum changes. Until then, the two physical archives are build candidates only and cannot be tagged or published.

Final archives: BUILT (candidates; publication blocked)
Notice files: BLOCKED
Rust rustfmt: PASS
Two-platform acceptance: BLOCKED
Security scans: BLOCKED
MySQL status: LEGAL REVIEW REQUIRED
Human legal approval: PENDING
Tagging and publication: NOT AUTHORIZED
