# Release and versioning policy

## Versioning

DCG follows Semantic Versioning after `1.0.0`. Until then, it uses `0.MINOR.PATCH` public-beta
versions: a minor release may include breaking changes, while a patch release is intended for
backwards-compatible fixes. Maven development builds use the `-SNAPSHOT` suffix and are never a
release artifact.

## Release checklist

1. Ensure CI is green: tests, changed-contract check, secret scan, dependency/vulnerability
   checks, and SBOM generation.
2. Update [CHANGELOG.md](../CHANGELOG.md), including support-level or limitation changes.
3. Run `docker compose -f docker-compose.yml config` and the documented Compose smoke test.
4. For any supported database claim, attach the corresponding test and recovery evidence.
5. For MySQL, do not claim GA until the provider-specific gates in
   [production limitations](production-limitations.md) have passed.
6. Tag the exact commit as `vMAJOR.MINOR.PATCH`, publish release notes, and attach the CI SBOM.

## Compatibility promise

Public CLI flags, service API shapes, configuration names, and storage migrations should remain
compatible within a stable release line. A breaking change requires migration guidance, changelog
coverage, and a documented versioning decision before release.
