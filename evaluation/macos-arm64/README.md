# DCG macOS ARM64 — Milestone 1, telemetry v2

Unpublished evaluation bundle for native Apple Silicon only. Includes deterministic
CLI/service checks and AI advisory startup/prediction. No Intel/Rosetta support,
Docker, IEMS, PostgreSQL, MySQL or Newman. The Java service uses embedded SQLite;
CLI fixture checks do not record to a database.

Prerequisites: native ARM64 Java 21 and Python 3.10 or newer. macOS provides Bash,
curl, lsof, shasum and memory measurement tools. No Rust compiler, Maven, Homebrew
GNU utilities or Docker is needed. Set `JAVA_HOME` if Java 21 is not your default:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
```

## Verify and extract a new download

Place the archive and matching `.sha256` sidecar in Downloads. These commands
deliberately extract outside a source checkout, into a path containing spaces.
Choose a new parent directory for each run; acceptance rejects an existing workspace.

```bash
cd "$HOME/Downloads"
shasum -a 256 -c dcg-macos-arm64-milestone-1-telemetry-v2.tar.gz.sha256
RUN_ROOT=$(mktemp -d "$HOME/Downloads/DCG Mac evaluation.XXXXXX")
tar -xzf dcg-macos-arm64-milestone-1-telemetry-v2.tar.gz -C "$RUN_ROOT"
cd "$RUN_ROOT/dcg-macos-arm64-milestone-1-telemetry-v2"
```

## Gatekeeper: manual evaluation exception

This round has no Developer ID signing or notarization. The reused ARM64 Mach-O
has its existing ad-hoc signature. After verifying the trusted download checksum,
remove quarantine from the extracted bundle (scripts can also carry quarantine):

```bash
xattr -dr com.apple.quarantine "$PWD"
```

For the Rust binary alone, the exact command is:

```bash
xattr -d com.apple.quarantine "$PWD/dcg/bin/dcgaimodel"
```

“No such xattr” means the attribute is already absent. No `sudo` or global
Gatekeeper change is needed. Removing quarantine does not repair a damaged signature.
Checksums verify bytes; this exception is not a notarization or publisher identity check.

## Run Milestone 1

Close other DCG instances so ports 8080 and 8081 are free. Stop Docker Desktop
and avoid builds during the 8GB measurement. The runner never starts Docker or IEMS.

```bash
/bin/bash scripts/verify-install.sh
python3 scripts/macos-acceptance.py --evidence "$RUN_ROOT/private-evidence"
```

The runner verifies native Darwin/arm64, ARM64 Java 21, hashes, CLI PASS (exit 0)
and FAIL (exit 1), deterministic-only start/status/stop, authentication and loopback
listeners, repeat-start PID stability, repeated stop, restart with AI AVAILABLE,
an actual advisory prediction, unchanged deterministic results, retained credentials/
contracts/check history, final embedded SQLite integrity, scoped shutdown, and unchanged
package inventory and hashes. It runs shell entrypoints with stock `/bin/bash`.

The runner first records a **30-second normal-pressure baseline**, then runs the
functional checks while observing memory approximately once per second. Allow about
one minute on a quiet Mac, or longer if startup needs it. Close heavy applications
before beginning. An already-pressured baseline produces REVIEW rather than blaming
DCG for existing host pressure.

`workspace/` contains private credentials, runtime logs and embedded state. Evidence
is written outside the package. `results.json` schema version 2 includes:

| Field | Meaning |
| --- | --- |
| `functional_status`, `checks`, `cleanup` | Functional assertions and cleanup results |
| `memory_qualification` | PASS / REVIEW / FAIL against the policy below |
| `memory_reasons` | Every REVIEW/FAIL reason with a stable code and explanation |
| `status` | FAIL if functional or memory failure; otherwise the memory result |
| `eight_gb_qualification` | Combined result on detected 8GiB RAM; NOT_TARGET on other hosts |
| `host.ram_bytes`, `host.is_8gb_target` | Detected RAM; flag is automatic, exactly 8,589,934,592 bytes |
| `memory_metrics.peak_combined_rss_bytes` | Maximum simultaneous workload RSS sum, bytes |
| `memory_metrics.longest_warning_seconds` | Longest continuously observed warning span |
| `memory_metrics.swap_used_delta_bytes` | After minus before swap used, bytes |
| `memory_metrics.peak_swap_used_increase_bytes` | Largest observed transient increase from the starting swap usage |
| `memory_metrics.swapout_delta_bytes` | New Swapouts pages multiplied by reported page size |
| `memory_metrics.max_sample_gap_seconds` | Largest interval between successful observations |
| `memory_before`, `memory_after` | Raw pressure/swap/VM outputs plus parsed counters |
| `telemetry.samples` | Full baseline and workload time series, including pressure and swap |
| `telemetry.samples[].processes` | PID/parent/start identity, command name (no full arguments), role and RSS in KiB |
| `telemetry.samples[].combined_rss_kib` | Sum of unique workload PIDs from one process-table snapshot |
| `telemetry.samples[].memory` | Raw memory_pressure/sysctl/vm_stat and parsed pressure level/counters |
| `telemetry.errors` | Failed observations; any error prevents a memory PASS |
| `memory_policy` | Version and exact thresholds used for this result |

The workload includes registered CLI/launcher processes, Java/Rust identified by
launcher PID/start records, and observed descendants, including those subsequently
reparented. Unrelated applications and the sampling process are excluded from RSS,
but their impact is visible in system-wide pressure. Simultaneous sums come from a
single `ps` process-table snapshot, not sums of independent per-process peaks.
Sub-second peaks and very short-lived children between observations can be missed.
Shared pages may be counted in more than one process; RSS is not physical footprint.
Sampling gaps above 2.5 seconds or missing CLI/Java/Rust coverage cause REVIEW.

## Memory qualification policy: macos-m1-memory-v1

These are conservative project budgets for this short smoke workload, not Apple
limits or a sustained-load certification. The classifier applies them automatically:

| Measurement | PASS budget | REVIEW | FAIL |
| --- | --- | --- | --- |
| Combined workload RSS | <=1,024 MiB | >1,024 and <1,536 MiB | >=1,536 MiB |
| Workload host pressure | Normal throughout | Warning for <5 continuously observed seconds | Any critical observation, or warning for >=5 continuously observed seconds |
| Swap-used increase, endpoint or transient peak | <=128 MiB | >128 and <256 MiB | >=256 MiB |
| New swap-out traffic | <=128 MiB | >128 and <256 MiB | >=256 MiB |

`memory_pressure -Q` free percentage is retained as evidence, never converted into
an invented warning threshold. Pressure is sampled via the read-only
`kern.memorystatus_vm_pressure_level` query (1 normal, 2 warning, 4 critical).
Swapout delta uses the VM-reported page size; historic swap/pageout counts do not
fail a run. Unknown pressure levels, parsing errors, reset counters, incomplete
baseline, missing observations or incomplete workload produce REVIEW. Observed
FAIL evidence takes precedence over REVIEW. A transient swap increase cannot be
hidden by swap falling again before shutdown.

A normal 24GiB dry run can report `memory_qualification: PASS` and `status: PASS`,
but will explicitly say `eight_gb_qualification: NOT_TARGET`. It is never evidence
of physical 8GB suitability. On the friend's Mac, check `host.is_8gb_target: true`,
`functional_status: PASS` and `eight_gb_qualification: PASS`. Detected RAM alone
does not distinguish a physical machine from a VM; record the machine being tested.

Exit codes: **0 PASS, 1 FAIL, 2 REVIEW**. Always retain the JSON, including on nonzero
exit. REVIEW means the run is not qualified; inspect the reasons, quiet the host if
needed, and repeat using a fresh extraction. OOM/runtime failures fail functional
acceptance; no automatic memory-pressure stress or global OS changes are performed.

To replay classification from existing evidence without launching services:

```bash
python3 scripts/memory_telemetry.py \
  --classify-report "$RUN_ROOT/private-evidence/results.json" \
  --output "$RUN_ROOT/replayed-classification.json"
```

Replay output is explicitly marked as offline, includes the input hash, and refuses
to overwrite an existing output. It does not change the original report or qualify
synthetic evidence as a live machine run.

Default JVM options are `-Xms64m -Xmx512m -XX:MaxMetaspaceSize=192m`, with a
180-second Java readiness timeout and 10-second AI readiness timeout. Heap caps do
not cap total RSS. You can set `JAVA_TOOL_OPTIONS` before running; the report records
it. Keep options free of secrets. Startup flags are marked for cleanup before
starting, so partial failures also attempt scoped shutdown. A failed cleanup is
reported; PID evidence is preserved when a process will not stop.

Do not share `workspace/` or private logs publicly. Return `results.json` for the
8GB decision after reviewing it. It contains host details and paths but no password.
The evaluator should keep the archive and its sidecar with the report.

## Manual service use after acceptance

```bash
DCG_AI_ENABLED=false /bin/bash scripts/start-dcg.sh
/bin/bash scripts/status-dcg.sh
/bin/bash scripts/stop-dcg.sh
DCG_AI_ENABLED=true /bin/bash scripts/start-dcg.sh
/bin/bash scripts/status-dcg.sh
/bin/bash scripts/stop-dcg.sh
```

The UI is at http://127.0.0.1:8080/ui; username `demo`, password in
`workspace/password`. Acceptance requires a fresh extraction, not a reused manual
workspace. On interruption inspect `private-evidence/results.json` and run the stop
script if cleanup failed.

## Provenance

`dcg/build-info.json` is the preserved historical input provenance, including its
development-build qualifications. It is not a claim that current source was rebuilt.
`bundle-info.json` records the reused artifact hashes, original manifest/provenance
hashes, and the new launcher hashes. Java and Rust are reused without compilation.
The Rust source pin is `32ca579095ed5b91749b8c33999556624e58758f`, target
`aarch64-apple-darwin`; its recorded build command was
`cargo +1.96.0 build --release --locked --target aarch64-apple-darwin`.
Only launcher files are replaced in the copied DCG input; their hashes and the new
manifests describe this evaluation bundle. Existing input packages remain untouched.
