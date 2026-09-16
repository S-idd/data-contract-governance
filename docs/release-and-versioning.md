# Release and versioning policy

## Versioning

The selected next publication version is `4.0.0-rc.1`, with Git tag `v4.0.0-rc.1` planned only after the release gates pass; no RC tag or final archive exists yet.
All Maven reactor project/parent versions and published artifact filenames use that same
version. This is a planned local-demo prerelease for native macOS ARM64 and Linux x64 on WSL2, not a production or GA release. The Rust 0.1.0 executable is pinned by commit and embedded for asynchronous, logging-only, fail-open shadow inference; it has no independent RC release. Compose is outside this prerelease asset set.

Historical tags v1.0, V2.0 and v3.0 retained Maven `0.1.0-SNAPSHOT`; that Maven version
was stale, not a separate supported 0.x line. Preserve those tags. The V4 alpha continues
the published product lineage and supersedes the earlier 0.x policy and candidate drafts. The historical alpha archives and evidence remain unchanged.

Use Semantic Versioning: advance `4.0.0-alpha.N`, then `4.0.0-rc.N` after acceptance gates
pass, toward stable `4.0.0`. Stable breaking changes require a major version; compatible
features a minor version; compatible fixes a patch version. Alpha APIs and configuration
may change, with changes documented. Development snapshots are not publication artifacts.
See [the package specification](local-prerelease-packaging.md) for exact names and pins.

## Release checklist

1. Ensure CI is green: tests, changed-contract check, security scanning, dependency/vulnerability
   checks, and SBOM generation.
2. Update [CHANGELOG.md](../CHANGELOG.md), including support-level or limitation changes.
3. For this local archive RC, do not add Compose assets; source-tree Compose remains a separate development/demo path.
4. For any supported database claim, attach the corresponding test and recovery evidence.
5. For MySQL, do not claim GA until the provider-specific gates in
   [production limitations](production-limitations.md) have passed.
6. For the local RC, verify newly built extracted packages on both advertised platforms, toolchain/source
   provenance, checksums, notices, SBOMs and human redistribution approval. Prior alpha acceptance does not transfer. No hosted deployment is required.
7. Only after approval and passing gates, tag the exact build commit as `v4.0.0-rc.1`,
   publish prerelease notes and attach verified assets. Future tags use `vMAJOR.MINOR.PATCH`
   with the appropriate prerelease suffix. A version-alignment commit alone is not publication.

## Compatibility promise

Public CLI flags, service API shapes, configuration names, and storage migrations should remain
compatible within a stable release line. A breaking change requires migration guidance, changelog
coverage, and a documented versioning decision before release.
