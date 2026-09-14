# Native Mac candidate acceptance — 2026-09-14

User-authorized scope: current native Mac, clean archive-only input location. This does not
claim a separate physical clean machine or certify Linux/Intel Mac.

Tasks 1 and 2: PASS on arm64, hardware identifier Mac16,11. Copied only the Step 7 ARM64
archive and its external SHA256SUMS into a new Downloads directory. shasum reported OK.
Extracted outside the checkout and moved to a renamed directory before invoking launchers.
Tested exact archive SHA-256:
`7efb51fd31cf4aaabfb0466567c66bef63e05042afb79c6e95528b6159444e2c`.

Full runner passed missing-Java actionable error (JAVA_HOME unset; PATH with required
utilities but no Java), restored Temurin 21.0.12.1+1 startup, loopback-only listeners,
authentication, repeat start, actual contract checks before/during/after Rust outage,
identical authoritative output and Java PID through recovery, scoped shutdown preserving
an unrelated test-owned sleep process, repeated cycles/status, external-state SQLite
integrity and unchanged package file inventory/digests. Scoped shutdown evidence includes
before/after PID/parent/command-name snapshots, without full process arguments.
All test-owned services and sentinel were stopped; private state was retained outside
the package. A first harness attempt omitted required utilities; the corrected run passed.
No package bytes were modified. Regression suite: 17 tests passed.

Local machine-readable evidence:
`/Users/siddarthkanamadi/Desktop/dcg-mac-mini-acceptance-20260914-final/acceptance-report.json`.
Private runtime logs/database/password must not be published.

Task 3: Java build commit remains `dac3ed509d03e1bef75c47b497ca80bbdd1f2e04`.
Packaging is versioned separately; committing packaging does not replace binary source pins.
No alpha tag is authorized until Linux, licensing and vulnerability gates are resolved.
