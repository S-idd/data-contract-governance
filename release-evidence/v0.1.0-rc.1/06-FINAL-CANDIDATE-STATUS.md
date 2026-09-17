# Reviewed RC candidates — gate status

This report supersedes the candidate hashes and notice findings in `05-BUILD-NOTICE-RESULTS.md`; that report and the original archives remain as historical evidence. Neither repository was committed, tagged, pushed, uploaded or published. No Java JAR or Rust executable was rebuilt. Java source pin `994770c97ed00d00b1a6bf974344a6c68c31d656`, Rust pin `32ca579095ed5b91749b8c33999556624e58758f`, and the release-pins file were verified before work. The original archives and all previously recorded SHA-256 values verified.

## Exact current candidate assets

| Target | Archive | SHA-256 | Acceptance |
|---|---|---|---|
| Native macOS ARM64 | `release-output/v0.1.0-rc.1/reviewed-macos-arm64/dcg-4.0.0-rc.1-macos-arm64.tar.gz` | `7921446b1efcc229c2fb02218f8a7cce4412d5b300f2215576c1f253df58be0e` | PASS, 13/13 on native Mac Mini |
| Linux x64 on WSL2 | `release-output/v0.1.0-rc.1/reviewed-linux-x64/dcg-4.0.0-rc.1-linux-x64.tar.gz` | `f3f39529704bf5eef3030c9d0e02ab03234778e60843487e8f7967c9ac07b588` | PENDING real WSL2 test |

Both external `SHA256SUMS` files verify. Each extracted archive has the expected 24 regular files and 23 verified internal checksum entries, its matching platform SBOM and notice, and no Compose or image assets. The CLI JAR remains SHA-256 `271c34c32ded6a8c08cdd8b9d4f083c2d37457c47f074f5703618be4ddb2ecb1`; service JAR remains `5a6bec25ef2d9022176e423ab4643fd88e92199804001ffbda99ca9b389ef712`; Rust binaries remain `c004f10655f1516ed5d4ac90b9cbd2b4fc6c79f3b809447466f0adebfb0a906c` (macOS) and `994e9adb98f5572e04ca0a169e5a80d9f86472d1d9c711438630daf8e6366cd4` (Linux). `python3 -m unittest discover -s scripts/release -p 'test_*.py' -q`: **20/20 PASS**. Original archives were not overwritten. Current copies have reviewed SBOM/notice/provenance inputs; no archive was manually renamed.

## Notice and dependency review

The generated inventories cover 143 Maven entries and 151 macOS / 150 Linux Cargo entries, plus Rust standard library (295 / 294 total). The reviewed notices contain no NUL/binary `.class` data. The broken Jakarta BOM text was removed, and the exact Jakarta Annotation API 2.1.1 JAR's `META-INF/LICENSE.md` was verified to contain the GPL/classpath-exception text; its source SHA-256 is recorded in the license audit. Three source URLs were resolved: tiny-keccak 2.0.2 from its Cargo homepage metadata, and Spring Boot loader/jarmode-tools 3.5.15 from the official Spring Boot source tag. Both platform audits now report zero missing license texts, zero invalid texts and zero missing upstream source URLs. Root-level notice copies exist in both repositories; each archive contains its platform-specific notice.

The exact packaged dependency list and versions are in each `sbom.cdx.json`, with per-component declared license, source registry URL and verbatim text in each `THIRD-PARTY-NOTICES.txt`. MySQL Connector/J 9.7.0 (GPLv2 with Universal FOSS Exception 1.0), Flyway MySQL 11.7.2 (declared Apache-2.0), Logback classic/core 1.5.34 (declared EPL-2.0 and LGPL-2.1-only), and Jakarta Annotation API 2.1.1 (declared EPL-2.0 and GPLv2 with classpath exception) were individually inspected in the generated BOM and license sources. This automated inventory is **not** a human legal review of every obligation. Structured copyright fields remain unsupported/UNKNOWN for 295 / 294 components, and corresponding-source instructions have not been resolved. No attribution was invented. Notice completeness is **BLOCKED**; see `LEGAL-REVIEW-REQUEST.md`. MySQL redistribution needs an explicit human determination; its driver was not removed. The MySQL image is not part of this release.

## Security scan coverage

Trivy 0.72.0 vulnerability and Java databases were refreshed/checked before rescanning (vulnerability DB updated 2026-09-16 13:02 UTC; Java DB updated 2026-09-15 01:01 UTC). Reports are `release-output/v0.1.0-rc.1/work/trivy-{reviewed-package-*,reviewed-sbom-*,source-*}.json`.

| Input | Scanners / actual coverage | Findings and limitation |
|---|---|---|
| Both extracted reviewed packages | `fs`: vuln, misconfig, secret, license | 0 result records; 0 language-specific files. Confirms the scanner ran on package files, **not** dependency-vulnerability coverage. |
| Both final platform SBOMs | `sbom`: vuln, license; Cargo + Java recognized | 294 macOS / 293 Linux package records; 298 / 297 license records; 0 reported vulnerabilities. Trivy warns that imported third-party SBOMs may be inaccurate and some hashes are unsupported. |
| Pinned Java source export, excluding `**/target` and `.git` | `fs`: vuln, misconfig, secret, license; 11 POM language files | 571 package records, 558 license records, 0 vulnerabilities, 0 secrets; 3 misconfiguration findings (`KSV-0020` LOW, `KSV-0021` LOW, `KSV-0125` MEDIUM) in non-distributed `deploy/kubernetes/mysql-private/deployment.yaml`. Source scan includes development/deployment code, not just shipped bytes. |
| Pinned Rust source export, excluding `**/target` and `.git` | `fs`: vuln, misconfig, secret, license; 1 Cargo.lock language file | 174 package records, 0 vulnerabilities, 0 secrets; 1 LOW `DS-0026` finding in non-distributed Dockerfile. Large oracle audit JSON triggered a secret-scanner size warning. |

No Compose image was scanned or distributed. The source configuration findings and SBOM/import coverage limits need review. A zero-result package filesystem scan is not a PASS for dependency coverage. Security scans remain **BLOCKED** as a release gate, notwithstanding zero reported dependency vulnerabilities in the SBOM/source scans.

## Acceptance and next operator step

The exact reviewed macOS archive passed 13/13 extracted-package checks outside the checkout on native macOS ARM64. Report: `/private/tmp/dcg-rc1-reviewed-accept.Om3pqW/run/acceptance-report.json`; its archive hash is `7921446b1efcc229c2fb02218f8a7cce4412d5b300f2215576c1f253df58be0e`. It covered startup/authentication, healthy shadow AI, Java-authoritative fail-open outage and recovery, repeat lifecycle, scoped cleanup, persistence and immutable files.

The Linux archive is **not accepted** until the exact reviewed file is transferred to the real Windows/WSL2 laptop with `scripts/release/test-extracted-package.py` (runner SHA-256 `1ca0cec104a1234f30073d404eae32814e29fecade55e8eca5019d8d37b6ccc9`) and run there. No macOS or Docker execution substitutes. Once both files are in `~/dcg-final-test` on WSL2, and Temurin JDK 21.0.12.1+1 is at `~/jdk21/jdk-21.0.12.1+1`, use:

```sh
cd ~/dcg-final-test
printf '%s  %s\n' 'f3f39529704bf5eef3030c9d0e02ab03234778e60843487e8f7967c9ac07b588' 'dcg-4.0.0-rc.1-linux-x64.tar.gz' | sha256sum -c -
printf '%s  %s\n' '1ca0cec104a1234f30073d404eae32814e29fecade55e8eca5019d8d37b6ccc9' 'test-extracted-package.py' | sha256sum -c -
test -x "$HOME/jdk21/jdk-21.0.12.1+1/bin/java"
test_parent=$(mktemp -d "$HOME/dcg-rc1-reviewed-accept.XXXXXX")
python3 test-extracted-package.py --archive "$PWD/dcg-4.0.0-rc.1-linux-x64.tar.gz" --java-home "$HOME/jdk21/jdk-21.0.12.1+1" --work-dir "$test_parent/run" --machine-description 'Windows laptop WSL2 Linux x64'
cat "$test_parent/run/acceptance-report.json"
```

Before running, make sure ports 8080/8081 are free and that the specified JDK path matches the actual extracted JDK; do not substitute a container. Return the JSON report and the two `sha256sum -c` outputs. If the archive changes after this test, acceptance must be repeated on the changed exact bytes.

Remaining blockers: WSL2 acceptance; human legal approval of all shipped notices/attributions/corresponding-source duties, especially MySQL; and disposition of scan coverage/config findings. These candidates must not be tagged or published.

Final archives: CANDIDATES
Notice files: BLOCKED
Linux WSL2 acceptance: PENDING
Security scans: BLOCKED
MySQL status: LEGAL REVIEW REQUIRED
Human legal approval: PENDING
Tagging and publication: NOT AUTHORIZED
