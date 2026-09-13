# Release and versioning policy

## Versioning

The current publication version is `4.0.0-alpha.1`, with Git tag `v4.0.0-alpha.1`.
All Maven reactor project/parent versions and published artifact filenames use that same
version. This is a planned macOS/Linux local-demo prerelease, not a production or GA release.

Historical tags v1.0, V2.0 and v3.0 retained Maven `0.1.0-SNAPSHOT`; that Maven version
was stale, not a separate supported 0.x line. Preserve those tags. The V4 alpha continues
the published product lineage and supersedes the earlier 0.x policy and candidate drafts.

Use Semantic Versioning: advance `4.0.0-alpha.N`, then `4.0.0-rc.N` after acceptance gates
pass, toward stable `4.0.0`. Stable breaking changes require a major version; compatible
features a minor version; compatible fixes a patch version. Alpha APIs and configuration
may change, with changes documented. Development snapshots are not publication artifacts.
See [the package specification](local-prerelease-packaging.md) for exact names and pins.

## Release checklist

1. Ensure CI is green: tests, changed-contract check, security scanning, dependency/vulnerability
   checks, and SBOM generation.
2. Update [CHANGELOG.md](../CHANGELOG.md), including support-level or limitation changes.
3. Run `docker compose -f docker-compose.yml config` and the documented Compose smoke test.
4. For any supported database claim, attach the corresponding test and recovery evidence.
5. For MySQL, do not claim GA until the provider-specific gates in
   [production limitations](production-limitations.md) have passed.
6. For the local alpha, verify extracted packages on each advertised platform, toolchain/source
   provenance, checksums, notices and SBOMs. No hosted deployment is required.
7. Only after approval and passing gates, tag the exact build commit as `v4.0.0-alpha.1`,
   publish prerelease notes and attach verified assets. Future tags use `vMAJOR.MINOR.PATCH`
   with the appropriate prerelease suffix. A version-alignment commit alone is not publication.

## Compatibility promise

Public CLI flags, service API shapes, configuration names, and storage migrations should remain
compatible within a stable release line. A breaking change requires migration guidance, changelog
coverage, and a documented versioning decision before release.
