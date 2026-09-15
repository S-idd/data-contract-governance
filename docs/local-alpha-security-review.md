# Local alpha alert disposition — 2026-09-14

## Remote build pin established — 2026-09-15

Tomcat upgrade committed and pushed as `4ca0fa42c769749f37fd1d5306bbf5b1c0054aa0`.
Fetched origin/main and verified this commit is reachable using merge-base --is-ancestor.
Assembler, staging (via assembler constants) and SBOM source references now select this
revision for the next build. A regression test rejects the superseded Java pin. Earlier
local-only status below is historical. Existing archives have not been rebuilt or relabelled;
remote alert closure is not claimed. Pause before assembly for the user's design discussion.

## Source upgrade — 2026-09-15

The working-tree Maven pin now aligns tomcat-embed-core/el/websocket at 10.1.59.
Full reactor `./mvnw -B -ntp verify` succeeded using Temurin 21.0.12.1+1:
251 tests, 232 passed, 19 skipped, zero failures/errors. Inspected the newly built service
JAR and confirmed all three embedded Tomcat JARs are exactly 10.1.59.
The three CVEs below are addressed by the upstream fixes in this newly built dependency
version, not by suppressing alerts or assuming unreachability. No CVE exploit reproduction
or comprehensive vulnerability scan is claimed.

This is a local source/build change, not an updated published candidate. Existing Step 7
archives still contain 10.1.55 and retain their old security dispositions below. Commit/push,
approval of a new final Java source pin, regenerated SBOM/notices/build-info and exact-archive
Mac/WSL2 retesting remain required. No remote alert closure is claimed.
Build log: `/Users/siddarthkanamadi/Downloads/dcg-tomcat-10159-verify.log`.

Live native Mac diagnostic smoke also passed CLI, paired readiness, loopback listeners,
port-conflict rejection, repeat start, authentication, AI outage handling, repeated stop,
restart and persistence. Used a fresh extracted copy with only the service JAR replaced;
this is NOT a release package and its original checksums/provenance no longer describe
the substituted JAR. Source archives were untouched. Diagnostic directory:
`/Users/siddarthkanamadi/Downloads/dcg-tomcat-10159-smoke-AXWmzi`.
All smoke-owned services stopped; retained runtime state is private.
New service JAR SHA-256:
`44478b0c2ab7c8b35f045a5c980692fbe2154846c458bbc245902b3650aa6825`.

## Historical candidate assessment

Scope: exact current Step 7 candidate, Java source
`dac3ed509d03e1bef75c47b497ca80bbdd1f2e04`. GitHub authenticated Dependabot API returned
three open critical alerts. Actual service JAR BOOT-INF/lib and its SBOM both contain
tomcat-embed-core, tomcat-embed-el and tomcat-embed-websocket **10.1.55**.
Tomcat is actively used for HTTP; it is not an unused dependency.

| Alert | CVE | Package | GitHub first patched | Disposition and applicability assessment |
| --- | --- | --- | --- | --- |
| [3](https://github.com/S-idd/data-contract-governance/security/dependabot/3) | CVE-2026-65182 | org.apache.tomcat.embed:tomcat-embed-core 10.1.55 | 10.1.58 | Deferred until tested upgrade. Reviewed application source has no servlet/container security-constraint registration; authorization is Spring Security request matchers. The specific container-constraint ordering precondition was not found. This is static evidence, not proof against all runtime configurations. |
| [2](https://github.com/S-idd/data-contract-governance/security/dependabot/2) | CVE-2026-65905 | org.apache.tomcat.embed:tomcat-embed-core 10.1.55 | 10.1.58 | Deferred until tested upgrade. The launcher enables Spring Security Basic auth; no Tomcat DIGEST authenticator configuration was found. Local configuration does not appear to exercise the affected mechanism. |
| [1](https://github.com/S-idd/data-contract-governance/security/dependabot/1) | CVE-2026-68525 | org.apache.tomcat.embed:tomcat-embed-core 10.1.55 | 10.1.58 | Deferred until tested upgrade. No container FORM login registration was found; Spring formLogin is also explicitly disabled. The reported FORM mechanism does not appear enabled in this candidate. |

Source evidence: `contract-service/src/main/java/com/ideas/contracts/service/SecurityConfig.java`
uses Spring SecurityFilterChain/httpBasic and disables formLogin; searches of service source
found no ServletSecurity, HttpConstraint, SecurityConstraint, DigestAuthenticator,
FormAuthenticator, Tomcat factory customization or deployment-descriptor login constraints.
Basic-auth acceptance passed; no CVE-specific exploit reproduction or runtime instrumentation
was performed. Loopback limits network exposure but is not a vulnerability fix.

## Fix version and compatibility

Apache explains that 10.1.58's release vote failed: the published fix release is **10.1.59**.
Do not choose an unavailable release solely from GitHub's patched-version field.
Apache rates the DIGEST and FORM issues Low, unlike GitHub's Critical classification;
we preserve both assessments rather than dismissing alerts on severity alone.
[Apache security advisory](https://tomcat.apache.org/security-10.html).

Tomcat 10.1 supports Java 11+, so Java 21 meets the documented runtime requirement.
Remaining on 10.1 preserves the Servlet 6.0 line; no Rust compiler/source change is needed.
This is compatibility reasoning, not successful integration testing of 10.1.59.
[Apache version matrix](https://tomcat.apache.org/whichversion.html).

All three remain explicitly deferred for the unpublished loopback-only candidate while
preserving its locked build/test identity. Before public release, align core/el/websocket
to a published fixed 10.1 release, build/test it, push a newly approved Java build pin,
regenerate SBOM/notices/provenance/archives and rerun acceptance against new hashes.
Alternatively require an explicit maintainer risk decision backed by deeper applicability
evidence. This report is not that approval. No alert was suppressed, dismissed or patched.

## Rust and remaining boundaries

The dcgaimodel Dependabot API returned HTTP 403 with "Dependabot alerts are disabled for
this repository." It also emitted a scope hint. This is unavailable alert visibility, not
zero vulnerabilities. No permissions or repository security settings were changed.
Enable/read its alerts with owner authorization or perform a separate Rust dependency scan
before claiming coverage. This task was an alert investigation, not a full vulnerability scan.
Licensing review, Linux acceptance, signed attestation and minimum-OS certification remain open.
