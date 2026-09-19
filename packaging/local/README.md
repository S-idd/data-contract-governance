# DCG 4.0.0-rc.1 — local demo

Not a hosted or production deployment. Install Java 21 separately (acceptance baseline:
Eclipse Temurin 21.0.12.1+1). Runtime utilities: Bash 3.2+, curl, lsof, ps and standard
macOS/Linux shell utilities. No Maven, Cargo, Python or Docker is needed by recipients.
Only native macOS ARM64 and Linux x64 on WSL2 are in this prerelease scope. Run only the
archive matching your OS and CPU. Minimum OS/glibc support is not yet certified.

## Evidence supplied with this package

- `build-info.json`: actual Java/Rust revisions, compiler/JDK evidence, build-log digests,
  binary hashes, frozen input hashes and packaging-input hashes; not a signed attestation.
- `sbom.cdx.json`: resolved Java/Rust CycloneDX 1.6 component inventory.
- `THIRD-PARTY-NOTICES.txt`: aggregated upstream license and notice texts.
- `RELEASE-NOTES.md`: locked versions, known limitations, acceptance gates and rollback.
- `SHA256SUMS`: every regular payload file except the checksum file itself, using
  lowercase SHA-256, two spaces, relative filename and LF line endings.

Checksums detect changed bytes but do not authenticate an untrusted download. Obtain the
archive and expected digest through a trusted channel. This candidate is unpublished;
host acceptance and redistribution/security review are not complete for all platforms.

## Verify and start

Verify the external archive SHA256SUMS before extraction. Inside the extracted directory,
run `shasum -a 256 -c SHA256SUMS` (or `sha256sum -c SHA256SUMS` on Linux).

```sh
./bin/dcg --help
./bin/start
./bin/status
./bin/stop
```

For deterministic operation without loading or starting the Rust model:

```sh
DCG_AI_ENABLED=false ./bin/start
./bin/status
./bin/stop
```

The default remains `DCG_AI_ENABLED=true`; only `true` and `false` are accepted.
No-AI mode requires no Rust binary or model directory and leaves port 8081 alone.
Java receives an explicit `--shadow.inference.enabled=false`, overriding inherited
inference configuration. Status and stop use the saved instance mode, so the flag
need not be repeated. Stop before changing modes. Legacy state without a saved
mode is treated as AI-enabled. The application's compatibility gate remains a
separate CLI/build integration; disabling AI does not alter its PASS/FAIL rules.

Use the absolute path to these scripts when working elsewhere. They support spaces in paths.
CLI arguments are passed unchanged, so relative schema arguments resolve from your current
directory. Sample schemas are in this archive's `contracts/orders.created/` directory.

Java listens only on 127.0.0.1:8080 and Rust only on 127.0.0.1:8081. Open
http://127.0.0.1:8080/ui . Login is `demo`; startup prints the location of a generated
password file, not the password. Rust inference is advisory, asynchronous and fail-open;
Java remains authoritative. In AI-enabled mode Java starts first, then Rust starts
best-effort. A missing binary/model, occupied Rust port, failed process or readiness
timeout prints `AI advisory: unavailable` while Java continues. `bin/status` reports
the Java and advisory states independently. `DCG_AI_STARTUP_TIMEOUT_SECONDS` may be
set to 1–10 (default 3); the Java inference request timeout remains configured by
`shadow.inference.timeout` (default 500 ms). The IEMS application performs its own
deterministic CLI checks before dispatching Java; this service launcher does not
replace that IEMS gate.

Writable state defaults to `$HOME/.local/share/dcg/4.0.0-rc.1`. Override it with an
absolute `DCG_DATA_DIR` outside the package, using the same value for start/status/stop.
Contracts are copied there once; upgrades never overwrite them. SQLite, logs, credentials
and process identity records also live there. Stop retains all data. Logs append across runs.
Tracked processes are signalled only when their recorded start time and command still match.
If a process will not stop in 30 seconds, shutdown reports failure without a forced kill.
If a manually started inference process from this exact package owns port 8081, `bin/stop`
checks its executable and arguments and stops it, even when launched as
`./bin/dcgaimodel`. An unrelated listener is reported and left untouched;
In AI-enabled mode, `bin/status` exits successfully while deterministic Java is healthy,
even when the optional advisory process is unavailable. A listener without a tracked PID
is reported as `NOT OWNED`. `bin/stop` signals it only when the executable and arguments
match this exact package; unrelated listeners remain untouched.

Ports are fixed for this prerelease. Stop conflicting services or use a separate demo machine.
Do not expose these listeners via a tunnel/proxy or use this prerelease on a shared/untrusted host.
The Rust loopback API has no authentication. Logs/password files are private local data.
Do not run launchers via symlinks; invoke them from the actual extracted package path.
Do not move a running package. Stop it first. If a launcher is interrupted and leaves
`run/lock`, verify no start/stop command is running before removing that empty directory.

The configuration template is copied once to `DCG_DATA_DIR/application-local-demo.properties`.
Edit that private copy while stopped. Package-controlled network,
authentication, storage and inference settings are enforced on the Java command line.
Keep shell Java option variables free of unrelated application overrides.

See RELEASE-NOTES.md for rollback boundaries and build-info.json for provenance.
