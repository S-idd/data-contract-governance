# Security Policy

## Supported releases

Security fixes are made on the latest released minor version. Development snapshots and older
release lines may receive a fix only when maintainers judge the issue to be severe and the fix is
low risk. See [the support policy](docs/support-policy.md) for runtime and backend boundaries.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability and do not include credentials, access
tokens, private endpoints, or customer data in any report.

Use GitHub's **Report a vulnerability** control in this repository's Security tab to submit a
private report. If that control is not enabled, contact a repository maintainer privately through
their GitHub profile and include the repository name, affected version or commit, impact, and
minimal reproduction steps.

Maintainers will acknowledge a report within five business days, provide periodic status updates,
and coordinate a fix and disclosure timeline with the reporter. Please allow reasonable time for
triage before public disclosure.

## Secret handling

Never commit a real `.env` file, cloud credential, database password, private key, or token.
Use the tracked `config/*.env.example` files only as templates, copy them to an ignored local
`.env.*` file, and supply production values through a secret manager or deployment platform.
If a secret is committed, revoke or rotate it immediately; deleting it from a later commit is not
sufficient.
