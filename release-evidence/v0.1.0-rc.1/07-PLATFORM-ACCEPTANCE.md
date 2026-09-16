# Two-platform extracted-package acceptance — DCG 4.0.0-rc.1

The exact reviewed RC archives passed the same 13-check extracted-package suite on their advertised real target environments. This records runtime acceptance, **not** legal or publication approval. Neither archive, its binaries/JARs, notices, SBOM, dependencies, or packaging was changed for this evidence record.

| Platform | Environment | Exact reviewed archive | Archive SHA-256 | Result |
|---|---|---|---|---|
| macOS ARM64 | Native Mac Mini; `aarch64-apple-darwin` | `release-output/v0.1.0-rc.1/reviewed-macos-arm64/dcg-4.0.0-rc.1-macos-arm64.tar.gz` | `7921446b1efcc229c2fb02218f8a7cce4412d5b300f2215576c1f253df58be0e` | **PASS, 13/13** |
| Linux x86_64 | Windows laptop WSL2; kernel `6.18.33.2-microsoft-standard-WSL2`; `x86_64-unknown-linux-gnu` | `release-output/v0.1.0-rc.1/reviewed-linux-x64/dcg-4.0.0-rc.1-linux-x64.tar.gz` | `f3f39529704bf5eef3030c9d0e02ab03234778e60843487e8f7967c9ac07b588` | **PASS, 13/13** |

The Linux result comes from the unchanged `linux-acceptance-report.json` in this directory, copied byte-for-byte from `/Users/siddarthkanamadi/Downloads/acceptance-report(1).json`; both source and copy have SHA-256 `3ba06f1d924cb764d358a131cf307c584490ee75784d8213e40fd2ad35ef8b83`. Its reported archive hash equals the locally recalculated reviewed Linux archive hash above. The report identifies WSL2 rather than Docker or macOS emulation, records package relocation before startup, and records Temurin `21.0.12.1+1`. The earlier failed precheck caused by a missing external `SHA256SUMS` is superseded by this successful run; it was not a package failure.

The macOS acceptance report remains at `/private/tmp/dcg-rc1-reviewed-accept.Om3pqW/run/acceptance-report.json` (SHA-256 `50c46e3ddd768227afe67feaf9e8e40f5fd6f19b23d4928630df257bd3aa18d0`). It records the reviewed macOS archive hash above, native ARM64 execution, package relocation, Temurin `21.0.12.1+1`, and **PASS, 13/13**. Both platform reports name the same test runner, `scripts/release/test-extracted-package.py`, SHA-256 `1ca0cec104a1234f30073d404eae32814e29fecade55e8eca5019d8d37b6ccc9`, matching the currently verified runner file.

All 13 named checks passed on each platform: archive checksum/extraction/native-target match; actionable error when Java is absent; CLI, Java/Rust startup/readiness, loopback listeners and authentication; repeat start without duplicate processes; healthy AI prediction; AI outage preserving the Java-authoritative result; AI recovery without restarting Java; scoped shutdown preserving an unrelated process; clean and repeated shutdown releasing ports and process records; two additional start/stop cycles; detection and cleanup of a manually started relative-path Rust listener; and persistent-data integrity with immutable package files. The shadow AI remained logging-only/fail-open for authoritative results during the outage test.

Acceptance is complete **only for these exact archive hashes**. Repackaging after a notice/document change creates new archive bytes and requires repeating both 13-check real-host suites. The outstanding release gates remain: verified third-party attributions/corresponding-source obligations and MySQL human legal review; documented security-scan coverage and finding disposition; then written human legal approval. No tag, push, upload, publication, or GitHub release is authorized by this evidence.

Platform acceptance: PASS
Notices: BLOCKED
Security scans: BLOCKED
MySQL legal review: PENDING
Tagging/publication: NOT AUTHORIZED
