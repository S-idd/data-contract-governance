# Week 7 Exit Checklist (Phase 1 Release + Showcase)

Use this checklist to mark Week 7 complete against the plan:

- publish release notes
- publish demo video/GIF + walkthrough
- refresh docs
- collect first-user feedback
- finalize Phase 1 GA candidate

## Current Status (Updated: 2026-04-24)

- [x] Week 6 compose baseline implemented.
- [x] Compose quickstart script added (`run-compose-demo.sh`).
- [x] Phase 1 release notes draft added.
- [x] Showcase kit doc added.
- [x] Feedback issue template + log template added.
- [ ] First-user dry run completed and logged.
- [ ] Phase 1 GA candidate approved.

## Exit Criteria

- [ ] Fresh-machine quickstart (compose) median setup time <= 10 minutes.
- [ ] At least 1 external user completes quickstart without ad-hoc help.
- [ ] Release notes are final and share-ready.
- [ ] Showcase assets include at least 1 video and 1 short GIF.
- [ ] README + quickstart docs reflect the final recommended path.
- [ ] Feedback is captured in issue template or feedback log.

## Required Inputs

1. Compose demo script: `scripts/demo/run-compose-demo.sh`
2. Compose quickstart: `docs/quickstart-compose.md`
3. Release notes: `docs/week7-phase1-release-notes.md`
4. Showcase kit: `docs/week7-showcase-kit.md`
5. Feedback template: `.github/ISSUE_TEMPLATE/phase1-feedback.yml`
6. Feedback log: `docs/week7-feedback-log-template.md`

## First-User Dry Run Record

- [ ] Tester name:
- [ ] Date:
- [ ] Environment (OS/Java/DB):
- [ ] Start time:
- [ ] First health-check time:
- [ ] First successful check run time:
- [ ] Total setup duration:
- [ ] Verbal/help intervention needed? (yes/no):

## Blockers Observed

- [ ] Blocker 1:
- [ ] Blocker 2:
- [ ] Blocker 3:

## Fixes Applied

- [ ] Doc fix:
- [ ] Script fix:
- [ ] Config fix:

## Verification Commands

```bash
cd /path/to/data-contract-governance
bash scripts/demo/run-compose-demo.sh
```

```bash
cd /path/to/data-contract-governance
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
TEST_POSTGRES_JDBC_URL="jdbc:postgresql://localhost:5432/contracts?currentSchema=dcg_dev" \
TEST_POSTGRES_USERNAME="<your_pg_user>" \
TEST_POSTGRES_PASSWORD="<your_pg_password>" \
./mvnw clean test
```

## Sign-Off

- [ ] Week 7 accepted
- [ ] Phase 1 GA candidate accepted
- [ ] Owner sign-off:
- [ ] Date:
