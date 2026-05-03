# AWS Account Safety for This Project

This project does not need your AWS username or password for Week 10/11 work.

Rules:

- Do not share AWS root credentials, console passwords, MFA codes, access keys, or secret keys in chat.
- Do not use a friend's AWS console username/password.
- Do not delete or recreate AWS accounts just for this repo.
- Build and test locally first with filesystem storage or a local S3-compatible emulator.
- When AWS is truly needed, use a new IAM user or role with least-privilege S3 access to one bucket/prefix.
- Set a billing alarm before creating real cloud resources.
- Keep real AWS S3 disabled until the local S3 adapter tests pass.

Current AWS notes to verify before using real cloud resources:

- AWS says new customers can choose a Free account plan or Paid account plan. New customers receive USD 100 in credits after account creation and can earn up to another USD 100 by completing activities. The Free account plan is for no-cost experiments for up to six months or until credits are used. Official doc: https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier.html
- Amazon S3 pricing says costs include storage, requests, data retrieval, transfer, management, replication, and related features. Official pricing: https://aws.amazon.com/s3/pricing/
- AWS Budgets can track cost and usage and send alerts. Official guide: https://docs.aws.amazon.com/cost-management/latest/userguide/budgets-create.html
- S3 encrypts new uploads by default with SSE-S3. Official S3 encryption doc: https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingServerSideEncryption.html

For a student/free-tier workflow, the safest order is:

1. Finish local code and tests.
2. Add S3 adapter tests against a local emulator.
3. Create or choose a dedicated AWS sandbox account.
4. Enable MFA on the root account.
5. Create a least-privilege IAM identity for the app.
6. Set budget alerts.
7. Run one small S3 smoke test.
8. Delete test objects and confirm the bill/budget dashboard.
