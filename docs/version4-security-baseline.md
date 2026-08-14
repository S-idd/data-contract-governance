# Version 4 Security Baseline

- Plan ID: `PLAN-2026-V4-SECURITY`
- Status: `In progress`
- Related plan: `docs/version4-production-readiness-release-plan.md`

## 1. Shared-Profile Defaults

`prod` and `sqlite-prod-lite` enable HTTP Basic authentication and require a configured writer role for every write route. They also fail at startup unless `APP_SECURITY_USERNAME` and `APP_SECURITY_PASSWORD` are both supplied with non-default values. The local profile retains the low-friction demo defaults only for local development. Docker Compose uses explicit compose-only credentials rather than inheriting the application defaults.

Set shared-profile credentials through the deployment environment or a secret manager, never a tracked `.env` file:

```bash
export APP_SECURITY_USERNAME="dcg-operator"
export APP_SECURITY_PASSWORD="<secret-manager-injected-value>"
export APP_SECURITY_ROLES="USER,WRITER"
```

## 2. Credential Rotation Procedure

1. Create a new application credential in the deployment secret manager and update every client that writes checks or contracts.
2. Restart one service instance with the new `APP_SECURITY_USERNAME` and `APP_SECURITY_PASSWORD`, then verify `/actuator/health` and an authenticated read.
3. Verify an authenticated writer can submit a non-production smoke check and that a user without `WRITER` receives `403` for the same route.
4. Roll the remaining service instances, then revoke the old application credential after the agreed overlap window.
5. Rotate `CHECKS_DB_USERNAME` / `CHECKS_DB_PASSWORD` through the database or secret manager using the same staged rollout. Verify health, Flyway history, and a known check run before revoking the old database credential.
6. Record the rotation time, operator, credential reference name, and validation result. Do not record secret values.

## 3. Covered Write Routes

| Route | Required when security is enabled | Audit action |
|---|---|---|
| `POST /checks` | `WRITER` | `CHECK_RUN_CREATE` |
| `POST /contracts` | `WRITER` | `CONTRACT_CREATE` |
| `POST /contracts/{contractId}/versions` | `WRITER` | `CONTRACT_VERSION_CREATE` |
| `POST /ui/contracts/{contractId}/checks` | `WRITER` | `CHECK_RUN_CREATE` |

Focused integration tests cover unauthenticated, unauthorized, and authorized API paths, plus audit records for check and contract writes. Notification payloads and delivery errors are redacted separately.
