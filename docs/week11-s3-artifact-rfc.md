# Week 11: S3 Artifact Store Architecture RFC

- Plan ID: `PLAN-2026-W11-S3-ARTIFACTS`
- Date: `2026-05-13`
- Status: `Approved and implemented`
- Scope: `ArtifactStore contract, metadata ownership boundaries, S3 key strategy, versioning, encryption defaults`
- Exit target: `S3 architecture RFC approved`

## 1. Decision Summary

Introduce S3 as an artifact backend behind the existing `ArtifactStore` port. The metadata database remains the source of truth for check runs, logs, audit events, and query state. S3 stores contract definition artifacts: metadata files, schema versions, checksums, and future generated artifacts.

No AWS account is required for this design phase. Implementation should begin with local tests and an S3-compatible local emulator before touching a real AWS account.

## 2. Ownership Boundaries

`MetadataStore` owns:

- check run lifecycle state
- check run logs
- audit logs
- pagination/filter query indexes
- operational health around DB connectivity and migrations

`ArtifactStore` owns:

- contract ids
- `metadata.yaml`
- immutable schema version payloads
- artifact checksums
- remote object references
- best-effort cleanup when a write fails before commit

`ContractCatalogService` may cache artifact-derived summaries, but the cache is not authoritative.

## 3. Contract Shape

The Java contract is intentionally small:

- list contract ids
- read metadata
- list schema versions
- read schema JSON
- create contract
- create version
- delete newly-created artifacts during failed writes
- expose artifact references for logging, audits, and future remote backends

The current filesystem implementation remains the default. Future S3 work should add an `S3ArtifactStore` implementation without changing controllers or catalog callers.

## 4. S3 Key Strategy

Canonical keys are produced by `ArtifactKeyStrategy`:

```text
contracts/{contractId}/metadata.yaml
contracts/{contractId}/versions/{version}/schema.json
contracts/{contractId}/versions/{version}/schema.sha256
```

Examples:

```text
contracts/orders.created/metadata.yaml
contracts/orders.created/versions/v1/schema.json
contracts/orders.created/versions/v1/schema.sha256
```

Validation rules:

- `contractId` must be lowercase dot-separated, for example `orders.created`.
- `version` must match `v{number}`, for example `v1`.
- prefixes must be relative object prefixes with no `..`, backslash traversal, or empty segments.

## 5. Versioning Policy

Application-level schema versions are immutable. A request to create an existing version must fail.

Bucket versioning should be enabled for defense-in-depth, but application reads should address the logical key above, not raw S3 version ids. Raw S3 version ids may be logged in audit detail later for forensic traceability.

Rollback policy:

1. Prefer app-level rollback by creating a new schema version that restores compatibility.
2. Use S3 object version restore only for accidental mutation or deletion.
3. Never overwrite a published schema version from normal application writes.

## 6. Encryption and Access Defaults

Student/local beta default:

- Do not use a real AWS account for local development.
- Use filesystem storage or an S3-compatible local emulator.
- Do not commit credentials, access keys, or account ids.

AWS beta default:

- Use a dedicated AWS account or sandbox project.
- Use one private bucket.
- Block public access.
- Enable bucket versioning.
- Require server-side encryption.
- Prefer SSE-S3 for low-cost beta usage.
- Allow SSE-KMS only when the account owner accepts the extra cost and key-management work.
- Access through an IAM role or least-privilege IAM user, never root credentials.

Minimum IAM permissions for the app role should be scoped to one bucket and one prefix:

```text
s3:ListBucket on bucket with prefix condition
s3:GetObject
s3:PutObject
s3:DeleteObject only for failed-write cleanup paths
```

## 7. AWS Credential Policy

Do not share AWS usernames, passwords, root credentials, or console passwords in chat, docs, commits, screenshots, or environment files.

When real AWS testing starts, provide only local environment variable names or a profile name, for example:

```bash
export AWS_PROFILE=dcg-artifacts-dev
export DCG_ARTIFACT_BUCKET=your-private-bucket
export DCG_ARTIFACT_PREFIX=contracts
```

The implementation should read credentials through the AWS SDK default provider chain.

## 8. Open Questions

- Should beta S3 writes be enabled only for admins/writers, or should reads also require app auth in all profiles?
- Should checksum files be mandatory before beta, or can they land with the first S3 implementation sprint?
- Should object retention/Object Lock stay out of beta to avoid accidental cost and deletion friction?

## 9. Approval Checklist

- [x] Artifact ownership boundaries defined.
- [x] S3 object key strategy implemented and tested.
- [x] Versioning policy defined.
- [x] Encryption and access defaults defined.
- [x] Credential safety policy defined.
- [x] Human approval to start S3 implementation.
