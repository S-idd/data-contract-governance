# Production limitations and required external validation

DCG can be run in a hardened deployment, but this repository does not itself provide a managed
database, cloud account, backup service, or uptime SLA. A successful local test or Compose demo
is not production approval.

## MySQL is beta

The project includes MySQL 8.4 migrations, pool/privilege/concurrency checks, idempotent check
submission, and an isolated restart/restore drill. The latest local evidence is recorded in
`docs/verification/mysql-recovery-drill-latest.md`.

Before declaring MySQL production-ready, the deployment owner must validate all of the following
against the actual managed provider and topology:

1. Private endpoint access only; no public database port.
2. TLS hostname verification and CA trust using the provider endpoint.
3. Separate non-root runtime and migration identities with least-privilege grants.
4. Encrypted automated backups, binary-log retention, point-in-time recovery, and an isolated
   restore that meets signed-off RPO/RTO.
5. Primary-to-replica failover under active workload, with recovery evidence proving no duplicate
   jobs and no lost evidence state.
6. A live private-cluster canary rollout and tested rollback.

The Kubernetes templates under `deploy/kubernetes/mysql-private` are a deployable baseline, not
proof that any cloud provider has these controls enabled.

## Docker Compose is local-only

The Compose stack intentionally publishes service and PostgreSQL ports to the local host and uses
demo credentials in an ignored local environment file. Do not expose it directly to the internet
or reuse its credentials, networking, backup model, or local volume as a production design.

## Evidence retention and S3

WORM archive/purge must be enabled only with a bucket that has the required Object Lock,
versioning, access control, encryption, retention, and legal-review settings. DCG verifies archive
readback and checksum before raw payload deletion, but an application check cannot substitute for
the bucket and IAM controls owned by the account administrator.
