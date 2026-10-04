# IEMS Newman coverage across SQLite, PostgreSQL, and MySQL

## Change and use

The Ubuntu evaluation acceptance runner now executes the same IEMS collection for
all three backends, between each backend's real start and stop. SQLite keeps its
existing check name `newman-iems-api`; the added checks are `newman-iems-postgres`
and `newman-iems-mysql`. A successful complete suite has **23 checks** (previously
21), with `check_count` and a separate `api_database_matrix` containing PASS, FAIL,
or NOT_RUN for each backend. Startup alone cannot satisfy the API matrix.

`start-iems.sh` records the selected backend in `workspace/state/iems/backend`.
`run-iems-postman.sh [sqlite|postgres|mysql]` defaults to that backend and rejects
an explicit mismatch. Restart an instance launched by an older script before using
the updated runner, so the backend record exists. Each invocation writes its own
`workspace/evidence/newman-<backend>-<timestamp>-<unique>/results.json`.

The packaged helper at `iems/postman/seed_notification.py` still seeds immediately
before each run, including repeat runs after the collection deletes its fixture.
SQLite uses its existing database file. PostgreSQL/MySQL use `docker compose exec -T`
(or the existing podman-compose fallback) and the native database client in the
matching service to insert into `iems_app`. The server timestamp/boolean types are
used for those backends; SQLite retains its existing timestamp representation.
A missing admin fails the insert rather than silently proceeding with zero rows.
Passwords come from the service environment and are not placed in command-line
password values. No host database clients or extra Python packages are required.

No Compose YAML or generated `.env` changes are necessary: existing services,
credentials, initialization scripts, application databases, and ports support this.
The same Compose file and `.env` used by startup are passed to the seed helper.

Apply the delivered incremental patch with `git am /path/to/downloaded.patch` on
top of your notification-fixture commit (`28de53b`). Reassemble into a fresh output
bundle/archive using the existing accepted DCG archive/report and IEMS JAR; no
Java or Rust recompilation is required by this tooling change. Existing extracted
bundles do not gain these changes automatically.

## Validation performed

Real service starts used the updated `start-iems.sh`, including real DCG CLI gates,
and the unchanged full IEMS Postman collection under Newman 6.2.2. Each database was
run twice with a fresh seed before each invocation. Backend mismatch requests were
also rejected for all three backends. IEMS stopped between backends, and rehearsal
containers and their isolated volumes were removed afterward.

| Backend | First run | Repeat run |
| --- | --- | --- |
| SQLite | 107 assertions passed, 0 failures | 107 passed, 0 failures |
| PostgreSQL | 107 assertions passed, 0 failures | 107 passed, 0 failures |
| MySQL | 107 assertions passed, 0 failures | 107 passed, 0 failures |

The initial proxy-stress rehearsal allowed npx registry retries and still passed
all six runs; repeat runs took about 72 seconds each before cache fallback. The
included rehearsal now explicitly uses npm offline mode on the second run to
separate that delay from loopback API validation.

Environment: macOS 27.0 ARM64, Java 21.0.10 (Oracle), Docker Engine 29.7.2 with Linux
ARM64 containers, PostgreSQL 16.15 (`postgres:16`), MySQL 8.0.46 (`mysql:8.0`). The
existing local IEMS/DCG CLI JARs were used; input hashes accompany the evidence.
The IEMS source checkout was `77297f82cf33d67431ccd40c1a2638275c4611c2`; its tested
collection matches the evaluation pin `6ae4970`. A checkout ID alone is not proof
of the prebuilt JAR's source revision.

The separate assembler/runner tests verify helper inclusion, repeat seeding and
fail-fast behavior; report/orchestration tests verify 23 checks, start/API/stop
ordering, and that a PostgreSQL API failure is reported as FAIL with MySQL NOT_RUN
and cleanup still attempted. Those report tests simulate the host guard; they are
not a run of the Ubuntu acceptance suite.

A reusable real rehearsal is included:

```bash
python3 scripts/release/rehearse-iems-database-matrix.py \
  --iems-root /absolute/iems \
  --iems-jar /absolute/iems/target/inclusive-education-management-system-1.0.0-SNAPSHOT.jar \
  --dcg-cli-jar /absolute/contract-cli-4.0.0-rc.1-all.jar \
  --evidence /absolute/new/private-matrix-evidence
```

It reserves ports 8090, 54329, and 33069, creates an isolated Compose project, and
uses only its own temporary bundle/database state. The second Newman run per backend
sets unreachable HTTP proxies and uses the already cached Newman package in npm
offline mode. This tests local API bypass separately from registry availability.

## Explicitly outstanding

**Ubuntu 24.04 x86-64 under WSL2 with Temurin 21.0.12.1+1 is deferred and unconfirmed.**
The full Ubuntu acceptance cycle, including its real host guards and all DCG checks,
was not run here. The user's resource limits and Docker Desktop WSL integration were
not reproduced. Podman fallback is also unvalidated.

When WSL time is available, recheck:

- Docker Desktop integration and host access to published IPv4 loopback database
  ports 54329/33069, plus IEMS port 8090. Running the seed client inside a container
  does not by itself prove that Java can reach the host-published port; the API runs
  and JDBC startup must succeed too.
- Proxy variables: the runner appends loopback addresses to both `NO_PROXY` and
  `no_proxy`. Health probes disable curlrc and use direct IPv4 with bounded timeouts.
  Corporate registry access for `npx` still needs a working proxy or cached Newman;
  it is deliberately not disabled globally. Unreachable registry proxies can delay
  npx even when all local API calls succeed.
- Startup timing and resources with 5 GB / 2 CPUs. The databases coexist but IEMS/API
  runs are sequential; existing readiness limits and a 30-second server seed timeout may expose
  host-specific delays. No mirrored networking is required or assumed by the scripts.
- A final real report with 23 checks, all three API matrix entries PASS, and released
  ports after cleanup. Until that exists, do not label the WSL2 evaluation accepted.
