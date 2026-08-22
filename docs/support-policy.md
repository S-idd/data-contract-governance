# Support policy and compatibility matrix

## Release support

DCG is currently a `0.x` public beta. A `0.x` release may change APIs, configuration, storage
behavior, and operational requirements before `1.0`. Maintainers support the latest published
minor line for security fixes and best-effort bug fixes; no uptime SLA, response-time guarantee,
or managed hosting service is offered.

| Area | Supported baseline | Support level | Notes |
| --- | --- | --- | --- |
| Java runtime | 21 LTS | Supported | CI and releases target Java 21. |
| Maven | 3.9+ | Supported | Use the included Maven wrapper when possible. |
| Docker Engine / Docker Desktop | Current supported release with Compose v2 | Supported for demo | The quickstart is a local demo, not a production topology. |
| PostgreSQL metadata store | 16 in Compose | Production standard | Production still requires private networking, TLS, backups, and an operator-owned restore plan. |
| SQLite metadata store | Bundled JDBC driver | Production-lite | Single node only; no HA or automatic failover. |
| MySQL metadata store | 8.4 | Beta | Local compatibility and recovery checks exist. Managed HA, encrypted backups/PITR, and live failover remain external validation gates. |
| Filesystem contract artifacts | Local filesystem | Supported for local/demo | Back up and control the filesystem outside DCG. |
| S3 contract artifacts | AWS-compatible S3 | Beta | Requires account-specific versioning, encryption, and access-control validation. |

The exact dependency versions consumed by a release are included in its SBOM artifact. The source
`pom.xml` is the authoritative development dependency declaration.

## CI security controls

Pull requests are scanned for committed secrets and newly introduced vulnerable dependencies.
Pushes and weekly scheduled runs perform a full OSV vulnerability scan and generate an SBOM
artifact. Repository owners must enable GitHub code-scanning results and, for organization-owned
repositories, configure the required `GITLEAKS_LICENSE` GitHub secret before relying on the
secret-scan workflow as a release gate.

## Support boundaries

Issues with a reproducible supported configuration are welcome. Maintainers do not provide
production operations, cloud-account access, database administration, incident response, or a
guarantee that experimental/beta storage paths satisfy an organization's regulatory obligations.
See [production limitations](production-limitations.md) before deploying beyond a demo.
