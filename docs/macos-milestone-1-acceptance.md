# macOS ARM64 Milestone 1 — 2026-10-03

Implemented and locally accepted: deterministic CLI/service plus AI advisory.
The macOS bundle contains no Docker, IEMS, database matrix or Newman workflow.
Embedded SQLite remains part of the Java service.

## Exact artifact

- Archive: `dcg-macos-arm64-milestone-1.tar.gz` (approximately 97 MiB).
- SHA-256: `803853956b74ef591f76f763649e6dfb85a42bd30e2ed6f07159dd5ce408f707`.
- Local artifact directory: `release-output/macos-milestone-1-r2/`.
- Matching checksum sidecar and the shipped README accompany the handoff.
- Original source: `dcg-4.0.0-phase2-service-advisory-dev.20260918-r3-macos-arm64`.
- Reused Rust SHA-256: `5932bb21d15fbb6b19e3a534437a3f3f082c23f91dbe7f1422e4c67fff2327f8`.
- Rust and Java were not rebuilt. Original development provenance is preserved;
  `bundle-info.json` separately records launcher replacements and reused artifacts.

## Observed validation

The archive and sidecar were copied outside the checkout, verified with
`shasum -a 256 -c`, and extracted under a fresh path containing spaces:
`/Users/siddarthkanamadi/Desktop/DCG Mac Milestone 1 gfoenz1d/`.

`private-evidence/results.json` reports PASS for 21 Milestone 1 check groups in
19.707 seconds. These are focused assertions, not the Ubuntu 23-check matrix.
They include deterministic CLI PASS/FAIL, no-AI startup/status/shutdown, loopback
listeners, authenticated UI, repeat-start PID stability, repeat stop, restart with
AI AVAILABLE and an actual advisory prediction, unchanged authoritative results,
retained credentials/contracts/check history, embedded SQLite integrity, unrelated
sentinel survival, released ports/PID files, and unchanged package bytes/inventory.

Host: Mac16,11, native arm64, **24 GiB RAM**, macOS 27.0 (26A428), stock Bash
3.2.57, native Oracle Java 21.0.10. This is **not acceptance on the target 8GB MacBook**.
The report records `host.is_8gb_target: false`.

Default JVM options: `-Xms64m -Xmx512m -XX:MaxMetaspaceSize=192m`.

| Measurement | Observed value |
| --- | --- |
| Deterministic startup | 2.224 s |
| Restart with AI | 3.289 s |
| Java service peak sampled RSS | 316,736 KiB (309.3 MiB) |
| Rust peak sampled RSS | 11,872 KiB (11.6 MiB) |
| Host swap used, before / after | 0 / 0 MiB |
| System-wide free-memory percentage, before / after | 81% / 79% |

RSS was sampled once per second for tracked service processes. It is not an exact
lifetime peak, excludes CLI JVMs, and does not predict memory pressure on an 8GB host.
The final check confirmed ports 8080/8081 had no listeners. Private state is retained
outside the source checkout; passwords and runtime logs are not part of the archive.

An initial acceptance attempt used `/contracts` for the unauthorized-access assertion;
this build intentionally permits catalog reads. The final runner correctly tests `/ui`
(401 unauthenticated, 200 authenticated). The failed attempt's scoped cleanup passed.

Regression discovery: 61 tests, **49 passed and 12 opt-in tests skipped**. A separate
real extracted-archive run supplied the local runtime evidence above. Regression
coverage includes stock Bash empty-array shutdown, input tampering, excluded matrix
files, Intel/Rosetta rejection, IEMS timeout/success PID retention, and injected partial
startup failures for both acceptance runners. No live IEMS or database matrix ran.

## Remaining target validation

Run the shipped README sequence on the 8GB Apple Silicon MacBook using this exact
archive and sidecar. Return its private `results.json` after review. Compare host RAM,
startup durations, RSS, memory pressure and swap. No new platform minimum or sustained
load claim is made. Gatekeeper removal is documented; a browser-download Gatekeeper
rehearsal and Developer ID/notarization were not performed in this round.
