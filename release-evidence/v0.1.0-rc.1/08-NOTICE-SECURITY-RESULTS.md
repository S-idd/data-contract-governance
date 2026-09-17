# Notice and security review of accepted 4.0.0-rc.1 archive bytes

Review date: 2026-09-17 (Asia/Kolkata). This is an evidence review, not legal clearance. The accepted archives, binaries, JARs, dependency versions, source pins, and packaged notices were **not changed**. The two 13/13 real-host acceptance results in `07-PLATFORM-ACCEPTANCE.md` remain applicable to these exact bytes; no repackaging or repeat acceptance was triggered.

| Accepted target | Archive | Recalculated SHA-256 |
|---|---|---|
| Native macOS ARM64 | `release-output/v0.1.0-rc.1/reviewed-macos-arm64/dcg-4.0.0-rc.1-macos-arm64.tar.gz` | `7921446b1efcc229c2fb02218f8a7cce4412d5b300f2215576c1f253df58be0e` |
| Linux x86_64 on WSL2 | `release-output/v0.1.0-rc.1/reviewed-linux-x64/dcg-4.0.0-rc.1-linux-x64.tar.gz` | `f3f39529704bf5eef3030c9d0e02ab03234778e60843487e8f7967c9ac07b588` |

## Notice and complete component/version inventory boundary

Both root-level `THIRD-PARTY-NOTICES.txt` files are byte-identical to the macOS platform notice, SHA-256 `deafccec618c145c42abb3afe5225222d97732ec291af065af8c54e5e4241e0e`. The accepted macOS archive contains that exact notice; the accepted Linux archive contains its target-specific notice, SHA-256 `56da5233b7df1116f7e19c93aebd6784e93903d2bc07c506dbeeacdd40423204`. Each archive notice matches its evidence input. Neither contains NUL/binary `.class` content or `404: Not Found` text. Both still say `NOTICE COMPLETENESS: BLOCKED`, accurately flagging legal review.

The exact, full component-name/version/purl/license inventories—not a selective summary—are the `components` arrays in the accepted archives' `sbom.cdx.json` files and identical evidence files at `release-output/v0.1.0-rc.1/work/evidence-{macos-arm64,linux-x64}/sbom.cdx.json`:

| Platform | SBOM SHA-256 | Component audit | License/source audit |
|---|---|---|---|
| macOS ARM64 | `ea96fa38270c00f6236903a323d68e7b76454bb9d0cb35301be7513b5a110d13` | 295 unique purls: 143 Maven, 151 Cargo, 1 Rust stdlib; all have exact versions and declared licenses | 616 source-text records; 0 missing/invalid license texts; 0 missing upstream source URLs; 295 structured attributions UNKNOWN |
| Linux x64 | `d93a3d2f33ca1305f8bf7084f2cd3df8ef6da21142d706adddc7916b4457d325` | 294 unique purls: 143 Maven, 150 Cargo, 1 Rust stdlib; all have exact versions and declared licenses | 614 source-text records; 0 missing/invalid license texts; 0 missing upstream source URLs; 294 structured attributions UNKNOWN |

Cargo build-only/proc-macro components are marked `scope: excluded` (25 on each platform) and kept in the notice inventory conservatively; they should not be misrepresented as linked runtime code. The complete human-readable per-component list is in each platform notice. This automated completeness audit is **not** a determination that every attribution, license choice, source-offer duty, or redistribution condition is satisfied.

Previously known defects are already corrected in the accepted bytes: the invalid Jakarta BOM license URL is not reproduced; the exact packaged Jakarta Annotation API 2.1.1 JAR supplies `META-INF/LICENSE.md` (SHA-256 `6e1f002892b81cbe0647019b150c8a056efc1add565671a4f8af629b6cd2cc7b`) containing EPL-2.0 and GPLv2/classpath-exception text and `META-INF/NOTICE.md`. The three missing source URLs are supplied by tiny-keccak 2.0.2 Cargo homepage metadata and the official Spring Boot 3.5.15 source tag for loader/jarmode-tools. No copyright was invented. Unresolved structured attribution and corresponding-source instructions remain **LEGAL REVIEW REQUIRED**.

Individually inspected required dependencies: MySQL Connector/J **9.7.0** is in both Java artifacts; its exact JAR `LICENSE` (SHA-256 `75d3a92b08c7bf9476bab5a8661e3abedc081463d85c93251aab60a5e0ad92ce`) names GPLv2 with Universal FOSS Exception 1.0 and Oracle attribution. [Oracle's exception text](https://oss.oracle.com/licenses/universal-foss-exception/) requires human assessment for this exact shaded/nested distribution and any complete-corresponding-source conditions; applicability is **not approved** here. Flyway MySQL **11.7.2** is declared Apache-2.0, with its BOM-supplied license text and [upstream project](https://github.com/flyway/flyway); exact binary notice duties still need human review. Logback classic/core **1.5.34** declare EPL-2.0 and LGPL-2.1-only; [Logback's license page](https://logback.qos.ch/license.html) describes a licensee choice, but the choice and duties for these packaged JARs are unresolved. Jakarta Annotation API **2.1.1** declares EPL-2.0 and GPLv2 with classpath exception; the exact JAR text and [upstream project](https://github.com/jakartaee/common-annotations-api) corroborate this, but its choice and notice duties remain for review. The MySQL 8.4 Docker image and Compose distribution are excluded; no image is scanned or cleared.

## Fresh security scans and actual coverage

Trivy **0.72.0** was run on 2026-09-17 local time. `trivy fs --download-db-only --quiet` and `trivy fs --download-java-db-only --quiet` checked/updated the cache before scanning. At scan time the vulnerability DB was version 2, updated **2026-09-16 13:02:23 UTC**, next update **2026-09-17 13:02:23 UTC**; Java DB version 1, updated **2026-09-15 01:01:41 UTC**, next update **2026-09-18 01:01:41 UTC**. No newer DB was offered then. The exact accepted archives were extracted afresh under `/tmp/dcg-rc1-scan-20260917.wQ5NkV/`; both extracted trees passed all 23 internal SHA256SUMS entries. Source-export root `pom.xml` and `Cargo.lock` bytes were checked against the pinned Java `994770c97ed00d00b1a6bf974344a6c68c31d656` and Rust `32ca579095ed5b91749b8c33999556624e58758f` commits.

Commands (all exited 0; JSON reports below are under `release-output/v0.1.0-rc.1/work/`):

```sh
trivy fs --download-db-only --quiet release-output/v0.1.0-rc.1/work/java-source
trivy fs --download-java-db-only --quiet release-output/v0.1.0-rc.1/work/java-source
trivy fs --skip-version-check --scanners vuln,misconfig,secret,license --format json --output release-output/v0.1.0-rc.1/work/trivy-20260917-package-macos-arm64.json /tmp/dcg-rc1-scan-20260917.wQ5NkV/dcg-4.0.0-rc.1-macos-arm64
trivy fs --skip-version-check --scanners vuln,misconfig,secret,license --format json --output release-output/v0.1.0-rc.1/work/trivy-20260917-package-linux-x64.json /tmp/dcg-rc1-scan-20260917.wQ5NkV/dcg-4.0.0-rc.1-linux-x64
trivy sbom --skip-version-check --scanners vuln,license --format json --output release-output/v0.1.0-rc.1/work/trivy-20260917-sbom-macos-arm64.json release-output/v0.1.0-rc.1/work/evidence-macos-arm64/sbom.cdx.json
trivy sbom --skip-version-check --scanners vuln,license --format json --output release-output/v0.1.0-rc.1/work/trivy-20260917-sbom-linux-x64.json release-output/v0.1.0-rc.1/work/evidence-linux-x64/sbom.cdx.json
trivy fs --skip-version-check --scanners vuln,misconfig,secret,license --skip-dirs '**/target' --skip-dirs '**/.git' --format json --output release-output/v0.1.0-rc.1/work/trivy-20260917-source-java.json release-output/v0.1.0-rc.1/work/java-source
trivy fs --skip-version-check --scanners vuln,misconfig,secret,license --skip-dirs '**/target' --skip-dirs '**/.git' --format json --output release-output/v0.1.0-rc.1/work/trivy-20260917-source-rust.json release-output/v0.1.0-rc.1/work/rust-source
```

Direct `trivy fs` vulnerability/license scans of the extracted CLI and service JAR paths were also attempted; JSON reports are `trivy-20260917-jar-{cli,service}.json`, and **both detected zero language-specific files**. They do not independently validate nested/shaded dependencies.

| Target scanned | Detected coverage | Findings | Limitation |
|---|---|---|---|
| Extracted macOS and Linux accepted packages | 0 language files; 0 JSON result records each | No reported vulnerability, misconfiguration, secret or license record | An empty language-file scan is **not** dependency coverage. |
| Final macOS and Linux CycloneDX SBOMs | Java and Cargo recognized; 294/293 package records, 298/297 license records | 0 reported vulnerabilities; 0 HIGH/CRITICAL | Imported SBOM warning and unsupported hash-algorithm warnings; dependent on inventory completeness and database currency. |
| Pinned Java source export | 11 POM files; 571 package records, 558 license records | 0 vulnerabilities, 0 secrets; 3 Kubernetes misconfigurations: KSV-0020 LOW, KSV-0021 LOW, KSV-0125 MEDIUM | Findings are in non-distributed `deploy/kubernetes/mysql-private/deployment.yaml`; source scan also includes development/examples. |
| Pinned Rust source export | 1 Cargo.lock; 174 package records | 0 vulnerabilities, 0 secrets; Dockerfile DS-0026 LOW | Dockerfile is non-distributed; `target` and `.git` excluded. |

**No HIGH or CRITICAL findings were reported within the detected scan scope.** This does not prove absence outside that scope. Security gate remains **BLOCKED** pending review of scan coverage/import warnings and explicit disposition of the four non-distributed configuration findings. No dependency or source change is authorized here.

## Release consequences

Notice text/URLs were already fixed before the accepted archive hashes were tested; this review changed **no notice bytes**. Therefore the repackaging rule was not triggered and both existing 13-check platform passes remain valid. A future notice change would require reassembling from the same verified JARs/Rust binaries, new checksums, structural tests, and **both** real-host 13-check suites on the new bytes. If a source pin, dependency, or build input must change, stop before rebuilding and obtain a new release decision.

Platform acceptance: PASS
Notice status: LEGAL REVIEW REQUIRED
Security scans: BLOCKED
MySQL status: LEGAL REVIEW REQUIRED
Human legal approval: PENDING
Tagging/publication: NOT AUTHORIZED
