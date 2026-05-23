# Week 13: S3 Beta User Onboarding Session

- Plan ID: `PLAN-2026-W13-S3-BETA`
- Status: `Published`
- Published date: `2026-05-23`
- Target duration: `45 minutes`
- Primary runbook: `docs/week13-s3-beta-runbook.md`
- Launch post: `docs/week13-s3-beta-launch-post.md`

## Goal

Help a first beta user run contract-service with S3 artifact storage, create a contract through the API, verify the S3 objects, and leave clear feedback for Version 4 planning.

## Prerequisites

Before the session, confirm:

- Java 21 is installed.
- Docker is running.
- AWS CLI is installed and authenticated with the selected profile.
- The AWS principal can create, configure, list, write to, and delete the beta bucket.
- The repo is checked out locally.
- No production AWS bucket or credentials will be used.

## Session Agenda

1. Context and safety guardrails - 5 minutes
2. Start Docker baseline and health check - 5 minutes
3. Create and harden a demo S3 bucket - 10 minutes
4. Run contract-service with S3 artifact backend - 10 minutes
5. Seed and verify contract artifacts - 10 minutes
6. Capture feedback and cleanup - 5 minutes

## Facilitator Checklist

- Share the launch post and runbook links.
- Confirm the user understands that S3 is beta and opt-in.
- Confirm the bucket name, AWS profile, and region before running setup.
- Watch for credential or Docker environment confusion.
- Ask the user to narrate any unclear step instead of fixing it silently.
- Confirm cleanup completed or intentionally leave the bucket for follow-up testing.

## User Walkthrough

Start from the repo root:

```bash
cd /path/to/data-contract-governance
```

Create and configure a beta bucket:

```bash
scripts/aws/s3-artifact-demo.sh setup --profile dcg-s3 --region ap-south-1
source /tmp/dcg-s3-demo.env
```

Start contract-service with S3 backend using the command printed by the setup script. Then verify service health:

```bash
curl -fsS "$DCG_SERVICE_URL/actuator/health"
```

Seed contract artifacts and verify the S3 objects:

```bash
scripts/aws/s3-artifact-demo.sh seed-contract
scripts/aws/s3-artifact-demo.sh verify
```

Expected object shape:

```text
contracts/<contract-id>/metadata.yaml
contracts/<contract-id>/versions/v1/schema.json
contracts/<contract-id>/versions/v1/schema.sha256
contracts/<contract-id>/versions/v2/schema.json
contracts/<contract-id>/versions/v2/schema.sha256
```

Cleanup when finished:

```bash
scripts/aws/s3-artifact-demo.sh cleanup --yes
```

## Success Criteria

- User reaches `UP` health status.
- User creates or reuses a beta bucket with hardened defaults.
- User creates contract `v1` and compatible `v2`.
- User sees metadata, schema, and checksum objects in S3.
- User can explain which environment variables switch the artifact backend to S3.
- User provides at least one concrete adoption signal or blocker.

## Feedback Notes

Capture one section per session:

```text
Tester:
Date:
Role/team:
OS:
AWS profile:
AWS region:
Setup path used:
First health check time:
First S3-backed contract write time:
Total setup duration:
Help needed:

What worked:
1.
2.
3.

Friction or blockers:
1.
2.
3.

Adoption signal:
- Would use S3 beta in a real project?
- Minimum required changes before broader rollout:

Version 4 ideas discovered:
1.
2.
3.
```
