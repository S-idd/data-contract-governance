# DCG Phase 1 + Phase 2 source-consolidation audit

Audit date: 2026-09-19. **Result: READY_FOR_CHECKPOINT for a local, unpublished development source checkpoint.** This is not release, legal, security, or new-platform acceptance. No branch, commit, archive, tag, upload, or publication was made in this audit.

## Identity and scope

DCG root: this repository; branch `main`; HEAD `7138c9047db23d118ae32e6815a9c67f97c7b713`; upstream `origin/main`, ahead/behind `0/0`. The worktree was dirty before this report: 18 modified tracked files, seven individual untracked source/input files, and the untracked `release-output/` tree. No staged, added-tracked, deleted, renamed, changed executable-mode, or changed symlink entries; no submodule or nested Git checkout was found. `git diff --stat` for the pre-audit tracked changes: 18 files, 570 insertions, 69 deletions. The present report is the only audit-created file.

IEMS is a separate dirty repository on `main` at `6e65c66745714b7f84521b50efd31fdbe0d99e98` (35 modified tracked files and 43 top-level untracked entries at inspection). Its files are excluded from every DCG checkpoint. Its private Phase 1 integrated `results.json` reports PASS for 10 scenarios; Phase 2 final integrated `results.json` reports PASS for 13 scenarios. These are cross-references, not newly run rehearsals.

## Complete Git-visible change inventory

Each row names an exact checkpoint candidate unless marked **exclude**. “Both” means the changed source is portable to macOS and Linux; runtime binaries still require target-specific builds. The listed tests are relevant coverage, not proof of every platform or edge case. No changed source contains a developer home path or credential value. Loopback ports 8080/8081 and the `4.0.0-rc.1` runtime artifact pin are intentional package assumptions; the new test files use temporary paths and controlled demo ports. The absolute `/Users/` strings in `assemble-local.py` and `test_local_packaging.py` are rejection fixtures, not embedded developer-specific output paths.

| File (relative to DCG root) | Classification / phase | Change, test, scope, checkpoint |
|---|---|---|
| `packaging/local/bin/dcg` | Packaging / Phase 1+2 | Validates requested AI mode, saved mode and PID ownership; bounded advisory startup timeout. Launcher tests; both; include. |
| `packaging/local/bin/start` | Packaging / Phase 1+2 | Starts deterministic Java first; no-AI skips Rust; AI startup is best-effort; stores mode/state and keeps 8081 scoped. Launcher tests; both; include. |
| `packaging/local/bin/status` | Packaging / Phase 1+2 | Separately reports deterministic Java and optional advisory status. Launcher tests; both; include. |
| `packaging/local/bin/stop` | Packaging / Phase 1+2 | Stops identified task-owned Java/Rust; avoids signaling unrelated 8081 listeners. Launcher tests; both; include. |
| `scripts/release/assemble-local.py` | Packaging / Phase 1+2 | Adds explicit unpublished development identity, frozen source inventory, notice/SBOM digest checks, path scans (including nested JARs), checksum provenance and isolated state path. Packaging tests; both; include. |
| `scripts/release/test_local_packaging.py` | Packaging test / Phase 1+2 | Tests development identity, nested-path rejection, notice tampering and byte-preserving checksums. 20-test suite; both; include. |
| `scripts/release/test_no_ai_foundation.py` | Phase 1 test | Disposable package/IEMS copies verify no-AI lifecycle and deterministic checks; hard-coded demo ports are test-only. Six-test suite; macOS tested, logic portable; include. |
| `scripts/release/test_ai_launcher_resilience.py` | Phase 2 test | Disposable installed-package copy verifies available, unavailable, timeout, no-AI and unrelated-listener behavior; test-only edits occur in the copy. Six-test suite; macOS tested, logic portable; include. |
| `packaging/local/README.md` | Documentation / Phase 1+2 | Documents no-AI, best-effort AI, runtime timeout and scoped state. Both; include. |
| `scripts/release/README.md` | Documentation / Phase 1+2 | Documents development assembly and installed-launcher tests. Both; include. |
| `THIRD-PARTY-NOTICES.txt` | Packaging / reviewed macOS input | Exact SHA-256 `deafccec618c145c42abb3afe5225222d97732ec291af065af8c54e5e4241e0e`, matching the accepted macOS notice. Include as a **macOS input only**; Linux's reviewed notice is different. Legal completeness remains blocked for publication. |
| `contract-core/src/main/resources/db/migration/V13__create_check_run_advisories.sql` | Database migration / Phase 2 | Adds advisory table and completed-run reference for SQLite/PostgreSQL migration path. Persistence test exercises SQLite V1–V13 and V12→V13; include. |
| `contract-core/src/main/resources/db/migration-mysql/V13__create_check_run_advisories.sql` | Database migration / Phase 2 | MySQL-specific types and foreign key; inspected, not independently exercised against MySQL in this audit; include, native DB verification remains next-step. |
| `contract-service/src/main/java/com/ideas/contracts/service/model/CheckRunAdvisoryResponse.java` | Dashboard/API / Phase 2 | Optional advisory response including status, model/feature hashes, three seeds, probabilities, agreement and test-only flag. Persistence test; both; include. |
| `contract-service/src/main/java/com/ideas/contracts/service/CheckController.java` | Dashboard/API / Phase 2 | `GET /checks/{runId}/advisory`: 200 advisory, 204 absent/pending, 404 missing run. Prior IEMS Phase 2 service rehearsal; both; include. |
| `contract-service/src/main/java/com/ideas/contracts/service/CheckRunRepository.java` | Phase 2 source | Adds optional advisory read/write contract; persistence test; both; include. |
| `contract-service/src/main/java/com/ideas/contracts/service/CheckRunStore.java` | Phase 2 source | Persists/retrieves advisory only for a completed PASS/FAIL run; advances expected SQLite/PostgreSQL and MySQL Flyway tip to V13. Persistence test; both; include. |
| `contract-service/src/main/java/com/ideas/contracts/service/CheckRunner.java` | Phase 2 source | Dispatches optional observation after authoritative completion/logging; persistence test and prior rehearsal; both; include. |
| `contract-service/src/main/java/com/ideas/contracts/service/ShadowInferenceObserver.java` | Phase 2 source | Records advisory or unavailable/timeout/invalid outcome without changing enforcement; labels frozen model/feature identity and agreement. Persistence test and prior rehearsal; both; include. Frozen-model constants must be revisited if model artifacts change. |
| `contract-service/src/main/java/com/ideas/contracts/service/ShadowInferenceProperties.java` | Phase 2 source | Rejects test-only adapter outside `phase2-rehearsal` profile; prior rehearsal; both; include. |
| `contract-service/src/main/resources/application.properties` | Phase 2 source | Defaults test-only adapter to false; prior rehearsal; both; include. Configurable endpoint/request timeout remain in properties. |
| `contract-service/src/main/java/com/ideas/contracts/service/UiController.java` | Dashboard/API / Phase 2 | Supplies advisory to check detail page; prior rehearsal; both; include. |
| `contract-service/src/main/resources/static/ui-checks.js` | Dashboard/API / Phase 2 | Polls advisory endpoint and renders text safely with DOM text nodes; prior rehearsal; both; include. |
| `contract-service/src/main/resources/templates/ui/check-detail.html` | Dashboard/API / Phase 2 | Renders advisory separately from final deterministic result, marking test-only output; prior rehearsal; both; include. |
| `contract-service/src/test/java/com/ideas/contracts/service/CheckRunAdvisoryPersistenceTest.java` | Phase 2 test | Three SQLite cases: restart persistence/disagreement, timeout without fabricated prediction, V12→V13 migration; include. |
| `release-output/` (10,522 files, 2.3 GB) | Generated build output / private evidence | **Exclude entire tree** from Git and every new archive input. It contains accepted RC archives, prior archives, extracted packages, source exports, Cargo cache, build tools, SBOMs and scan evidence. Preserve its accepted assets byte-for-byte. |

Classification count before this report: **25 individual source/input files**: Packaging 6, Packaging test 1, Phase 1 test 1, Phase 2 source 6, Phase 2 test 2, Database migration 2, Dashboard/API 5, Documentation 2; plus one excluded generated/evidence tree. Phase 1 launcher functionality is classified as Packaging, not double-counted as Phase 1 source. No ambiguous source-owner item was found. The report itself is Documentation and may join the documentation checkpoint after review.

Ignored generated files: 4,689 paths at inspection, mostly `target/` (4,453) and `.m2/` (86); the rest include local environment templates/overrides, generated contracts, IDE/OS files, SQLite DB/WAL/SHM, logs, demo runtime and files inside the untracked release tree. `target/`, `.m2/`, `*.db`, `*.sqlite*`, `*.log`, `.env*`, `.DS_Store`, IDE files and generated smoke contracts are covered by `.gitignore`. `release-output/` is **not** ignored: an ordinary blanket `git add .` could stage private/accepted artifacts. Add a verified exclusion before any broad staging, or stage only the exact files below. The assembler uses an explicit 24-file payload from named inputs, so its current archive path does not traverse `release-output/`, IEMS evidence, runtime DBs, IDE files or arbitrary source files. Reconfirm for any future assembler change. No newly added source file is binary, executable or a symlink; existing launcher executable modes are unchanged.

## Accepted RC preservation

The later reviewed archives, not the older root-level archives or alpha artifacts, are the accepted **runtime-tested** RCs. [`07-PLATFORM-ACCEPTANCE.md`](../release-evidence/v0.1.0-rc.1/07-PLATFORM-ACCEPTANCE.md) records 13/13 native Mac and 13/13 WSL2 Linux checks on the exact hashes. [`08-NOTICE-SECURITY-RESULTS.md`](../release-evidence/v0.1.0-rc.1/08-NOTICE-SECURITY-RESULTS.md) and [`09-SECURITY-DISPOSITION.md`](../release-evidence/v0.1.0-rc.1/09-SECURITY-DISPOSITION.md) preserve their identity. “Accepted” is runtime acceptance only: legal notice, MySQL legal review and security disposition still block publication.

| Platform and accepted archive | Archive SHA-256 | External `SHA256SUMS` SHA-256 | Internal `SHA256SUMS` SHA-256 | Recheck |
|---|---|---|---|---|
| `release-output/v0.1.0-rc.1/reviewed-macos-arm64/dcg-4.0.0-rc.1-macos-arm64.tar.gz` | `7921446b1efcc229c2fb02218f8a7cce4412d5b300f2215576c1f253df58be0e` | `c9e04496ceaca1776b338395c4d18c873ae19fa4b4d495b91f9918926e661b21` | `5676c89d404693267bbda9a99106bc073e5fad3891e182c42fe7bd7e1cc468ae` | 38 tar entries, 24 regular files, 23/23 internal checks PASS |
| `release-output/v0.1.0-rc.1/reviewed-linux-x64/dcg-4.0.0-rc.1-linux-x64.tar.gz` | `f3f39529704bf5eef3030c9d0e02ab03234778e60843487e8f7967c9ac07b588` | `85191bdc5cd7c18216fc2e970790034c24ffbc13177412ed30068700f72c6a78` | `0be42a76fc7afc1ff4e7c61cb96070aea01d10e52ab89446efb1b196c56ca33f` | 38 tar entries, 24 regular files, 23/23 internal checks PASS |

The on-disk bytes match the acceptance hashes and both external manifests; all packaged files match internal manifests. No RC file was written during this audit. The original root-level RC-named archives have different hashes (`48a833…` macOS, `1a5e80…` Linux) and must not be substituted. Version identity remains `4.0.0-rc.1`; macOS target is `aarch64-apple-darwin`, Linux target `x86_64-unknown-linux-gnu` tested under WSL2.

## Development package provenance and comparison

These packages are **IEMS-local historical evidence** under its `.dcg/runtime/` directory; all three have `build-info.json`, 23/23 correct internal checksums, source HEAD `7138c9047db23d118ae32e6815a9c67f97c7b713`, `dirty=true`, and base Maven artifact version `4.0.0-rc.1`. All are unpublished, local macOS ARM64 development packages. `build-info.json` supplies an explicit UTC assembly time and dirty-worktree inventory hash, but that hash alone is not a clean Git source revision.

| Package directory suffix | Source inventory SHA-256 | Service JAR SHA-256 | CLI JAR SHA-256 | Rust SHA-256 | Packaged launcher hashes (`dcg` / `start` / `status` / `stop`) |
|---|---|---|---|---|---|
| `dcg-4.0.0-phase1-no-ai-dev.20260917-macos-arm64` | `ec96d92b3ccd4b6f6f74cce5aedf0196fd62d005bc1a4ebc3f4e5a9b33e66f8f` | `5a6bec25ef2d9022176e423ab4643fd88e92199804001ffbda99ca9b389ef712` | `271c34c32ded6a8c08cdd8b9d4f083c2d37457c47f074f5703618be4ddb2ecb1` | `5932bb21d15fbb6b19e3a534437a3f3f082c23f91dbe7f1422e4c67fff2327f8` | `b543db64…` / `abecb0df…` / `b2371a16…` / `79e49827…` |
| `dcg-4.0.0-phase2-ai-launcher-dev.20260918-r4-macos-arm64` | `4c4fa48c3e6f6fa69fdb3927c1a7513c458f5856a38649b00b0104a40fc0e0b2` | `5a6bec25ef2d9022176e423ab4643fd88e92199804001ffbda99ca9b389ef712` | same | same | `e904e188…` / `ce1e975e…` / `1a9b749d…` / `79e49827…` |
| `dcg-4.0.0-phase2-service-advisory-dev.20260918-r3-macos-arm64` | `9b2b627ca525259f1b5c7541ccad6dd3b3bb4da9233969049e4d76682e15978c` | `bf1f9d4b53532a143192436237c8a596ddfd8dbee4e2a6ee85fec570a983073d` | same | same | `bdc6a8c5…` / `ce1e975e…` / `1a9b749d…` / `79e49827…` |

Full packaged launcher SHA-256 values, in `dcg`, `start`, `status`, `stop` order:

| Package | `bin/dcg` | `bin/start` | `bin/status` | `bin/stop` |
|---|---|---|---|---|
| Phase 1 | `b543db64631c3f172e4f6b707932f76e192b33fbd9f88d27865550f0e20d9515` | `abecb0dfa71eaac1efa2aa4ae18b48dbdae75f20bf729ecd10682e3f6fbb3edb` | `b2371a16fe94e4c1122cfebe12f9822aef0e8c48c02b6e9b260439ea9b01e9a5` | `79e49827eecacdf0e220c108223a17e6a89d4359f899325f9adbd9d47a81e08c` |
| Phase 2 launcher r4 | `e904e188fd2dc36f17965fdf812106354fe152bb793bf4a6e959e99457076c15` | `ce1e975ee85f81836b4a1755a62e254eb6422d7d554802ef7db85af483181960` | `1a9b749d04e270c1ee4e954cea95f0259b090868842ddf1a13c1e04398115e1d` | `79e49827eecacdf0e220c108223a17e6a89d4359f899325f9adbd9d47a81e08c` |
| Phase 2 service r3 | `bdc6a8c5bdc390b03c5144152a6c5fad169e22bcb82dff5ea0bb5d7683bc83d1` | `ce1e975ee85f81836b4a1755a62e254eb6422d7d554802ef7db85af483181960` | `1a9b749d04e270c1ee4e954cea95f0259b090868842ddf1a13c1e04398115e1d` | `79e49827eecacdf0e220c108223a17e6a89d4359f899325f9adbd9d47a81e08c` |

These metadata values were independently compared to the installed bytes. The final r3 package's **source template** hashes for all four launchers match the current DCG tree; `bin/dcg` packaged hash differs because assembly isolates the development state directory. The Phase 1 package predates the best-effort AI launcher; the r4 package has that launcher but retains the older service JAR. The final r3 package combines the current no-AI/best-effort launchers with advisory persistence/UI service JAR, and both six-test installed launcher suites passed on it. It is therefore the final demonstrated combination and supersedes the earlier packages **for future development**, while all three remain preserved historical evidence. The r3 Java JAR was built from dirty source and is not claimed reproducible from a clean checkpoint yet. The final package retains the 4.0.0-rc.1 CLI and the same frozen Rust binary; it cannot be described as a newly versioned Maven release. All three packages keep historical source/provenance and must not be overwritten.

## Packaging and versioning findings

Canonical local assembler: `scripts/release/assemble-local.py`; inputs and pinned target/source revisions: `scripts/release/release-pins.json`; build/native acceptance instructions: `scripts/release/README.md` and `scripts/release/test-extracted-package.py`. Assembly writes one archive and external checksum into a required empty output directory, with explicit payload, internal `SHA256SUMS`, CycloneDX SBOM, provenance and platform-specific notices. Tar uid/gid/mtime and gzip mtime are normalized; a frozen `assembled_at` and identical inputs are required to reproduce bytes. The script rejects this host's home/temp paths even within nested JARs, but the audit did not perform a new two-run byte-reproducibility build because archive creation was prohibited. Developer absolute paths remain in historical evidence/provenance outside new archive inputs; do not feed those files to an archive.

One consolidated **source** checkpoint can support both targets, but it cannot itself prove both archives. Java source and dashboard assets are portable and the CLI/service JARs are Java-21 artifacts; Rust `dcgaimodel` must be rebuilt/verified for each native target. Inputs include frozen model JSON, policy packs, platform SBOM and **platform-specific** notice bytes. Root `THIRD-PARTY-NOTICES.txt` is the accepted macOS notice; Linux requires the reviewed Linux notice with SHA-256 `56da5233b7df1116f7e19c93aebd6784e93903d2bc07c506dbeeacdd40423204`, not a copy of the macOS file. Both notices remain legally incomplete. The assembler checks notice/SBOM digests and writes checksums, but human legal approval is not implied.

`pom.xml`, module POMs, `packaging/local/bin/dcg`, the pinned Rust source/Cargo metadata, and `release-pins.json` still identify `4.0.0-rc.1`. A development package version may be separate without changing Maven coordinates; the JAR filenames and embedded Maven version remain RC-pinned and must be disclosed. A genuine change to package/artifact version would require coordinated Maven coordinates, launcher names/defaults, pins, Rust metadata/provenance and fresh native builds; this audit changes none. `packaging/local/RELEASE-NOTES.md` has stale pre-archive language (“No RC archive ... exists yet”); correct it in a later, separately reviewed documentation step before new external-facing archives, without rewriting accepted RC packages. No current code path guarantees new Linux acceptance or MySQL V13 runtime validation.

## Validation run during audit

| Command / check | Result |
|---|---|
| `git diff --check` | exit 0, no whitespace errors |
| `python3 -m unittest scripts.release.test_local_packaging -q` | exit 0, 20 tests |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -pl contract-service -am -Dtest=CheckRunAdvisoryPersistenceTest -Dsurefire.failIfNoSpecifiedTests=false test -q` | exit 0, 3 tests, 0 failures/errors/skips; SQLite Flyway V1–V13 and V12→V13 observed |
| `DCG_TEST_PACKAGE="$IEMS_ROOT/.dcg/runtime/dcg-4.0.0-phase2-service-advisory-dev.20260918-r3-macos-arm64" IEMS_TEST_ROOT="$IEMS_ROOT" DCG_TEST_INSTALLED_LAUNCHERS=true DCG_TEST_EVIDENCE="$PRIVATE_EVIDENCE/noai" JAVA_HOME=$(/usr/libexec/java_home -v 21) python3 -m unittest scripts.release.test_no_ai_foundation -q` | exit 0, 6 tests; host-specific path prefixes replaced by variables here |
| `DCG_TEST_PACKAGE="$IEMS_ROOT/.dcg/runtime/dcg-4.0.0-phase2-service-advisory-dev.20260918-r3-macos-arm64" IEMS_TEST_ROOT="$IEMS_ROOT" DCG_TEST_EVIDENCE="$PRIVATE_EVIDENCE/ai" JAVA_HOME=$(/usr/libexec/java_home -v 21) python3 -m unittest scripts.release.test_ai_launcher_resilience -q` | exit 0, 6 tests; host-specific path prefixes replaced by variables here |
| Three development package `SHA256SUMS` manifests | 23/23 PASS each |
| Two accepted RC external + internal manifests | both archive SHA-256 matches; 23/23 internal PASS each |
| `lsof` listener preflight for 8080/8081 | neither port occupied before launcher tests |

Focused Maven tests created only ordinary ignored `target/` output and JUnit temporary SQLite databases. Installed launcher tests use temporary package copies, task-owned processes and private evidence; their cleanup results must be checked before checkpoint. No full integrated rehearsal or new archive was run. MySQL V13 and PostgreSQL V13 were inspected as SQL, not newly exercised here; no Linux test was run on this Mac.

## Proposed checkpoint and archive plan — commands are **not executed**

Proposed branch: `codex/phase1-phase2-source-consolidation`. Proposed unpublished identity: `4.0.0-phase1-phase2-dev.20260919`. Proposed filenames: `dcg-4.0.0-phase1-phase2-dev.20260919-macos-arm64.tar.gz` and `dcg-4.0.0-phase1-phase2-dev.20260919-linux-x64.tar.gz`. These are development artifacts, not `4.0.0-rc.1` or public releases. Freeze a clean source commit and native build inputs before assembly; record both archive and internal hashes, Java/Rust toolchains and commits, complete package/source inventory, SBOM/notice digest and review status, platform acceptance output, and evidence directory in new provenance. Keep every existing RC/development package unchanged.

Logical commits (exact paths; source files can be staged by explicit path, never with `git add .`):

1. **Launcher and no-AI deterministic foundation:** `packaging/local/bin/dcg`, `packaging/local/bin/start`, `packaging/local/bin/status`, `packaging/local/bin/stop`, `scripts/release/test_no_ai_foundation.py`, `packaging/local/README.md`. This logical group includes later best-effort startup in the same final file; review the combined diff before staging rather than pretending the earlier Phase 1 version is still present.
2. **Development assembly and launcher verification:** `scripts/release/assemble-local.py`, `scripts/release/test_local_packaging.py`, `scripts/release/test_ai_launcher_resilience.py`, `scripts/release/README.md`, `THIRD-PARTY-NOTICES.txt`. The notice is specifically the reviewed macOS input; verify Linux notice separately and retain its review status.
3. **Advisory persistence and model observation:** `contract-core/src/main/resources/db/migration/V13__create_check_run_advisories.sql`, `contract-core/src/main/resources/db/migration-mysql/V13__create_check_run_advisories.sql`, `contract-service/src/main/java/com/ideas/contracts/service/CheckRunRepository.java`, `contract-service/src/main/java/com/ideas/contracts/service/CheckRunStore.java`, `contract-service/src/main/java/com/ideas/contracts/service/CheckRunner.java`, `contract-service/src/main/java/com/ideas/contracts/service/ShadowInferenceObserver.java`, `contract-service/src/main/java/com/ideas/contracts/service/ShadowInferenceProperties.java`, `contract-service/src/main/resources/application.properties`, `contract-service/src/test/java/com/ideas/contracts/service/CheckRunAdvisoryPersistenceTest.java`.
4. **Advisory API/dashboard:** `contract-service/src/main/java/com/ideas/contracts/service/model/CheckRunAdvisoryResponse.java`, `contract-service/src/main/java/com/ideas/contracts/service/CheckController.java`, `contract-service/src/main/java/com/ideas/contracts/service/UiController.java`, `contract-service/src/main/resources/static/ui-checks.js`, `contract-service/src/main/resources/templates/ui/check-detail.html`.
5. **Audit documentation:** `docs/archive-consolidation-audit.md` after confirming the final file inventory/status.

Before any checkpoint, verify `git status --porcelain=v2`, `git diff --check`, exact explicit path list, and that `release-output/` and all IEMS paths remain unstaged. Recommended next implementation is a separate, reviewed `.gitignore` exclusion for `release-output/` plus correction of stale release notes; neither is performed here. Rebuild service/CLI from clean consolidated source, compare functionality and artifact hashes with the r3 historical package, and run the focused unit, launcher, API/dashboard, Flyway SQLite/PostgreSQL/MySQL suites. Use a **fresh** macOS build/output directory; validate internal/external manifests, binary target, safe archive paths, the extracted-package host suite and accepted-RC hashes before/after. Repeat natively for Linux x86_64 (WSL2 acceptance only establishes that environment); do not infer Linux acceptance from a Mac build. Keep legal/security/publication gates separate.

Exact next-task commands to begin **only after review/authorization of the checkpoint task** (illustrative read-only preflight, not executed here):

```sh
cd "$DCG_REPO"
git status --porcelain=v2
git diff --check
shasum -a 256 release-output/v0.1.0-rc.1/reviewed-macos-arm64/dcg-4.0.0-rc.1-macos-arm64.tar.gz release-output/v0.1.0-rc.1/reviewed-linux-x64/dcg-4.0.0-rc.1-linux-x64.tar.gz
```

No `git add`, branch creation, build/archive assembly, deletion, reset, commit, tag or push was run here.

## Checkpoint follow-up

The subsequent source-checkpoint task added the narrow `/release-output/` entry to `.gitignore` without moving that directory. The pre-checkpoint classification above remains the historical audit snapshot. Host-specific paths in this maintained copy were replaced with repository-relative descriptions or variable-based command templates. The reviewed macOS `THIRD-PARTY-NOTICES.txt` was kept byte-for-byte intact; its upstream CRLF/trailing spaces make an unqualified staged `git diff --check` report notice-only whitespace findings, while the remaining staged packaging files pass. The checkpoint verification report records the five commit identities, final test results and preservation checks.
