# Java identity during Rust advisory outage

## Investigation

In `scripts/release/test-extracted-package.py`, the step after “healthy AI prediction”
sources the packaged helper and runs `state_init; lock; stop_one rust`. It signals
only the saved Rust PID. It does not stop Java or remove Java's PID record. For the
`advisory-v2` status protocol, `expected_status_exit(protocol, "outage")` is **0**;
Java must stay RUNNING/ACTIVE while Rust is STOPPED and AI is UNAVAILABLE. The test
also compares Java's PID record before/after and submits an authoritative check.

The reported combination is consistent with **both process identity checks failing**:

1. `alive rust` returns false although Rust is still listening.
2. The old `stop_one rust` silently removes its PID record and returns success.
3. `alive java` also returns false; old status conflates failed identity, failed
   readiness, and actual process exit as `Java service: STOPPED`.
4. Cleanup removes Java's unrecognized PID record without signalling the JVM, then
   rejects its still-held port 8080.

The shared identity check compared `ps -o lstart=` calendar timestamps. The original
lifecycle patch did not introduce this check or the misleading status vocabulary;
both were present before it. Its Rust escalation path does not signal Java.

A controlled Linux regression changes only the calendar timestamp returned by a
`ps` shim, leaving the actual process, kernel start ticks, and listener unchanged.
Running the original `fca631b` helper and status against that fixture reproduced:

```text
stop_one rust: exit 0, Rust PID file deleted
status: exit 1
Java service: STOPPED
Deterministic enforcement: INACTIVE
AI advisory mode: UNAVAILABLE
Rust advisory process: NOT OWNED
Actual Java/Rust processes alive: true / true
cleanup: exit 1, port 8080 still has a listener
```

This demonstrates how loss of calendar identity reproduces the reported failure.
It does **not** establish that clock correction caused the user's WSL2 failure: saved/current
identity values from that failed host are still needed to confirm that diagnosis.
The reported cherry-pick `ccbf313` was unavailable from origin during investigation;
comparison used its original lifecycle patch `fca631b` and parent `2610f48`.

## Changes

Linux identity now combines kernel boot ID with field 22 of `/proc/PID/stat`, the
process start time since boot. It is independent of the displayed wall-clock date.
The parser handles spaces and parentheses in the process name. Other platforms
retain their existing identity mechanism. See the
[Linux kernel proc documentation](https://docs.kernel.org/filesystems/proc.html).

Status treats process ownership and health separately:

- Verified live Java + healthy endpoint: RUNNING / ACTIVE, exit 0, including AI outage.
- Verified live Java + failed endpoint: RUNNING / INACTIVE, exit 1, explicit health diagnostic.
- Live PID or listener with unverified ownership: UNVERIFIED / INACTIVE, exit 1,
  explicit identity diagnostic. It is never claimed to be stopped or adopted by port.
- No live tracked process or listener: STOPPED / INACTIVE, exit 1.

Stop preserves a live but unverified PID record and fails without signalling it.
It retains the verified TERM, 30-second wait, ownership-checked KILL, five-second
exit wait, and final listener check from the earlier patch. New acceptance runs
must use fresh state; old live calendar-based Linux records are not auto-adopted.
No Java/Rust application behavior or expected outage acceptance result was changed.

## Validation and WSL2 re-verification

The new Linux regression tests exercise calendar changes, scoped Rust shutdown,
unchanged Java identity and health, health-failure vocabulary, retained unverified
records, and `/proc` parsing. An opt-in integration test uses real DCG Java/Rust
artifacts: healthy prediction, calendar change, Rust-only outage, successful status
and idempotent start, unchanged authoritative check result, Java survival, and
cleanup releasing both ports.

Run the focused tests from source:

```bash
python3 -m unittest discover -s scripts/release -p test_advisory_outage_identity.py -v
python3 -m unittest discover -s scripts/release -p test_launcher_lifecycle.py -v
```

For the real integration test, set `DCG_TEST_SERVICE_JAR` to the service JAR and
`DCG_TEST_RUST_ROOT` to a directory containing `dcgaimodel` and `model/`. It copies
artifacts into temporary directories and skips occupied ports.

Validation here uses macOS and isolated Linux ARM64 containers, including Ubuntu
22.04 with Temurin 21.0.12+8. It is not the native 13-check suite and not Ubuntu
24.04 x86-64 WSL2 with Temurin 21.0.12.1+1. Rebuild with the updated source using
`build-development-linux-wsl2.py` and a **new build/work directory**, requiring its
13-check acceptance result to PASS before assembling and testing the full Ubuntu
evaluation bundle. Reusing a previous archive does not update its launchers.

Completed validation: five new Linux tests passed (including real inference), all
seven Linux lifecycle tests passed, six macOS lifecycle tests passed (real Java
opt-in skipped), 29 packaging tests passed, seven runner protocol tests passed,
and five evaluation-template tests passed. The original-helper reproduction was
run separately against the unchanged `fca631b` launcher files.
