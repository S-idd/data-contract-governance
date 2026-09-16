# DCG 4.0.0-alpha.1 — local demo

Not a hosted or production deployment. Install Java 21 separately (acceptance baseline:
Eclipse Temurin 21.0.12.1+1). Runtime utilities: Bash 3.2+, curl, lsof, ps and standard
macOS/Linux shell utilities. No Maven, Cargo, Python or Docker is needed by recipients.
Run only the archive matching your OS and CPU. Minimum OS/glibc support is not yet certified.

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

Use the absolute path to these scripts when working elsewhere. They support spaces in paths.
CLI arguments are passed unchanged, so relative schema arguments resolve from your current
directory. Sample schemas are in this archive's `contracts/orders.created/` directory.

Java listens only on 127.0.0.1:8080 and Rust only on 127.0.0.1:8081. Open
http://127.0.0.1:8080/ui . Login is `demo`; startup prints the location of a generated
password file, not the password. Rust inference is advisory, asynchronous and fail-open;
Java remains authoritative. Rust must be ready for initial paired startup. A later Rust
outage does not stop Java, but `bin/status` reports a degraded instance.

Writable state defaults to `$HOME/.local/share/dcg/4.0.0-alpha.1`. Override it with an
absolute `DCG_DATA_DIR` outside the package, using the same value for start/status/stop.
Contracts are copied there once; upgrades never overwrite them. SQLite, logs, credentials
and process identity records also live there. Stop retains all data. Logs append across runs.
Tracked processes are signalled only when their recorded start time and command still match.
If a process will not stop in 30 seconds, shutdown reports failure without a forced kill.
If a manually started inference process from this exact package owns port 8081, `bin/stop`
checks its executable and arguments and stops it, even when launched as
`./bin/dcgaimodel`. An unrelated listener is reported and left untouched;
`bin/status` identifies package-owned listeners without PID records separately from
unrelated untracked listeners. A missing PID record still makes status exit nonzero.

Ports are fixed for this alpha. Stop conflicting services or use a separate demo machine.
Do not expose these listeners via a tunnel/proxy or use this alpha on a shared/untrusted host.
The Rust loopback API has no authentication. Logs/password files are private local data.
Do not run launchers via symlinks; invoke them from the actual extracted package path.
Do not move a running package. Stop it first. If a launcher is interrupted and leaves
`run/lock`, verify no start/stop command is running before removing that empty directory.

The configuration template is copied once to `DCG_DATA_DIR/application-local-demo.properties`.
Edit that private copy while stopped. Package-controlled network,
authentication, storage and inference settings are enforced on the Java command line.
Keep shell Java option variables free of unrelated application overrides.

See RELEASE-NOTES.md for rollback boundaries and build-info.json for provenance.
