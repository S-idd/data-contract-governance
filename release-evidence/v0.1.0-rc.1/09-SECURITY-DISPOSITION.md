# Final RC security-coverage disposition — DCG 4.0.0-rc.1

Reviewed 2026-09-17 (Asia/Kolkata) against **only** the accepted 4.0.0-rc.1 archive bytes and their embedded, matching RC SBOMs. The archives, JARs, Rust binaries, notices, dependencies, source pins, and contents were not changed. macOS ARM64 and Linux x86_64 WSL2 acceptance remain **PASS, 13/13 each** for these exact hashes (`07-PLATFORM-ACCEPTANCE.md`). No alpha SBOM or alpha report was used.

| Exact accepted archive scanned | SHA-256 | Exact embedded RC SBOM scanned | SBOM SHA-256 |
|---|---|---|---|
| `release-output/v0.1.0-rc.1/reviewed-macos-arm64/dcg-4.0.0-rc.1-macos-arm64.tar.gz` | `7921446b1efcc229c2fb02218f8a7cce4412d5b300f2215576c1f253df58be0e` | `/tmp/dcg-rc1-finalscan.PG2eHi/dcg-4.0.0-rc.1-macos-arm64/sbom.cdx.json` (identical to `release-output/v0.1.0-rc.1/work/evidence-macos-arm64/sbom.cdx.json`) | `ea96fa38270c00f6236903a323d68e7b76454bb9d0cb35301be7513b5a110d13` |
| `release-output/v0.1.0-rc.1/reviewed-linux-x64/dcg-4.0.0-rc.1-linux-x64.tar.gz` | `f3f39529704bf5eef3030c9d0e02ab03234778e60843487e8f7967c9ac07b588` | `/tmp/dcg-rc1-finalscan.PG2eHi/dcg-4.0.0-rc.1-linux-x64/sbom.cdx.json` (identical to `release-output/v0.1.0-rc.1/work/evidence-linux-x64/sbom.cdx.json`) | `d93a3d2f33ca1305f8bf7084f2cd3df8ef6da21142d706adddc7916b4457d325` |

Each exact archive was extracted afresh for scanning, and all 23 internal checksum entries verified. Embedded SBOM metadata identifies `dcg-local-demo` version `4.0.0-rc.1`. The pinned Java source export's root `pom.xml` matches Git commit `994770c97ed00d00b1a6bf974344a6c68c31d656` (SHA-256 `9b42219ddd7eab81504c96f0ecb7b3278da20ab8976dfbbd77fb486296192b56`); the pinned Rust export's `Cargo.lock` matches commit `32ca579095ed5b91749b8c33999556624e58758f` (SHA-256 `8459a7705e01ca131fcef14e0ea7df0bd1789cfcf00481e8e79651e23829e2b3`).

## Scanner and exact commands

Installed scanner: **Trivy 0.72.0**. At scan time (2026-09-16 18:41 UTC), vulnerability DB version 2 had `UpdatedAt=2026-09-16 13:02:23 UTC` and `NextUpdate=2026-09-17 13:02:23 UTC`; Java DB version 1 had `UpdatedAt=2026-09-15 01:01:41 UTC` and `NextUpdate=2026-09-18 01:01:41 UTC`. Refresh-check commands were run before the scans; these were the latest cached databases then available. A future publication decision must consider DB staleness at that later time.

All commands below exited 0. Relative paths are from the Java repository root. JSON reports are retained under `release-output/v0.1.0-rc.1/work/` and are not committed or published here.

```sh
trivy fs --download-db-only --quiet release-output/v0.1.0-rc.1/work/java-source
trivy fs --download-java-db-only --quiet release-output/v0.1.0-rc.1/work/java-source
trivy sbom --skip-version-check --scanners vuln,license --format json --output release-output/v0.1.0-rc.1/work/trivy-final-sbom-macos-arm64.json /tmp/dcg-rc1-finalscan.PG2eHi/dcg-4.0.0-rc.1-macos-arm64/sbom.cdx.json
trivy sbom --skip-version-check --scanners vuln,license --format json --output release-output/v0.1.0-rc.1/work/trivy-final-sbom-linux-x64.json /tmp/dcg-rc1-finalscan.PG2eHi/dcg-4.0.0-rc.1-linux-x64/sbom.cdx.json
trivy fs --skip-version-check --scanners vuln,secret,misconfig,license --skip-dirs '**/.git' --skip-dirs '**/target' --skip-dirs '**/.m2' --skip-dirs '**/build' --skip-dirs '**/.gradle' --skip-dirs '**/node_modules' --format json --output release-output/v0.1.0-rc.1/work/trivy-final-source-java.json release-output/v0.1.0-rc.1/work/java-source
trivy fs --skip-version-check --scanners vuln,secret,misconfig,license --skip-dirs '**/.git' --skip-dirs '**/target' --skip-dirs '**/.m2' --skip-dirs '**/build' --skip-dirs '**/.gradle' --skip-dirs '**/node_modules' --format json --output release-output/v0.1.0-rc.1/work/trivy-final-source-rust.json release-output/v0.1.0-rc.1/work/rust-source
trivy fs --skip-version-check --scanners secret,misconfig,license --format json --output release-output/v0.1.0-rc.1/work/trivy-final-archive-macos-arm64.json /tmp/dcg-rc1-finalscan.PG2eHi/dcg-4.0.0-rc.1-macos-arm64
trivy fs --skip-version-check --scanners secret,misconfig,license --format json --output release-output/v0.1.0-rc.1/work/trivy-final-archive-linux-x64.json /tmp/dcg-rc1-finalscan.PG2eHi/dcg-4.0.0-rc.1-linux-x64
```

No Compose or container image scan was run: neither is shipped in this local-demo prerelease. Source exclusions remove repository/build caches, **not** application source manifests. The source scans include non-distributed deployment/Docker configuration for visibility; those findings are separated below from packaged-archive findings.

## Results by coverage boundary

| Boundary | What Trivy actually recognized | Findings by severity | Disposition/limit |
|---|---|---|---|
| macOS RC SBOM dependency scan | 151 Cargo + 143 Maven packages; 294 package records and 298 license records | Vulnerabilities: 0 (HIGH 0, CRITICAL 0) | Every Maven/Cargo name+version in the embedded 295-component SBOM matched a Trivy package record. The remaining component is the Rust standard library, which Trivy does not map as a language package. |
| Linux RC SBOM dependency scan | 150 Cargo + 143 Maven packages; 293 package records and 297 license records | Vulnerabilities: 0 (HIGH 0, CRITICAL 0) | Every Maven/Cargo name+version in the embedded 294-component SBOM matched a Trivy package record; Rust standard library is not mapped. |
| Pinned Java source export | 11 POM language files; 571 package and 558 license records; config files also scanned | Vulnerabilities 0; secrets 0; misconfigurations: LOW 2, MEDIUM 1, HIGH 0, CRITICAL 0 | KSV-0020, KSV-0021 and KSV-0125 are in `deploy/kubernetes/mysql-private/deployment.yaml`, absent from both release archives. Record for future deployment hardening; not a packaged RC finding. |
| Pinned Rust source export | 1 Cargo.lock language file; 174 package records; Dockerfile scanned | Vulnerabilities 0; secrets 0; misconfigurations: LOW 1, HIGH 0, CRITICAL 0 | DS-0026 is a missing Dockerfile HEALTHCHECK; the Dockerfile/image is absent from both archives. Record for future container work; not a packaged RC finding. |
| Extracted macOS and Linux archive files | Text/config filesystem scanner ran; 0 language-specific files and 0 config files detected in each | 0 reported secrets, misconfigurations or license records; HIGH 0, CRITICAL 0 | This is **not** dependency coverage and does not scan binary JAR/Rust internals for secrets. Dependency findings are sourced from the RC SBOM scans above. |

Trivy 0.72.0 supports the vulnerability, license, secret and misconfiguration scanners used here. Imported SBOMs trigger Trivy's warning that vulnerability matching may be inaccurate; some SBOM hash algorithms are unsupported. Independently comparing every Maven/Cargo name+version between each embedded RC SBOM and its Trivy package records reduces the risk of a silently dropped dependency but does not prove the upstream SBOM itself complete or that no undisclosed vulnerability exists. Build provenance and the earlier packaging audit support, but do not cryptographically attest, the SBOM-to-binary relationship. External JDK, OS libraries and excluded Compose/images are not distributed or covered. The Rust standard library is recorded in the SBOM/license inventory but has no Trivy language-package vulnerability result. An empty archive filesystem language scan is explicitly **not** counted as dependency coverage.

## Gate decision

**No HIGH or CRITICAL findings were reported within the detected scope.** For the defined local-demo RC scope, the security-coverage gate is **PASS**: both exact embedded RC SBOMs were scanned with full Maven/Cargo name+version mapping; both pinned source trees were scanned with cache/build exclusions; and both extracted archive trees received secret/misconfiguration/license filesystem scans. The four LOW/MEDIUM source-configuration findings concern files absent from the shipped archives and are deferred to future deployment/container work, not silently ignored. This scoped PASS does **not** certify production deployment, unscanned Rust standard-library vulnerabilities, binary secrets, legal redistribution, or future DB results. If the archive bytes, SBOMs or source pins change, this disposition must be repeated.

The packaged notices have **technically complete collected license text** (the audit reports zero missing/invalid license texts), but structured attribution and corresponding-source obligations remain **LEGAL REVIEW REQUIRED**; this security decision is not human legal approval. No archive, notice, dependency, source or commit was changed for this report.

Platform acceptance: PASS
Notice text: TECHNICALLY COMPLETE
Security coverage: PASS
MySQL legal review: PENDING
Human legal approval: PENDING
Tagging/publication: NOT AUTHORIZED
