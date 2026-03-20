# Local Demo Walkthrough

This walkthrough is the fastest way to show the value of the project to another developer.

## Goal

Show a full local flow in under 5 minutes:

- submit a compatibility check from the UI
- watch it run asynchronously
- explain a failing change with actionable guidance
- contract catalog is browsable in the UI

## Prerequisites

- PostgreSQL is running locally
- database `contracts` exists
- these env vars are set:

```bash
export TEST_POSTGRES_JDBC_URL="jdbc:postgresql://localhost:5432/contracts"
export TEST_POSTGRES_USERNAME="<your_pg_user>"
export TEST_POSTGRES_PASSWORD="<your_pg_password>"
```

## Start The Demo

```bash
cd /path/to/data-contract-governance
bash scripts/demo/run-local-demo.sh
```

The script does four things:

- builds the CLI fat jar
- records one PASS run and one FAIL run in local PostgreSQL
- starts `contract-service` against the same database
- prints the exact UI and API URLs for the seeded demo runs

Keep that terminal open while demoing. Use `Ctrl+C` in that terminal when you want to stop the service.

## Demo Flow (Submit -> Run -> Explain)

### 1. Submit (Queue a Check Run)

Open:

- `http://localhost:8080/ui/contracts/orders.created`

What to do:

- use the "Run Compatibility Check" form
- Base Version: `v1`
- Candidate Version: `v2`
- Commit SHA: `demo-ui` (optional)
- click "Run Check"

What happens:

- you are redirected to `/ui/checks/<runId>`
- the status starts as `QUEUED` and auto-updates

### 2. Run (Watch It Execute)

What to show:

- status transitions `QUEUED -> RUNNING -> PASS/FAIL`
- execution logs appear as the runner works
- results render as soon as the run completes

What to say:

- "Checks run asynchronously, so the UI stays responsive while the runner works."

### 3. Explain (Failing Check Detail)

Open the FAIL run URL printed by the script, or use:

```bash
curl "http://localhost:8080/checks/page?contractId=orders.created&status=FAIL&limit=20&offset=0"
```

Then open:

- `http://localhost:8080/ui/checks/<fail-run-id>`

What to show:

- breaking changes list
- warnings list
- generated developer guidance
- copy-ready API and CLI snippets

What to say:

- "The app does not just say the check failed. It explains why and gives the next action."

### 4. Optional: Dashboard + Contracts Context

Open:

- `http://localhost:8080/ui`
- `http://localhost:8080/ui/contracts`
- `http://localhost:8080/ui/contracts/orders.created`

What to show:

- contract count and recent checks
- searchable contract inventory
- versions for `orders.created`
- recent checks tied to that contract

What to say:

- "A developer can immediately see whether recent contract changes are safe or risky."
- "This removes the need to jump between raw files, database rows, and CLI output."

### 5. Optional: API Path

Open:

- `http://localhost:8080/swagger-ui/index.html`

Optional curls:

```bash
curl "http://localhost:8080/checks?contractId=orders.created"
curl "http://localhost:8080/checks/page?contractId=orders.created&limit=20&offset=0"
```

What to say:

- "The UI is backed by additive APIs, so teams can integrate the same data into other tooling later."

## Expected Demo Outcome

By the end of the walkthrough, the audience should understand:

- checks are submitted and executed asynchronously
- contracts are discoverable and searchable
- compatibility history is visible in the UI
- failing changes are explainable
- the system works fully local without Docker

## Troubleshooting

If the script fails:

- make sure nothing else is listening on `localhost:8080`
- make sure `TEST_POSTGRES_JDBC_URL`, `TEST_POSTGRES_USERNAME`, and `TEST_POSTGRES_PASSWORD` are set
- verify local DB access:

```bash
psql -h localhost -p 5432 -U "$TEST_POSTGRES_USERNAME" -d contracts -c "select current_user, current_database();"
```
