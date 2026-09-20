# WSL2 current-source development package

This workflow builds the current Phase 1 and Phase 2 DCG source into a private Linux x64
development package and runs real-host acceptance on AlmaLinux x86-64 under WSL2. It does
not modify the accepted RC, release pins, source contracts, IEMS data, or normal DCG state.
It does not publish an artifact.

The workflow deliberately requires:

- a clean DCG checkout; its exact `HEAD` becomes the Java, contract, launcher, and packaging
  source commit;
- a clean `dcgaimodel` checkout at
  `32ca579095ed5b91749b8c33999556624e58758f`;
- Eclipse Temurin `21.0.12.1+1` plus its original checksum-pinned Linux x64 archive;
- the pinned Rust `1.96.0` toolchain;
- a new build directory under the WSL home filesystem, outside either checkout; and
- free loopback ports 8080 and 8081 when acceptance is enabled.

The build needs network access for Maven/Cargo dependencies and the public license texts
listed in `scripts/release/license-sources.json`. Every supplemental license is verified
against its repository-pinned SHA-256 before use.

## Prepare clean source checkouts

Use a fresh checkout when an existing repository contains generated or untracked files.

```bash
cd "$HOME"
git clone https://github.com/S-idd/data-contract-governance.git
git clone https://github.com/S-idd/dcgaimodel.git
git -C "$HOME/dcgaimodel" checkout --detach 32ca579095ed5b91749b8c33999556624e58758f
test -z "$(git -C "$HOME/data-contract-governance" status --porcelain --untracked-files=all)"
test -z "$(git -C "$HOME/dcgaimodel" status --porcelain --untracked-files=all)"
```

Record the selected development source before starting. The workflow repeats this check and
records the full SHA in package provenance.

```bash
export JAVA_REPO="$HOME/data-contract-governance"
export RUST_REPO="$HOME/dcgaimodel"
git -C "$JAVA_REPO" rev-parse HEAD
git -C "$RUST_REPO" rev-parse HEAD
```

## Run the complete build and acceptance workflow

Set `JDK_ARCHIVE` to the original Linux x64 Temurin archive whose digest is allowlisted in
`scripts/release/release-pins.json`. Point `JAVA_HOME` at the directory extracted from that
same archive.

```bash
export JAVA_HOME="$HOME/jdk21/jdk-21.0.12.1+1"
export JDK_ARCHIVE="$HOME/jdk21/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz"
export BUILD_DIR="$HOME/dcg-development-linux-$(date -u +%Y%m%dT%H%M%SZ)"

python3 "$JAVA_REPO/scripts/release/build-development-linux-wsl2.py" \
  --java-repo "$JAVA_REPO" \
  --rust-repo "$RUST_REPO" \
  --java-home "$JAVA_HOME" \
  --jdk-archive "$JDK_ARCHIVE" \
  --work-dir "$BUILD_DIR" \
  --machine-description "AlmaLinux x86-64 under WSL2"
```

This single command:

1. rejects WSL1, containers, wrong architectures, dirty source, wrong commits/toolchains,
   reused output directories, Windows-mounted build directories, and occupied demo ports;
2. exports both exact commits rather than building from mutable working-tree files;
3. builds the Java CLI/service and Linux x64 Rust advisory binary;
4. generates and verifies dependency evidence;
5. stages provenance and assembles the same package twice;
6. requires identical archive, compatibility-manifest, and checksum bytes;
7. creates a self-contained acceptance handoff; and
8. runs the 13-check target-host acceptance suite.

The final JSON printed by the workflow must report `status`, `reproducibility`, and
`acceptance` as `PASS`. Inspect the same durable result afterward:

```bash
jq . "$BUILD_DIR/workflow-summary.json"
jq '{status, archive, archive_sha256, java_build_commit, rust_commit, status_protocol,
     checks: [.checks[] | {check, status}]}' \
  "$BUILD_DIR/acceptance/acceptance-report.json"
```

The transferable package and matching runner files are under
`$BUILD_DIR/acceptance-handoff/`. The build, evidence, service logs, generated password, and
acceptance state remain private under `$BUILD_DIR`.

## Build without starting services

Use this only when ports 8080/8081 cannot be reserved yet. It proves the build and byte-for-byte
reproduction, but it does not establish runtime acceptance.

```bash
python3 "$JAVA_REPO/scripts/release/build-development-linux-wsl2.py" \
  --java-repo "$JAVA_REPO" \
  --rust-repo "$RUST_REPO" \
  --java-home "$JAVA_HOME" \
  --jdk-archive "$JDK_ARCHIVE" \
  --work-dir "$BUILD_DIR" \
  --skip-acceptance
```

The summary will report `acceptance` as `SKIPPED`. Do not describe that result as accepted.
Run `test-extracted-package.py` from the generated handoff later with a new private acceptance
directory.

## Recovery and safe cleanup

The acceptance runner stops only task-owned processes in a `finally` block. If the terminal is
interrupted, use the packaged scoped stop command with the generated state directory:

```bash
DCG_DATA_DIR="$BUILD_DIR/acceptance/persistent state" \
  "$BUILD_DIR/acceptance/relocated and renamed demo/bin/stop"
ss -ltnp | grep -E ':(8080|8081)\b' || echo "Ports 8080 and 8081 are free"
```

Keep `workflow-summary.json`, `acceptance/acceptance-report.json`, and the handoff until the
result has been reviewed. The build directory is disposable and can be removed later. Never
remove an accepted RC archive, normal IEMS data, or another service's process as part of this
workflow.
