# How to Run the S3 Beta

This guide runs Data Contract Governance with Amazon S3 as its contract-artifact store.
It is for the **S3 Beta** only. The normal local demo should continue to use
[`config/compose.live-demo.env.example`](../config/compose.live-demo.env.example) and the
filesystem backend; it does not require AWS.

## 1. Prerequisites

- Docker Desktop is running.
- AWS CLI v2 is installed.
- An AWS CLI profile with least-privilege access to one dedicated beta bucket exists.
- The profile can identify itself successfully:

  ```bash
  aws sts get-caller-identity --profile <profile> --region <region>
  ```

Never use root-account credentials. Never commit an access key, secret key, generated `.env`
file, or `/tmp/dcg-s3-demo.env`.

## 2. Where the environment file goes

From the repository root, create the local-only S3 Beta environment file:

```bash
cp config/compose.s3-beta.env.example .env.s3-beta
chmod 600 .env.s3-beta
```

The file must be at the **repository root**, alongside `docker-compose.yml`:

```text
data-contract-governance/
├── .env.s3-beta             # local only; ignored by Git
├── docker-compose.yml
└── config/compose.s3-beta.env.example
```

Edit `.env.s3-beta` and set the following values:

```dotenv
DCG_APP_PASSWORD=<unique-local-beta-password>
CONTRACTS_ARTIFACT_S3_BUCKET=<your-dedicated-beta-bucket>
CONTRACTS_ARTIFACT_S3_REGION=<your-bucket-region>
CONTRACTS_ARTIFACT_S3_ACCESS_KEY=<least-privilege-access-key-id>
CONTRACTS_ARTIFACT_S3_SECRET_KEY=<least-privilege-secret-access-key>
```

Keep `CONTRACTS_ARTIFACT_BACKEND=s3`,
`CONTRACTS_ARTIFACT_S3_FALLBACK_ENABLED=false`, and
`CONTRACTS_ARTIFACT_S3_SERVER_SIDE_ENCRYPTION=AES256` as supplied. The disabled fallback is
intentional: an S3 permission or configuration problem must be visible during the beta instead
of silently using the filesystem.

The AWS CLI profile is used by the host-side script. The S3 credentials in `.env.s3-beta` are
passed to the Docker container. They are separate concerns; the Docker container does not use
your `~/.aws` profile automatically.

## 3. Create or configure the dedicated bucket

Choose a globally unique bucket name. The command below configures Block Public Access,
bucket-owner-enforced ownership, AES256 default encryption, and versioning. It uploads only
small sample raw-data objects.

```bash
scripts/aws/s3-artifact-demo.sh setup \
  --profile <profile> \
  --region <region> \
  --bucket <your-dedicated-beta-bucket>
```

The script records non-secret bucket settings in `/tmp/dcg-s3-demo.env`. That file is only a
convenience for the current machine; `.env.s3-beta` remains the Docker configuration file.

## 4. Start the S3 Beta stack

Run the S3 beta launcher from the repository root. It reads `.env.s3-beta`, which is created
from `config/compose.s3-beta.env.example`, validates the S3 settings, and starts the Compose
stack without submitting the normal filesystem-demo check:

```bash
bash scripts/demo/run-s3-beta-demo.sh
```

Open the UI at `http://localhost:8080/ui`. Use `DCG_APP_USERNAME` and `DCG_APP_PASSWORD` from
`.env.s3-beta` when the browser requests authentication.

## 5. Seed and verify S3 contract artifacts

The demo script reads app credentials and S3 settings from `.env.s3-beta` by default. Seed and
verify:

```bash
scripts/aws/s3-artifact-demo.sh seed-contract \
  --profile <profile> \
  --region <region> \
  --bucket <your-dedicated-beta-bucket>

scripts/aws/s3-artifact-demo.sh verify \
  --profile <profile> \
  --region <region> \
  --bucket <your-dedicated-beta-bucket>
```

Successful verification lists the sample raw object and contract-artifact prefix, then returns
the `user.events` contract from `http://localhost:8080`.

## 6. Expected artifact keys

```text
contracts/<contract-id>/metadata.yaml
contracts/<contract-id>/versions/v1/schema.json
contracts/<contract-id>/versions/v1/schema.sha256
contracts/<contract-id>/versions/v2/schema.json
contracts/<contract-id>/versions/v2/schema.sha256
```

## 7. Stop or clean up

Stop the local services but retain the database volume and S3 artifacts:

```bash
docker compose --env-file .env.s3-beta -f docker-compose.yml down
```

Do **not** use the cleanup script for the shared beta bucket unless you explicitly intend to
delete every object version and the bucket itself:

```bash
scripts/aws/s3-artifact-demo.sh cleanup \
  --profile <profile> \
  --region <region> \
  --bucket <your-dedicated-beta-bucket> \
  --yes
```

For the agreed beta posture, retain current artifacts indefinitely, retain noncurrent versions
for 90 days, abort incomplete multipart uploads after 7 days, use AES256, and assign AWS cost
monitoring to the DCG maintainer/account owner. Configure those lifecycle and cost controls in
AWS before treating the bucket as release evidence.
