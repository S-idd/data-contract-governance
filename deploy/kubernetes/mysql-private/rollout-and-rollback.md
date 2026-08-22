# Private MySQL deployment, canary, and rollback

1. Render `deployment.yaml` with an immutable image digest and inject `dcg-mysql-runtime` and `dcg-mysql-migration` from the platform secret manager. Do not create database credentials in Git.
2. Apply the Service, NetworkPolicy, and Deployment. The database is external and private: this bundle intentionally contains no MySQL Service, LoadBalancer, NodePort, or public ingress.
3. Canary one new replica (`maxSurge: 1`, `maxUnavailable: 0`), then verify its authenticated health endpoint, Hikari acquisition metrics, database migrations, and a check submission with an `Idempotency-Key`.
4. Hold the canary for the agreed observation window. Promote only if error rate, acquisition p95, queue lag, and database alerts are within budget.
5. Roll back on breach: `kubectl -n dcg rollout undo deployment/dcg-contract-service`, verify the prior revision becomes ready, then replay any failed check submission using its original `Idempotency-Key`.

This is a Kubernetes deployment baseline. Provider-managed MySQL durability, private endpoint, backup encryption, and PITR must be configured independently on the selected database service.
