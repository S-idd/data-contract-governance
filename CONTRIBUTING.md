# Contributing to Data Contract Governance

Thank you for helping improve DCG. By participating, you agree to follow the
[Code of Conduct](CODE_OF_CONDUCT.md).

## Before opening a pull request

1. Discuss a substantial feature or behavior change in an issue first.
2. Keep each pull request focused and include tests for changed behavior.
3. Run `./mvnw --batch-mode --no-transfer-progress test` from the repository root.
4. For Compose changes, run `docker compose -f docker-compose.yml config` and follow the
   [Compose quickstart](docs/quickstart-compose.md).
5. Do not commit `.env`, `.env.*`, generated databases, credentials, or vendor-specific
   production configuration. Add only sanitized `config/*.env.example` templates when needed.

## Development conventions

- Target Java 21 and keep Maven module boundaries intact.
- Prefer small, backwards-compatible API changes. Document any API or migration impact.
- Keep database behavior equivalent across the supported dialects; MySQL changes need MySQL tests.
- Add release-note text under `Unreleased` in [CHANGELOG.md](CHANGELOG.md) for user-visible
  changes.
- Keep documentation factual about support levels and production limitations.

## Pull request review

Maintainers review correctness, tests, security impact, documentation, compatibility, and support
cost. A review or merge does not create a support commitment beyond the published support policy.
Contributors retain ownership of their work and grant the project the rights needed under the
[Apache-2.0 license](LICENSE).
