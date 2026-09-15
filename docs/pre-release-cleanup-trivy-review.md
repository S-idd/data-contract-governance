# Prerelease cleanup and Trivy review — 2026-09-15

Release decision: NOT CLEAR. This scan is evidence for remediation, not release approval.

## Cleanup

Inspected 498 tracked files and ignore rules. No tracked generated logs, databases, classes
or target/cache directories were found. The Maven wrapper JAR is intentional. Preserved
examples, tests, source, local credentials and build/test evidence. Corrected stale local-only
Tomcat upgrade wording in security documentation/release notes. No destructive cleanup or
dependency changes performed. Packaging regressions: 18 passed; git diff --check passed.

## Scan scope and method

Trivy 0.72.0; vulnerability DB refreshed to UpdatedAt 2026-09-15 13:41:13 UTC.
No ignore-unfixed flag or severity exclusions were used. Findings were not suppressed.
Raw outputs/logs are private under
`/Users/siddarthkanamadi/Downloads/dcg-trivy-review-qntX2f`.

- Workspace filesystem: vulnerability, misconfiguration and secret scanners; .git and
  target directories excluded. Includes ignored local files, so it is not a release inventory.
- Tracked source: copied current tracked working-tree files outside the checkout and
  scanned with the same three scanners, without local environment files or Maven cache.
- Rust: exact Cargo.toml/Cargo.lock exported from ef819fe58865c643a240ee067cf61d506f04a778.
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
updated Rust lockfile scan returned zero findings. The workspace scan remains non-clean
because it includes ignored local credential-pattern files and deployment-file configuration
findings; neither belongs in the binary manifest. A final scan of regenerated selected
archives is still required. The Java remediation commit and Rust remediation commit are
separate from the package commit; existing archives remain unchanged.
