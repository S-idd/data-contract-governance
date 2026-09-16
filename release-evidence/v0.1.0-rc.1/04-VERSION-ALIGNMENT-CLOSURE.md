# Version-alignment closure — Java 4.0.0-rc.1

This closes the version-alignment documentation gap identified in [03-VERSION-ALIGNMENT-RESULTS.md](03-VERSION-ALIGNMENT-RESULTS.md). The earlier report records the state before this README correction; its `BLOCKED` line is historical, not the current alignment status. No release archive, notice, tag, publication, upload or dependency change was made here.

| Decision / provenance | Exact value |
|---|---|
| Java version-alignment source commit | `994770c97ed00d00b1a6bf974344a6c68c31d656` |
| Java release-pin commit | `472f12c124c94cf045d8024c9b9a55fc2137e562` |
| Java source pin in `scripts/release/release-pins.json` | `994770c97ed00d00b1a6bf974344a6c68c31d656` |
| Rust source pin in the same file | `32ca579095ed5b91749b8c33999556624e58758f` |
| Java release version | `4.0.0-rc.1`; tag `v4.0.0-rc.1` is planned, not created |
| Rust Cargo version | `0.1.0`; embedded shadow-only component, no independent Rust release |
| Platforms | native macOS ARM64 and Linux x64 on WSL2 only |
| Compose archive | Excluded |
| Future archive names | `dcg-4.0.0-rc.1-macos-arm64.tar.gz`; `dcg-4.0.0-rc.1-linux-x64.tar.gz` |

The only content edit in this closure is `scripts/release/README.md` line 27: its stale statement that the Java source pin was pending now names the exact committed Java RC source revision. No historical alpha documentation was rewritten. Java remains authoritative; Rust remains asynchronous, logging-only, shadow-only and fail-open.

## Read-only verification

- `git rev-parse --show-toplevel` identified the Java repository, and `git status --porcelain=v1` was empty before this documentation edit.
- A JSON/Git pin check passed: `java_build_commit` equals `994770c97ed00d00b1a6bf974344a6c68c31d656`, that commit's root POM declares `4.0.0-rc.1`, `rust_commit` equals `32ca579095ed5b91749b8c33999556624e58758f` and resolves as a commit, and the target map is exactly `macos-arm64` plus `linux-x64`.
- `./mvnw -B -ntp -o validate`: **PASS**, all 11 Maven reactor modules at `4.0.0-rc.1`.
- `python3 -m unittest discover -s scripts/release -p 'test_local_packaging.py' -v`: **PASS**, 16/16 tests. Synthetic temporary fixtures are not release archives.
- `cargo +1.96.0 metadata --locked --offline --no-deps --format-version 1`: **PASS**, Rust package version remains `0.1.0`.
- `cargo +1.96.0 fmt --all -- --check`: **unavailable** because `rustfmt`/`cargo-fmt` is absent from the pinned toolchain; install that component and rerun before final release validation, without changing the Rust toolchain version.

Version alignment is complete, but release readiness is not. Required later work includes real RC builds, fresh provenance/SBOM/notices and target-host acceptance, final security review, rustfmt, and human redistribution approval. MySQL Connector/J and related obligations remain under legal review. The existing alpha evidence cannot be relabeled as RC evidence. No Compose asset is included.

Version alignment: COMPLETE
Final archives: NOT BUILT
THIRD-PARTY-NOTICES.txt: NOT CREATED
Rust rustfmt: FOLLOW-UP REQUIRED
MySQL legal review: PENDING
Human legal approval: PENDING
Tagging and publication: NOT AUTHORIZED
