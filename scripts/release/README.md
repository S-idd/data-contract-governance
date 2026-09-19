# Local archive assembly (maintainer-only)

Requires Python 3.9+, Git and already-built inputs. No build/CI/publish operation is hidden
inside the assembler. Release identity pins are maintained in
`scripts/release/release-pins.json`, not embedded as release data in the assembler. The
assembler records the manifest SHA-256 in `build-info.json` and rejects staged evidence
from a different pin manifest. Packaging-only changes are recorded by file hashes and do
not silently replace the Java build pin.

Supply an input directory with exactly these required input names:

- `contract-cli-4.0.0-rc.1-all.jar`
- `contract-service-4.0.0-rc.1.jar`
- `dcgaimodel` (the requested target's native executable)
- `THIRD-PARTY-NOTICES.txt` (reviewed aggregate runtime dependency notices)
- `sbom.cdx.json` (CycloneDX JSON with Java and Rust runtime dependencies)
- `build-info.json` (actual build evidence, described below)

Only those selected files are consumed. Development/example files and unrelated inputs are
never recursively copied. Frozen models, Java sample contracts/policies and licenses are
read from exact Git commits, not either repository's working tree. The four launchers and
package documents come from `packaging/local/` in this checkout and are hashed in provenance.

`build-info.json` must have these fields from the real builds:

- `version`: `4.0.0-rc.1`
- `java_build_commit`: `994770c97ed00d00b1a6bf974344a6c68c31d656` (the exact committed Java RC source revision)
- `rust_commit`: `32ca579095ed5b91749b8c33999556624e58758f`
- `release_pins_sha256`: SHA-256 of `scripts/release/release-pins.json`
- `target`: exact Rust target triple corresponding to `--platform`
- `java_vendor`: `Eclipse Temurin`; `java_version`: `21.0.12.1+1`
- `jdk_archive_sha256`: one of the four verified upstream archive digests
- `rustc_verbose`: full rustc -Vv output; `cargo_version`: cargo -V output
- `artifacts`: object mapping `lib/contract-cli-4.0.0-rc.1-all.jar`,
  `lib/contract-service-4.0.0-rc.1.jar`, and `bin/dcgaimodel` to actual SHA-256 values

Record additional toolchain/OS build evidence as extra fields. Provenance is checked for
consistency, not cryptographically attested. Basic SBOM format/ecosystem checks do not prove
complete dependency/license coverage; review the actual SBOM and notices before publication.
Never supply fabricated notices, SBOMs, or build metadata to unblock assembly.

```sh
python3 scripts/release/assemble-local.py \
  --platform macos-arm64 \
  --java-repo /absolute/path/to/data-contract-governance \
  --rust-repo /absolute/path/to/dcgaimodel \
  --inputs /absolute/path/to/verified-build-inputs \
  --output /absolute/path/to/new-empty-output
python3 -m unittest discover -s scripts/release -p 'test_*.py' -v
```

The only other supported platform is linux-x64. An invocation produces one tar.gz and
an external SHA256SUMS in a fresh output directory. Keep platform outputs separate until
the later release-asset collection step; no existing output is overwritten. Archive paths,
file modes, timestamps, owner IDs and checksum ordering are normalized. The internal
SHA256SUMS covers every regular payload file except itself.

Launcher source files are now extensionless: `dcg`, `start`, `stop`, and `status`.
Assembly also accepts legacy `.sh` source names and maps them to extensionless package filenames. It rejects ambiguous
duplicates rather than silently choosing between `.sh` and extensionless sources.

## Step 5 evidence and staging helpers

`collect-dependency-evidence.py` consumes a real Maven aggregate BOM from
`org.cyclonedx:cyclonedx-maven-plugin:2.9.2:makeAggregateBom` with compile/runtime scope,
test/provided/system scopes disabled and `includeLicenseText=true`. It merges the normal/build
dependency closure from `cargo +1.96.0 metadata --locked --filter-platform TARGET`, marking
build-only/proc-macro packages excluded. It also inventories Spring Boot's injected loader
and jarmode resources and verifies their bytes against the actual service JAR. The Maven
plugin's configuration is documented at https://cyclonedx.github.io/cyclonedx-maven-plugin/makeAggregateBom-mojo.html .

Notices preserve upstream text from actual resolved JARs/crates and Rust standard-library
copyright files. Seven crates need supplemental license texts from their recorded source
commits. `license-sources.json` pins those exact URLs and hashes; download them into a private
working directory and pass it as `--license-supplements`. Missing text or a mismatched hash
blocks collection. Named/copyleft license conditions still require redistribution review.

`stage-local-inputs.py` verifies the exported Java/Rust source files against the locked Git
commits, checks real build logs and compiler/JDK evidence, and stages the required input files
with actual hashes. Its `--workspace` expects `java-source/`, `rust-source/`,
`cargo-PLATFORM.json`, `evidence-PLATFORM/`, `java-build.log`, and `rust-PLATFORM.log` for
macOS or `rust-PLATFORM-pinned.log` plus `PLATFORM/{dcgaimodel,rustc.txt,cargo.txt}` for Linux.
Run each helper with `--help` for arguments. JARs are built once with Temurin and reused
unchanged across platforms; Rust binaries and target-filtered Cargo inventories are separate.

Important: the Rust repository contains a floating `rust-toolchain.toml`. A pinned Docker
image alone is insufficient. Inside the build directory set `RUSTUP_TOOLCHAIN=1.96.0`, verify
`rustc +1.96.0 -Vv`, and invoke `cargo +1.96.0 build --release --locked --target TARGET`.
Do not use plain `cargo build` and assume the image's default toolchain wins.

Assembly is distinct from release acceptance: verify extracted files, CLI, paired startup,
auth, persistence, AI outage/recovery, rollback and shutdown on each advertised target.

For a deliberate live test on the matching host (ports 8080/8081 must be free):

```sh
python3 scripts/release/smoke-local.py --package /absolute/path/to/extracted-package \
  --java-home /absolute/path/to/temurin-jdk-home --data /absolute/path/to/new-test-state
```

This starts real services, checks login/readiness, simulates a Rust outage, checks restart
and persistence, and stops its processes in a finally block. It refuses existing test data
and retains new state/logs for inspection. Compilation on other targets does not substitute
for running this smoke test there.

## Step 6: standalone target-host acceptance

Copy `test-extracted-package.py`, the matching platform tar.gz and its external SHA256SUMS
to the target host. Run the script using Python 3.9+ outside any source checkout. It requires
the package runtime prerequisites (Java 21, Bash, curl, lsof and ps). `--work-dir` must be new.

```sh
python3 test-extracted-package.py \
  --archive /absolute/path/to/dcg-4.0.0-rc.1-macos-arm64.tar.gz \
  --java-home /absolute/path/to/verified-java-21-home \
  --work-dir /absolute/path/to/new-acceptance-directory \
  --machine-description 'Actual machine and environment description'
```

The runner verifies/extracts the archive, rejects target mismatches and Docker/Rosetta,
records actual OS/architecture, submits real checks before/during/after AI outage, checks
run-specific inference events, verifies Java survives and produces identical authoritative
results, restores Rust without restarting Java, tests repeated lifecycle operations, checks
port/PID cleanup, SQLite integrity and unchanged package files. It saves a structured
`acceptance-report.json` and private service logs without copying the password into the report.

WSL2 is recorded explicitly as WSL2, not native/bare-metal Linux. WSL1 is not accepted.
A WSL2 result validates that laptop environment only; do not generalize it to Linux ARM64,
Intel macOS, or arbitrary Linux distributions. Both selected RC archives require fresh
matching-host acceptance. No CI or emulation success replaces missing host results.

## Explicit development assemblies

Pass `--development-info /path/to/frozen-development.json` to use a separate identity.
The JSON requires `version` (for example `4.0.0-phase1-no-ai-dev.20260917`),
`base_version` equal to the pinned artifact version, `assembled_at` as a UTC ISO timestamp,
and `packaging_source` containing the full `commit`, boolean `dirty`, and SHA-256
`worktree_inventory_sha256`. Record source inventories before assembly and reuse this
same JSON and all inputs for both reproducibility runs. Extra evidence fields are preserved.

The development archive retains the verified runtime JAR names and embedded versions,
labels its README/release notes and provenance as unpublished development, and isolates its
default state directory. It never changes release pins or existing archives. Provenance
records source template hashes separately from transformed packaged launcher hashes.
Development assembly requires `dependency_evidence.notices_sha256` and `sbom_sha256` to
match the inputs before creating checksums; notices are copied as bytes, preserving upstream
line endings. Developer home/temp paths are rejected, including inside nested JAR entries.
A necessary path-remapped binary rebuild must record the original and replacement hashes,
exact source/toolchain, reason, command and build log hash; it does not inherit RC acceptance.

Use `DCG_TEST_INSTALLED_LAUNCHERS=true` with `DCG_TEST_PACKAGE`, `IEMS_TEST_ROOT` and Java 21
to run `test_no_ai_foundation.py` with launchers copied from the installed development
package. Controlled missing-artifact and breaking fixtures never edit the primary installation
or the project's approved contracts. Verify the primary installation's checksums and lifecycle
separately. The AI-enabled host acceptance runner above is outside this no-AI verification.
