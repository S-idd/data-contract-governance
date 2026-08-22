# Evidence S3 WORM archive rollout

This is a production rollout procedure for immutable compatibility evidence. It is separate from the S3 artifact-storage Beta: use a new, dedicated evidence bucket. Do not reuse the artifact bucket or its 90-day noncurrent-version lifecycle policy.

## Preconditions

1. Use an AWS bucket created with **S3 Object Lock enabled** and versioning enabled. Object Lock cannot be safely treated as an afterthought on an existing bucket.
2. Block public access, enforce bucket-owner ownership, and prohibit lifecycle rules that expire current or noncurrent evidence versions before the required retention period.
3. Use a workload IAM role. Production DCG rejects static access keys, path-style addressing, and custom S3 endpoints.
4. Set an explicit `CHECKS_EVIDENCE_ARCHIVE_S3_EXPECTED_BUCKET_OWNER`. It binds requests to the intended AWS account.
5. Give the runtime role only `s3:GetObjectLockConfiguration` on the bucket and `s3:GetObject`, `s3:PutObject` on the configured evidence prefix. Do not grant delete permissions or `s3:BypassGovernanceRetention`.
6. If the bucket uses SSE-KMS, its key policy must allow S3 to use the key for this bucket and runtime role. Do not make the KMS key deletable before the evidence-retention obligation ends.

The deployment values are documented in [evidence-archive-s3.properties.example](../config/evidence-archive-s3.properties.example). For a controlled Compose run, start from [compose.evidence-worm.env.example](../config/compose.evidence-worm.env.example). Keep credentials out of both files and source them through the AWS workload identity chain.

## Pre-deployment verification

Using an approved operator profile, record these non-secret checks against the exact bucket and region:

```bash
aws s3api get-object-lock-configuration --bucket <evidence-bucket> --region <region>
aws s3api get-bucket-versioning --bucket <evidence-bucket> --region <region>
aws s3api get-public-access-block --bucket <evidence-bucket> --region <region>
aws s3api get-bucket-encryption --bucket <evidence-bucket> --region <region>
```

Confirm Object Lock is `Enabled`, versioning is `Enabled`, public access is blocked, and lifecycle rules cannot delete evidence before its legal retention period. The application creates every archived object with **COMPLIANCE** mode and at least 2,555 days (seven years) of retention; it then reads back the exact returned object version and verifies the SHA-256 checksum before raw evidence can be removed from the database.

## Safe enablement sequence

1. Deploy with `CHECKS_EVIDENCE_RETENTION_ENABLED=false` or `CHECKS_EVIDENCE_RETENTION_DRY_RUN=true`.
2. Set `CHECKS_EVIDENCE_ARCHIVE_MODE=S3_WORM` and deploy using the `prod` profile. Docker Compose forwards every archive, retention, and evidence rate-limit setting to the service. Startup validates the bucket's Object Lock configuration. A wrong region, IAM denial, missing Object Lock, filesystem mode, or invalid production S3 configuration prevents startup or purge.
3. Review `POST /checks/evidence/retention/dry-run` with a normal operator account. Confirm the candidate count and legal holds are expected.
4. Assign `APP_SECURITY_RETENTION_ROLE` only to the retention operator; a normal `WRITER` cannot invoke `POST /checks/evidence/retention/run`.
5. Enable retention and turn off dry-run. Start with `CHECKS_EVIDENCE_RETENTION_BATCH_SIZE=1` for the first controlled run.
6. Review the resulting retention event, archive location including `versionId`, and checksum. Confirm the database raw-payload purge marker is set only after the verified archive receipt is recorded.
7. Increase the batch size gradually, record the change approval and deployment values, and monitor archive failures, `403` responses, unexpected `301` region responses, and checksum failures.

The Compose template temporarily enables Basic authentication only to import a controlled, non-sensitive test document. It is not a production fallback: remove both `APP_SECURITY_EVIDENCE_AUTH_*` overrides and use the approved OIDC workload identity before deploying the retention setting.

## Recovery and incident handling

- **Archive write/readback failure:** DCG fails closed and leaves the database raw payload intact. Fix IAM, region, bucket ownership, or KMS policy, then rerun the retention operation. The archive key is deterministic; a successfully written object is checksum-validated and reused rather than overwritten.
- **Service cannot start:** leave retention disabled, resolve the reported production admission check, and redeploy. Do not switch to filesystem storage in a production profile.
- **Evidence retrieval:** use the `s3://...?...versionId=<version>` stored in the retention event. Retrieve that exact version and verify its SHA-256 against the recorded archive checksum before using it as audit evidence.
- **Legal hold or approval dispute:** do not run the mutating endpoint until the hold/reference is resolved according to the retention policy. Preserve the archive receipt and retention events as the audit trail.

Never delete an Object-Lock evidence object to “fix” a failed run. A failed purge is recoverable; loss of immutable evidence is not.
