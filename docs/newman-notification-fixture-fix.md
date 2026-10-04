# Missing Newman notification fixture: delivery correction

The original Newman fix was not committed. During this investigation, the original
DCG checkout on `codex/ubuntu-24.04-evaluation-bundle` still contained uncommitted
edits to the runner, assembler, evaluator manual, and assembler tests. Git history
for the runner and assembler contained only `0b71559`. The lifecycle and advisory
identity patch commits excluded those edits. There was no delivered Newman commit
to pull or cherry-pick; this was an incomplete handoff, not a regression in those
later patches.

The IEMS helper itself is committed separately:

- IEMS repository path: `scripts/postman/seed_notification.py`.
- Introduced in `da1c723ffe9b037c335dfc245013d958a4e6755c`.
- Also verified present in the documented IEMS pin
  `6ae4970712f6f58150e935d95b71524a7c675d40`.

The new DCG patch is self-contained. It includes the helper at
`scripts/release/fixtures/iems/seed_notification.py`, copied byte-for-byte from that
IEMS pin, and the assembler copies it to **`iems/postman/seed_notification.py`** in
the output bundle. No IEMS source edit or IEMS helper download is required.

The db-demo profile disables the Kafka notification consumer, so an otherwise
working IEMS startup does not supply the collection's notification fixture. The
runner now invokes the helper with `workspace/iems-data/iems.db` before every Newman
run. The helper requires the existing database and `demo-admin`, then inserts an
unread notification. If preparation fails, the runner stops before Newman. The
collection marks/deletes its fixture, so each subsequent run seeds another one.
This runner is for the SQLite acceptance stage.

## Verify and apply

Apply the supplied single-commit patch to your current DCG branch (including
`ccbf313` and `f184b16`) with `git am /path/to/the-downloaded.patch`.
Then, inside the DCG checkout:

```bash
git log -1 --oneline -- scripts/release/assemble-ubuntu-evaluation.py \
  evaluation/ubuntu-24.04/scripts/run-iems-postman.sh
test -f scripts/release/fixtures/iems/seed_notification.py
python3 -m unittest discover -s scripts/release -p test_ubuntu_evaluation.py -v
```

To independently check the original helper in an IEMS checkout which has the pin:

```bash
git cat-file -e 6ae4970712f6f58150e935d95b71524a7c675d40:scripts/postman/seed_notification.py
git show 6ae4970712f6f58150e935d95b71524a7c675d40:scripts/postman/seed_notification.py
```

Reassemble the evaluation bundle using the documented
`assemble-ubuntu-evaluation.py` command, with a **new output directory/archive**.
An already accepted DCG archive and its matching PASS report can be reused; this
fixture-only change does not require recompiling Java or Rust. Use the clean IEMS
checkout and its existing built JAR as before.

From the new extracted evaluation bundle:

```bash
test -f iems/postman/seed_notification.py
grep 'iems/postman/seed_notification.py' SHA256SUMS
sha256sum -c SHA256SUMS
```

Run the full Ubuntu acceptance script from this fresh bundle with a new evidence
directory. The newman stage must show notification creation before the collection
and report zero failures. For a separate disposable SQLite rehearsal, start IEMS
and run `scripts/run-iems-postman.sh` twice; both should pass despite the first run
consuming its notification. The fresh-bundle full acceptance remains the target-host
proof; this patch does not claim WSL2 validation performed elsewhere.

## Validation performed

All seven evaluation assembler/runner tests passed. They verify archive and checksum
inclusion without an IEMS source helper, repeated fixture preparation after
consumption, and stopping before Newman when the database or admin is absent.

A real local IEMS JAR and the pinned IEMS Postman collection were run against a
fresh disposable SQLite database on macOS with Java 21 and Newman 6.2.2:

| Runner | Exit | Assertions | Failures |
| --- | --- | --- | --- |
| Original `0b71559` runner | 1 | 107 (6 failed assertions) | 7 total failures |
| Patched, first run | 0 | 107 passed | 0 |
| Patched, second run | 0 | 107 passed | 0 |

The owned rehearsal process was stopped afterward. No existing database or live
service was used. This is not Ubuntu 24.04 WSL2 acceptance.
