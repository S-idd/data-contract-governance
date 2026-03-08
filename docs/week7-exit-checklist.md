# Week 7 Exit Checklist (Showcase Package)

Use this checklist to mark Week 7 complete against the plan.

## Current Status (Updated: 2026-03-07)

- [x] Local demo script verified by owner on local PostgreSQL.
- [x] Local demo flow validation completed by owner (UI + API path).
- [ ] Third-party dry run pending (friend unavailable at the moment).

## Exit Criteria

- [ ] A third-party developer runs the demo from docs without ad-hoc help.
- [x] Demo script runs fully local (no Docker) with local PostgreSQL.
- [ ] Quickstart setup time is <= 15 minutes.
- [ ] UI walkthrough (problem -> explanation -> next action) is <= 5 minutes.
- [ ] Screenshot set is captured for showcase materials.

## Required Inputs

- Demo script: `scripts/demo/run-local-demo.sh`
- Setup doc: `quickstart-local.md`
- Walkthrough doc: `docs/demo-walkthrough.md`

## Third-Party Dry Run

- [ ] Status: pending (friend unavailable; schedule this before Week 7 sign-off).
- [ ] Tester name:
- [ ] Test date:
- [ ] Machine/OS:
- [ ] PostgreSQL version:
- [ ] Start time:
- [ ] First successful UI load time:
- [ ] Total setup duration:
- [ ] Was any verbal help needed? (yes/no):

### Blockers Observed

- [ ] Blocker 1:
- [ ] Blocker 2:
- [ ] Blocker 3:

### Fixes Applied

- [ ] Doc fix 1:
- [ ] Script fix 1:
- [ ] Command fix 1:

## Showcase Screenshot Set

- [ ] Dashboard (`/ui`)
- [ ] Contracts list (`/ui/contracts`)
- [ ] Contract detail (`/ui/contracts/orders.created`)
- [ ] FAIL check detail (`/ui/checks/<runId>`)
- [ ] Swagger UI (`/swagger-ui/index.html`)

## Verification Commands

```bash
cd /path/to/data-contract-governance
bash scripts/demo/run-local-demo.sh
```

```bash
cd /path/to/data-contract-governance
mvn -pl contract-service -am \
  -Dtest=CheckRunStorePostgresPathTest,CheckControllerPostgresSuccessIntegrationTest,CheckControllerPostgresAuthFailureIntegrationTest,CheckControllerPostgresNetworkFailureIntegrationTest,CheckControllerPostgresSchemaMismatchIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

## Sign-Off

- [ ] Week 7 accepted
- [ ] Owner sign-off:
- [ ] Date:
