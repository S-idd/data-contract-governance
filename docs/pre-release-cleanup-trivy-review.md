# Prerelease cleanup and Trivy review — 2026-09-15

Release decision: NOT CLEAR FOR PUBLIC RELEASE. The regenerated local-demo candidates pass
technical archive checks and package scans, but Linux WSL2 acceptance of these exact rebuilt
bytes, redistribution/license review, and final operator approval remain release gates.

## Cleanup

Inspected 498 tracked files and ignore rules. No tracked generated logs, databases, classes
or target/cache directories were found. The Maven wrapper JAR is intentional. Preserved
examples, tests, source, local credentials and build/test evidence. Corrected stale local-only
Tomcat upgrade wording in security documentation/release notes, upgraded the resolved Java
dependencies, updated the Rust lockfile, and refreshed the pinned license-source map. No
destructive cleanup was performed. Packaging regressions: 18 passed; git diff --check passed.

## Scan scope and method

Trivy 0.72.0; vulnerability DB refreshed to UpdatedAt 2026-09-15 13:41:13 UTC.
No ignore-unfixed flag or severity exclusions were used. Findings were not suppressed.
Raw outputs/logs are private under
`/Users/siddarthkanamadi/Downloads/dcg-trivy-review-qntX2f`.

- Workspace filesystem: vulnerability, misconfiguration and secret scanners; .git and
  target directories excluded. Includes ignored local files, so it is not a release inventory.
- Tracked source: copied current tracked working-tree files outside the checkout and
  scanned with the same three scanners, without local environment files or Maven cache.
- Rust: exact Cargo.toml/Cargo.lock exported from 32ca579095ed5b91749b8c33999556624e58758f.
- Java: copied newly built CLI/service JARs for direct rootfs vulnerability scanning.
  An initial fs scan detected zero language files and is NOT counted as a clean JAR scan.

## Findings already established

Initial tracked source scan: 94 vulnerability occurrences (2 critical, 28 high, 48 medium, 16 low),
3 Kubernetes misconfigurations (1 medium, 2 low), zero detected secrets. Counts include
repeated dependencies across modules, not 94 unique CVEs or demonstrated exploits.

Critical finding: netty-handler 4.1.132.Final, CVE-2026-75595; scanner lists fixes
4.1.137.Final or 4.2.17.Final. Direct ZIP inspection confirmed 4.1.132.Final is actually
bundled in the service JAR, despite the top-level netty.version property. Resolve dependency
management and compatible fixed versions, then rebuild/test/rescan; do not merely edit a
property and assume it controls imported BOM dependencies. Tomcat core/EL/WebSocket are
confirmed 10.1.59 in the same JAR.

Other source findings involve Netty, Logback, HttpComponents, Log4j API and Spring Boot.
Rust lockfile remediation updated Parquet 57 to 60, removed Thrift 0.17.0, and passed
313 Rust tests (311 library passed, 2 ignored; integration tests passed). A fresh Rust
lockfile scan returned zero findings. Reachability and upgrade compatibility are not
established by a scanner result alone.
Kubernetes configuration findings concern UID/GID <=10000 and registry restrictions; these
deployment files are not part of the local binary manifest, but remain source-scan findings.

The broad workspace scan additionally flagged two possible AWS credentials in ignored .env
and three Maven settings patterns in ignored .m2. Values were not printed or copied into
this report. Treat .env findings as potentially real: owner should verify privately and
rotate/revoke if genuine and exposed. No claim of leakage or validity has been established.
Do not upload source.json or the entire scan workspace publicly without redaction.

After remediation, direct scans of the rebuilt Java JARs returned zero findings and the
updated Rust lockfile scan returned zero findings. The current tracked-source scan returned
zero vulnerabilities and secrets; its three Kubernetes configuration findings remain outside
the local binary manifest. The broad workspace scan remains non-clean because it includes
ignored local credential-pattern files and deployment-file configuration findings; neither
belongs in the binary manifest. No ignored credential values were copied into a package.

## Regenerated candidate validation — 2026-09-15

Fresh archives were assembled outside the source checkout from Java
`d54a518c3b308d1c54a440f016d81086e0a73155` and Rust
`32ca579095ed5b91749b8c33999556624e58758f`:

The release-pin manifest is `scripts/release/release-pins.json`, SHA-256
`5b95d7b5deb33b092c51d95f07c05306ece00317a8230f7fb64ceac12244080a`.

- macOS ARM64: `dcg-4.0.0-alpha.1-macos-arm64.tar.gz`, SHA-256
  `a482b84b481d4f4c4e29a1b9639e1a730850620d95f0224e5f21769d0ae2582e`; 24 payload files
  and 23 internal checksum entries.
- Linux x64: `dcg-4.0.0-alpha.1-linux-x64.tar.gz`, SHA-256
  `5712d8d5101f070d33badcb1bc3e3235bc7be12247f3888b42edab70bc4d76da`; 24 payload files
  and 23 internal checksum entries.

Both extracted candidates passed checksum verification, SBOM graph/reference validation,
and Trivy 0.72.0 scans with vulnerability, misconfiguration, and secret counts all zero.
The macOS ARM64 regenerated candidate also passed native CLI, loopback readiness, repeat
start/stop, and clean-shutdown checks. Linux WSL2 acceptance must be rerun by the operator
against this regenerated archive; the earlier WSL2 report applies to the prior archive only.

Candidate files and raw scan output are private under
`/Users/siddarthkanamadi/Downloads/dcg-final-release-20260915`; they are not repository
artifacts and were not committed.

## Manual inference process follow-up — 2026-09-16

The Linux x64 WSL2 report for SHA-256
`5712d8d5101f070d33badcb1bc3e3235bc7be12247f3888b42edab70bc4d76da`
confirmed that a Rust process started manually during outage/recovery remained on port
8081 after `bin/stop` reported success. The launcher now identifies an untracked
listener, stops it only if its full command matches the inference binary and model path
in this extracted package, and returns a failure for any unrelated listener.

The replacement candidate requires a new archive hash and WSL2 acceptance report. Its
macOS ARM64 counterpart passed full native extracted-package acceptance, including the
new manual-process check. Linux x64 passed an emulated smoke test of the same behavior;
that is not a substitute for WSL2 acceptance.

Final replacement archive digests after the launcher and release-note changes:

- macOS ARM64: `e484257de452c77f7dc1655bbfec85351825748393e497e1087f2d6f0d58cb6f`;
  full standalone native acceptance report status `PASS`.
- Linux x64: `4a935b4d69140c632aeb965dfff5e05ddfa803b3eaabe83b2f8bfd2c84c9d68b`;
  external/internal checksums and an extracted-package Trivy scan passed with zero findings.
  WSL2 acceptance for this exact archive is pending.
