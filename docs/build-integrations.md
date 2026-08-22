# Offline-first build integrations

The Maven and Gradle integrations run compatibility locally. `contract-service` is never required to decide whether a build passes. Every run writes a JSON evidence artifact before an optional remote call is attempted.

## Maven

Add the plugin to the consuming project's `pom.xml`:

```xml
<plugin>
  <groupId>com.ideas.contracts</groupId>
  <artifactId>contract-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <configuration>
    <baseSchema>${project.basedir}/contracts/orders.created/v1.json</baseSchema>
    <candidateSchema>${project.basedir}/contracts/orders.created/v2.json</candidateSchema>
    <mode>BACKWARD</mode>
  </configuration>
</plugin>
```

The `check-compat` goal binds to Maven's `verify` phase by default. It writes `target/dcg-compatibility-report.json`, fails for an incompatible schema, and uses a distinct execution failure for malformed files or unavailable required reporting.

## Gradle

Publish the plugin JAR to your organization’s plugin repository, then configure it in `build.gradle`:

```groovy
plugins {
  id 'com.ideas.contracts.governance' version '0.1.0-SNAPSHOT'
}

dcgCompatibility {
  baseSchema = file('contracts/orders.created/v1.json')
  candidateSchema = file('contracts/orders.created/v2.json')
  mode = 'BACKWARD'
}
```

Run `./gradlew dcgCheckCompatibility`. The default report is `build/reports/dcg/compatibility.json`.

## Optional evidence import

Remote reporting is disabled by default. When enabled, it sends the exact local JSON artifact to `POST /checks/evidence` after the local result exists. It never changes the local compatibility result.

```xml
<remoteReportingMode>OPTIONAL</remoteReportingMode>
<remoteServiceUrl>https://dcg.example.com</remoteServiceUrl>
<contractId>orders.created</contractId>
<commitSha>${env.GIT_COMMIT}</commitSha>
<remoteTimeoutSeconds>5</remoteTimeoutSeconds>
<remoteMaxAttempts>2</remoteMaxAttempts>
<ciIdentity>github-actions</ciIdentity>
<buildUrl>${env.CI_BUILD_URL}</buildUrl>
<!-- In production inject a short-lived signed OIDC bearer token from the CI runtime. -->
<remoteAuthorization>${env.DCG_AUTHORIZATION}</remoteAuthorization>
```

Modes:

- `DISABLED`: no network call.
- `OPTIONAL`: import failure is logged, but does not change the compatibility result or mutate the evidence artifact.
- `REQUIRED`: report failure fails the build after writing the JSON artifact.

The timeout is an overall deadline for all attempts; retry delay is bounded by the remaining time. The artifact includes a stable idempotency key, engine protocol, policy-pack fingerprint, raw schema digests, local result, CI identity, and build URL. The service records the authenticated principal separately from the claimed CI identity.

### Production authentication

Production evidence import requires `Authorization: Bearer <CI-issued OIDC JWT>`. DCG validates the token signature and issuer, requires the configured audience, and then authorizes an exact `contractId` / repository / ref combination. It records verified issuer, subject, audience, repository, and ref as provenance; the client-supplied `ciIdentity` remains an untrusted label.

Use [evidence-oidc.properties.example](../config/evidence-oidc.properties.example) as the deployment configuration template. The service fails closed at startup unless `issuer-uri` is present in an explicit, unique `trusted-issuers` allowlist, and unless audience, claim names, and non-empty unique contract authorization rules are configured. This deployment supports one issuer endpoint per service instance; do not add an issuer merely because its tokens are cryptographically valid. Basic authentication is only available in an explicitly selected local/demo profile (`APP_SECURITY_EVIDENCE_AUTH_MODE=BASIC` and `APP_SECURITY_EVIDENCE_AUTH_ALLOW_BASIC=true`); it is not a production fallback.

Evidence API failures use stable codes: `AUTH_FAILED`, `CONTRACT_NOT_AUTHORIZED`, `MALFORMED_DOCUMENT`, `EVIDENCE_PAYLOAD_REQUIRED`, `EVIDENCE_PAYLOAD_TOO_LARGE`, `EVIDENCE_RATE_LIMITED`, and `EVIDENCE_IDEMPOTENCY_CONFLICT`. A stored verification outcome remains `VERIFIED`, `VERSION_SKEW`, `REJECTED`, or `UNVERIFIED`; it is not an HTTP authentication result.

## Evidence ingestion boundary controls

Use [evidence-rate-limit.properties.example](../config/evidence-rate-limit.properties.example) to configure the HTTP evidence boundary. The default payload limit is 1 MiB; requests with an oversized `Content-Length` are rejected before MVC reads them, while streaming/chunked requests are terminated as soon as they cross the limit. Rejections return `413` and `EVIDENCE_PAYLOAD_TOO_LARGE`.

The default shared quota is 60 imports per authenticated identity and repository per one-minute fixed window. It is stored in the shared metadata database, so it applies across service replicas. A rejected request returns `429`, `EVIDENCE_RATE_LIMITED`, and a positive `Retry-After` value. The Maven and Gradle integrations use the shared build-support library and honor that delay, bounded by their configured remote-report timeout.

`contractId` and versioned schema files (`v1.json`, `v2.json`) are required for a remote import. A purely offline run can still create a local report without them, but that report cannot be verified by the service until it is configured with registered contract/version identity.

## Replay after an outage

Preserve the JSON evidence file as a CI artifact. Replay exactly that file—do not regenerate it—once the service is available:

```bash
mvn com.ideas.contracts:contract-maven-plugin:0.1.0-SNAPSHOT:replay-evidence \
  -Ddcg.evidenceFile=target/dcg-compatibility-report.json \
  -Ddcg.remoteServiceUrl=https://dcg.example.com \
  -Ddcg.remoteAuthorization="Bearer $DCG_OIDC_TOKEN"
```

For Gradle, configure the same `remoteServiceUrl`, `remoteAuthorization`, and optionally `evidenceFile`, then run `./gradlew dcgReplayEvidence`. The server returns the original evidence row for an exact retry and returns `409 Conflict` if the same idempotency key is reused with different bytes.

Replay never stores or reuses the original CI token. Supply a fresh, short-lived OIDC Bearer token at replay time. A `401` fails immediately without retrying the expired token and tells CI to obtain a fresh token; the saved evidence file remains the artifact to replay. A `403` means the token was valid but the configured contract/repository/ref policy rejected it.

The service independently checks registered schema digests, protocol, policy-pack fingerprint, and result. A protocol or policy mismatch is stored as `VERSION_SKEW`, not converted into a misleading pass/fail. Approval workflows must require `VERIFIED` evidence.
