# Week 7 Showcase Kit (Phase 1)

Use this package for demos, first-user onboarding, and launch communication.

## 1) Demo Assets

Existing recordings:

1. `docs/screenshots/data-contracts-governance/how-to-run-postgres.mp4`
2. `docs/screenshots/data-contracts-governance/cli-walkthrough-postgres.mp4`
3. `docs/screenshots/data-contracts-governance/cli-walkthrough-sqlite.mp4`

Primary screenshot set:

1. `docs/screenshots/data-contracts-governance/1.png`
2. `docs/screenshots/data-contracts-governance/2.png`
3. `docs/screenshots/data-contracts-governance/3.png`
4. `docs/screenshots/data-contracts-governance/5.png`
5. `docs/screenshots/data-contracts-governance/6.png`

Recommended short GIF (generated from compose flow):

1. `docs/screenshots/data-contracts-governance/phase1-compose-demo.gif`

GIF generation command:

```bash
ffmpeg -y -i docs/screenshots/data-contracts-governance/how-to-run-postgres.mp4 \
  -ss 00:00:03 -t 00:00:08 \
  -vf "fps=12,scale=960:-1:flags=lanczos" \
  docs/screenshots/data-contracts-governance/phase1-compose-demo.gif
```

## 2) Fast Demo Script

Preferred demo path (fresh machine):

```bash
bash scripts/demo/run-compose-demo.sh
```

Local non-Docker path (existing):

```bash
bash scripts/demo/run-local-demo.sh
```

## 3) 5-Minute Talking Track

1. Problem: schema changes can silently break downstream systems.
2. Submit: run a check from UI or API.
3. Run: show async lifecycle (`QUEUED -> RUNNING -> PASS/FAIL`).
4. Explain: open a FAIL run and show actionable details.
5. Operate: show Compose path, health endpoint, and run history.

## 4) Links to Share

1. Release notes: `docs/week7-phase1-release-notes.md`
2. Compose quickstart: `docs/quickstart-compose.md`
3. Week 6 baseline: `docs/week6-docker-compose-production-baseline.md`
4. Feedback form: `.github/ISSUE_TEMPLATE/phase1-feedback.yml`
5. Feedback log template: `docs/week7-feedback-log-template.md`
