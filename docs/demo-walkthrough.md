# Local Demo Walkthrough

This walkthrough is the fastest way to show the value of the project to another developer.

## Goal

Show a full local flow in under 5 minutes:

- contract catalog is browsable in the UI
- compatibility history is visible without CLI-only digging
- a failing change is explained with actionable guidance

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

## Demo Flow

### 1. Dashboard

Open:

- `http://localhost:8080/ui`

What to show:

- contract count
- recent checks list
- mixed PASS and FAIL history in one place

What to say:

- "A developer can immediately see whether recent contract changes are safe or risky."

### 2. Contracts Page

Open:

- `http://localhost:8080/ui/contracts`
- `http://localhost:8080/ui/contracts/orders.created`

What to show:

- searchable contract inventory
- versions for `orders.created`
- recent checks tied to that contract

What to say:

- "This removes the need to jump between raw files, database rows, and CLI output."

### 3. Failing Check Detail

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

### 4. API Path

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

- contracts are discoverable
- compatibility history is visible
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
