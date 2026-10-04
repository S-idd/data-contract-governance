# macOS Milestone 1 telemetry v2 — frozen 2026-10-04

This replacement archive includes simultaneous combined process RSS, continuous
native pressure/swap/VM sampling, a 30-second baseline, and automated memory
PASS/REVIEW/FAIL classification. Use it instead of the original Milestone 1 archive
for the physical 8GB MacBook test.

Archive: `dcg-macos-arm64-milestone-1-telemetry-v2.tar.gz`.
SHA-256: `b614ee8ed77097f421b2f58579ccc173c5711ea72e3833ace25d4685681171fc`.
Handoff: `release-output/macos-milestone-1-telemetry-v2-frozen/`.
That directory contains the archive, sidecar, shipped README, complete native
results, classifier validation, regression log and FREEZE.json. Its SHA256SUMS
verifies every handoff file. No Rust or Java rebuild occurred.

## Native acceptance

A fresh checksum-verified extraction outside the checkout, in a path with spaces,
passed all 21 functional checks on native ARM64 macOS, stock Bash 3.2 and Java 21.
The actual host has **24GiB RAM**, so this is not physical 8GB acceptance.

- `functional_status: PASS`
- `memory_qualification: PASS`
- `eight_gb_qualification: NOT_TARGET`
- Duration including baseline: 50.817 seconds.
- 30 baseline and 47 workload samples; no telemetry errors.
- Observed roles: CLI, Java service, Rust and helpers/children.
- Peak combined sampled RSS: 330,039,296 bytes (approximately 314.8 MiB).
- Largest observation gap: 1.0054 seconds.
- No warning/critical pressure; zero swap-used increase and swap-out delta.
- Scoped shutdown, retained-state checks and package immutability passed.

The full JSON contains every process/pressure sample and raw VM snapshots. Sampling
can miss sub-second peaks or very short-lived children; shared pages can be counted
more than once. System-wide pressure includes unrelated applications.

## Classifier proof against this archive

All **35 synthetic cases** were replayed using the classifier extracted from this
archive: **5 expected PASS, 23 expected REVIEW, 7 expected FAIL**. Every result and
CLI exit code matched. Tests feed synthetic observations and do not generate real
pressure or claim a physical-machine run. Full case results and reasons are in
`classifier-validation.json`.

Coverage includes RSS/swap/swapout boundaries, transient versus five-second warning
pressure, critical pressure, pressured/short baselines, missing samples, unknown
pressure levels, counter resets, page-size changes, missing process coverage,
duplicate PIDs, inconsistent sums, negative RSS, transient swap recovery, FAIL
precedence, and larger hosts remaining NOT_TARGET. Other unit tests cover process
reparenting/PID reuse, simultaneous sums versus independent peaks, native parsing
and functional failure aggregation.

Classifier SHA-256:
`5fa133f7e1ca867d0b77efad089be9487ad73c3e627cd2aa52393d7aa4a6f6bc`.
Regression discovery: **68 tests, 56 passed, 12 opt-in tests skipped**. The separate
live extracted-archive run supplies the native functional evidence above.

## Reviewer decision

Follow the new archive's README. Exit codes: 0 PASS, 1 FAIL, 2 REVIEW. The report
separates functional results and memory qualification and explains every non-pass.
On the physical MacBook, require `host.is_8gb_target: true`,
`functional_status: PASS` and `eight_gb_qualification: PASS`. RAM detection alone
does not distinguish a physical machine from an 8GiB VM; retain hardware context.

No Docker/IEMS/external database matrix is included. Gatekeeper remains a manual
quarantine exception. Physical 8GB macOS acceptance remains pending.
