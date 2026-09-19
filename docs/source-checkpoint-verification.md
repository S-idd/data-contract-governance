# Phase 1 + Phase 2 source checkpoint verification

Verification date: 2026-09-19. Result: **READY_FOR_MACOS_ARCHIVE** for an unpublished development archive. This result does not authorize an RC, tag, push, publication, or deployment.

## Repository state

- Starting branch: `main`
- Starting SHA: `7138c9047db23d118ae32e6815a9c67f97c7b713`
- Final branch: `codex/phase1-phase2-source-consolidation`
- Consolidated source SHA before this report: `f16d14fd32b67ab7082396666ecd66dd3320ed68`
- Documentation follow-up: the commit containing this file; use `git log -1 --format=%H -- docs/source-checkpoint-verification.md` to resolve it without embedding a self-referential commit hash.
- The branch was created directly from the audited SHA. Nothing was pushed.

## Logical commits

### `b4a169cee73e3af41069a5d6e4f2c58d527f30bd` — `feat(release): add deterministic no-AI launcher support`

- `packaging/local/README.md`
- `packaging/local/bin/dcg`
- `packaging/local/bin/start`
- `packaging/local/bin/status`
- `packaging/local/bin/stop`
- `scripts/release/test_no_ai_foundation.py`

### `8cef4a597efd45c0e8d23ebdcb73f0f148078438` — `build(release): verify development assembly and launcher resilience`

- `.gitignore`
- `THIRD-PARTY-NOTICES.txt`
- `scripts/release/README.md`
- `scripts/release/assemble-local.py`
- `scripts/release/test_ai_launcher_resilience.py`
- `scripts/release/test_local_packaging.py`

### `40a031ca7e51fab32ba94796d441820f8d6f3615` — `feat(service): persist deterministic-safe AI advisories`

- `contract-core/src/main/resources/db/migration-mysql/V13__create_check_run_advisories.sql`
- `contract-core/src/main/resources/db/migration/V13__create_check_run_advisories.sql`
- `contract-service/src/main/java/com/ideas/contracts/service/CheckRunRepository.java`
- `contract-service/src/main/java/com/ideas/contracts/service/CheckRunStore.java`
- `contract-service/src/main/java/com/ideas/contracts/service/CheckRunner.java`
- `contract-service/src/main/java/com/ideas/contracts/service/ShadowInferenceObserver.java`
- `contract-service/src/main/java/com/ideas/contracts/service/ShadowInferenceProperties.java`
- `contract-service/src/main/resources/application.properties`
- `contract-service/src/test/java/com/ideas/contracts/service/CheckRunAdvisoryPersistenceTest.java`

### `10b0fbd866f4fbb06841e2443d59e4c24ee63d59` — `feat(ui): display optional check-run advisories`

- `contract-service/src/main/java/com/ideas/contracts/service/CheckController.java`
- `contract-service/src/main/java/com/ideas/contracts/service/UiController.java`
- `contract-service/src/main/java/com/ideas/contracts/service/model/CheckRunAdvisoryResponse.java`
- `contract-service/src/main/resources/static/ui-checks.js`
- `contract-service/src/main/resources/templates/ui/check-detail.html`

### `f16d14fd32b67ab7082396666ecd66dd3320ed68` — `docs(release): record Phase 1 and Phase 2 source audit`

- `docs/archive-consolidation-audit.md`

Each of these 27 paths appears in exactly one commit. The two additions beyond the original 25 source/input files are the narrow `.gitignore` change and the maintained audit document. No IEMS, `.dcg/`, `target/`, database, log, runtime-state, or `release-output/` file appears in the commit range. Launcher modes remain `100755`; new Java, SQL, Python, notice, and documentation files are `100644`. Flyway V13 is in the service persistence commit.

This verification report is an authorized additional documentation-only commit because it was generated after the five planned commits; no earlier commit was amended.

## Audit and output decisions

`docs/archive-consolidation-audit.md` is maintained project documentation. It contains no credentials or private evidence contents. Host-specific paths were replaced with repository-relative descriptions or variables, while historical hashes and conclusions were retained.

`release-output/` contains generated assemblies, extracted packages, caches and release evidence. `.gitignore` now contains the narrow root-anchored rule `/release-output/`. `git check-ignore -v release-output/` resolves to that rule. The existing directory was not moved, deleted, staged, or modified by the checkpoint task.

## Safety scan

All proposed checkpoint files and the audit document were scanned before branch creation. No private-key marker or credential literal was found. Application credential settings are environment-variable placeholders; the generated local demo password is read from task-owned state. Test JDBC URLs point only to temporary SQLite files. Loopback ports 8080 and 8081 are intentional package/test interfaces. The `/Users/` and temporary-path byte strings in `scripts/release/assemble-local.py` and its packaging test are rejection rules/fixtures. The normal configuration leaves the test-only advisory adapter disabled, and `ShadowInferenceProperties` refuses it unless the `phase2-rehearsal` profile is active.

The reviewed `THIRD-PARTY-NOTICES.txt` intentionally retains upstream CRLF and trailing spaces. Its SHA-256 is `deafccec618c145c42abb3afe5225222d97732ec291af065af8c54e5e4241e0e`. An unqualified staged `git diff --check` reports those reviewed notice bytes; all other staged files passed `git diff --cached --check`. The notice was not normalized because doing so would invalidate its reviewed hash. Final clean-worktree `git diff --check` passes.

## Validation

All commands exited 0 unless a read-only absence check is described otherwise.

| Command | Result |
|---|---|
| `git diff --check` | PASS, no working-tree whitespace error |
| `python3 -m unittest scripts.release.test_local_packaging -q` | 20 tests, 0 failures/errors/skips |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -pl contract-service -am -Dtest=CheckRunAdvisoryPersistenceTest,CheckRunStoreTest,CheckRunStoreMigrationRollbackTest,ShadowInferenceObserverTest,HttpShadowInferenceGatewayTest,UiControllerIntegrationTest,CheckControllerSecurityIntegrationTest,CheckControllerDbFailureIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test -q` | 54 tests, 0 failures, 0 errors, 0 skipped |
| `DCG_TEST_PACKAGE="$IEMS_ROOT/.dcg/runtime/dcg-4.0.0-phase2-service-advisory-dev.20260918-r3-macos-arm64" IEMS_TEST_ROOT="$IEMS_ROOT" DCG_TEST_EVIDENCE="$PRIVATE_EVIDENCE/final-noai" JAVA_HOME=$(/usr/libexec/java_home -v 21) python3 -m unittest scripts.release.test_no_ai_foundation -q` | 6 tests, 0 failures/errors/skips |
| `DCG_TEST_PACKAGE="$IEMS_ROOT/.dcg/runtime/dcg-4.0.0-phase2-service-advisory-dev.20260918-r3-macos-arm64" IEMS_TEST_ROOT="$IEMS_ROOT" DCG_TEST_EVIDENCE="$PRIVATE_EVIDENCE/final-ai" JAVA_HOME=$(/usr/libexec/java_home -v 21) python3 -m unittest scripts.release.test_ai_launcher_resilience -q` | 6 tests, 0 failures/errors/skips |

The service/API/dashboard suite used Spring MockMvc and JUnit temporary SQLite files rather than a listening service port. Launcher tests used fixed loopback ports 8080 and 8081 with disposable package/state copies and private temporary evidence. Final `lsof` checks found neither port listening, and the process scan found no Java service or Rust advisory process from these tests. Maven produced only ignored `target/` output. No accepted archive was used as writable test state.

## Accepted RC preservation

| Accepted runtime-tested archive | Expected and recalculated SHA-256 | External manifest | Internal manifest |
|---|---|---|---|
| reviewed macOS ARM64 `dcg-4.0.0-rc.1-macos-arm64.tar.gz` | `7921446b1efcc229c2fb02218f8a7cce4412d5b300f2215576c1f253df58be0e` | PASS | 23/23 PASS |
| reviewed Linux x86_64 `dcg-4.0.0-rc.1-linux-x64.tar.gz` | `f3f39529704bf5eef3030c9d0e02ab03234778e60843487e8f7967c9ac07b588` | PASS | 23/23 PASS |

Each archive still has 24 regular package files and reports version `4.0.0-rc.1`. These checks establish byte preservation only. They do not resolve legal, security, publication, or broader native-Linux support gates.

## Remaining limitations and next task

The checkpoint validates the V13 migration through SQLite. The MySQL V13 script was inspected and committed but was not exercised against a live MySQL server in this task; the shared default migration path was not rerun against a live PostgreSQL server. The final development archive must retain an explicitly non-release identity and cannot claim RC acceptance. A macOS archive built from this clean checkpoint will have new bytes and requires fresh structural, checksum, launcher, service, and native-host verification. Linux requires a separate native x86_64 build and acceptance run.

Next task: build `dcg-4.0.0-phase1-phase2-dev.20260919-macos-arm64.tar.gz` in a fresh output directory from this checkpoint, generate and verify its provenance/SBOM/notice/checksums, run the macOS ARM64 extracted-package suite, and recheck the accepted RC hashes before and after. Do not overwrite any existing archive or development package.
