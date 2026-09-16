# v0.1.0-rc.1 — evidence-only license and artifact inventory

Audit result: **BLOCKED**. No final `v0.1.0-rc.1` archive was found. The tarballs below are existing **`4.0.0-alpha.1` build/candidate outputs**, not assets for the requested RC. Nothing was built, tagged, pushed, published, uploaded, or approved by this audit.

## Repository and version evidence

| Repository | `git rev-parse --show-toplevel` | HEAD | Manifest version |
|---|---|---|---|
| Rust | `/Users/siddarthkanamadi/Personal_Projects/dcgaimodel` | `32ca579095ed5b91749b8c33999556624e58758f` | `Cargo.toml`: `dcgaimodel` **0.1.0** |
| Java | `/Users/siddarthkanamadi/Personal_Projects/dcg/data-contract-governance` | `62903b1bf1296c3bb7f66b68b7fcd2436c15693b` | root and reactor POMs: **4.0.0-alpha.1** |

Both worktrees were clean before this report. Rust has local tags `v0.1.0` and `v0.1.0-alpha`; neither repository has a local `v0.1.0-rc.1` tag. The requested RC version does not match the Java reactor or any located archive. `Cargo.lock` is present alongside `Cargo.toml`; neither contains a MySQL-named crate/dependency.

## Located inputs and non-archive outputs

- Rust manifests: `/Users/siddarthkanamadi/Personal_Projects/dcgaimodel/Cargo.toml`, `Cargo.lock`; container recipe: `Dockerfile`. Existing local Rust binaries (not RC release artifacts): `target/debug/dcgaimodel` (arm64, SHA-256 `01aca3865b168d136ef013dc64f37359a9120698ccae1fd40e8f12babe834233`), `target/release/dcgaimodel` (arm64, `d22e43a23cd519668427e91f9d7b4718d2b511a64c51ac2a6e76ccff42ff693d`), `target/aarch64-apple-darwin/release/dcgaimodel` (arm64, `71cb96a0152630ab6470aa53a79494684d7bfa9ef79209574c39cdf8bb510063`), and `target/x86_64-apple-darwin/release/dcgaimodel` (x64, `111899f713148a3430892004bead2c609d894f4060b9d5cb22143fa6a3e8a4c5`). Paths are relative to the Rust root.
- Java Maven manifests: root `pom.xml`, plus `contract-core`, `contract-cli`, `contract-service`, `contract-validation-spring-boot-starter`, `contract-sdk`, `contract-build-support`, `contract-maven-plugin`, `contract-gradle-plugin`, `examples/dcg-spring-boot-realworld-demo`, and `examples/spring-boot-realworld-demo` `pom.xml` files. Existing `target/` build JARs: `contract-cli-4.0.0-alpha.1-all.jar`, `contract-cli-4.0.0-alpha.1.jar`, `contract-service-4.0.0-alpha.1.jar`, `contract-core-4.0.0-alpha.1.jar`, `contract-build-support-4.0.0-alpha.1.jar`, `contract-validation-spring-boot-starter-4.0.0-alpha.1.jar`, `contract-sdk-4.0.0-alpha.1.jar`, `contract-maven-plugin-4.0.0-alpha.1.jar`, `contract-gradle-plugin-4.0.0-alpha.1.jar`, and both example-module `4.0.0-alpha.1.jar` files. They are module build outputs, not final RC archives. The CLI all-JAR SHA-256 is `6c85af83f28db86008d8f5036b7f8fddaac656279199fa32c6f92070b1789371`; the service JAR SHA-256 is `43ebd7050af9404836faf72b977a203c2880e1452e89f0ce7666c3529db8de6a`.
- Compose source bundle (not a separately packaged archive): Java-root `docker-compose.yml`, `docker-compose.sqlite.yml`, `docker-compose.mysql.yml`, `docker-compose.shadow-inference.yml`; `docker/contract-service.Dockerfile`; environment templates under `config/`, including `compose.mysql-demo.env.example`. No Compose ZIP/tar bundle was found in the searched Downloads/Desktop locations.
- Local container **images**, not exported RC artifacts: `dcg/contract-service:local` image ID `sha256:e2994b3645e47b67b5f320717634f6eeb3adf5d65abd8f0d21431e0b376e5579` and `dcg/dcgaimodel:local` image ID `sha256:c9541de86f49adcc06767e02edd285259c2b2a8a5b06c0dd804fc90eb84e7533` (both created 2026-09-07). Neither has an RC tag/provenance claim.
- Existing SBOMs: `sbom.cdx.json` is inside every listed tarball; copies/build inputs exist under `D/inputs-*`, `D/evidence-*`, and extracted-package directories under Downloads/Desktop. The six distinct embedded SBOMs and their exact hashes/counts are below.
- Existing Trivy JSON reports: `D/final-trivy-{macos-arm64,linux-x64}.json`, `D/final-pins-trivy-{macos-arm64,linux-x64}.json`, `D/orphan-fix*-trivy-linux-x64.json`, `D/relative-orphan-fix*-trivy-{macos-arm64,linux-x64}.json`, and `D/release-candidate-sbom-scan-{macos-arm64,linux-x64}.json`. The last two SBOM scans detected Cargo and Java dependencies with zero reported vulnerabilities; they cover the **4.0.0-alpha.1** candidates, not this RC. Earlier extracted-filesystem scans discovered zero language-specific files, so their zero findings are not evidence of dependency coverage.

All relative Java paths above use the verified Java root. No `v0.1.0-rc.1` binary, JAR, container image, Compose bundle, SBOM, or Trivy report was identified.

## Existing archive inventory and checksums

Path abbreviations are exact prefixes: `S` = `/Users/siddarthkanamadi/Desktop/dcg-local-prerelease-4.0.0-alpha.1`; `D` = `/Users/siddarthkanamadi/Downloads/dcg-final-release-20260915`; `M` = `/Users/siddarthkanamadi/Downloads/dcg-mac-mini-candidate-ew4mOR`; `T` = `/Users/siddarthkanamadi/Downloads/dcg-step5-R6Cy1t`. Every row below was opened as a tarball, had its SHA-256 calculated, and contained **24 regular files**. Every row is a historical alpha build/candidate output, **not** a final `v0.1.0-rc.1` archive. `O` means older dependency set (Logback 1.5.32); `N` means newer set (Logback 1.5.34). The exact target is encoded in the archive filename and confirmed in its `build-info.json`.

| Archive (prefixes above) | SHA-256 | Set | Status for v0.1.0-rc.1 |
|---|---|---|---|
| `S/linux-arm64/dcg-4.0.0-alpha.1-linux-arm64.tar.gz` | `e89204fb7c72204c4539f6f9f3aaa68b270d1199803e166f1c354bdcfd956c22` | O | Build output; not final |
| `S/linux-x64/dcg-4.0.0-alpha.1-linux-x64.tar.gz` | `3580ec599071df9601292fc8b9f995cd8ea395608077215e84adbcac925374e9` | O | Build output; not final |
| `S/macos-arm64/dcg-4.0.0-alpha.1-macos-arm64.tar.gz` | `75bbc6fe9386cb74ca87da3792eae483de6bd77a688eebeaef5b8080a995fa82` | O | Build output; not final |
| `S/macos-x64/dcg-4.0.0-alpha.1-macos-x64.tar.gz` | `fd9345e4fd1c95f155d0590c2b5229ed8e7d5a6f94ad59ebefbc81239d1c4f57` | O | Build output; not final |
| `S/step7-candidates/linux-arm64/dcg-4.0.0-alpha.1-linux-arm64.tar.gz` | `3a5d8239426c446e4a46a3017cb3afe4c38ee89f439c406769f8f12459e6227a` | O | Candidate output; not final |
| `S/step7-candidates/linux-x64/dcg-4.0.0-alpha.1-linux-x64.tar.gz` | `1027b1a4365b480052d9767c53121fe5aac0bc51d57ee25312867275406e8b56` | O | Candidate output; not final |
| `S/step7-candidates/macos-arm64/dcg-4.0.0-alpha.1-macos-arm64.tar.gz` | `7efb51fd31cf4aaabfb0466567c66bef63e05042afb79c6e95528b6159444e2c` | O | Candidate output; not final |
| `S/step7-candidates/macos-x64/dcg-4.0.0-alpha.1-macos-x64.tar.gz` | `8c819c5e1110c4f71d78b35967f9943b943bd2158205fcb66c39a03b9f2d6356` | O | Candidate output; not final |
| `D/archives/linux-x64/dcg-4.0.0-alpha.1-linux-x64.tar.gz` | `9995e4dc0eaf2fde1d924898cc47b1b05ac59f08fa4bb97ff5a723ac5ce45df6` | N | Build output; not final |
| `D/archives/macos-arm64/dcg-4.0.0-alpha.1-macos-arm64.tar.gz` | `c31250e371c6b9bb27ef619b846ee40b3d9ba93ce0fd59197310c3cca0eb30a0` | N | Build output; not final |
| `D/archives-orphan-fix/linux-x64/dcg-4.0.0-alpha.1-linux-x64.tar.gz` | `798e8119b0eebd5fbfc2077c5fc6d691e11d9de1849fb01a6dab7eb9bbc189ba` | N | Superseded candidate; not final |
| `D/archives-orphan-fix/macos-arm64/dcg-4.0.0-alpha.1-macos-arm64.tar.gz` | `56372d11b63a423ccb8caadcac375911744a27571636360f45e40dda4c261619` | N | Superseded candidate; not final |
| `D/archives-orphan-fix-final/linux-x64/dcg-4.0.0-alpha.1-linux-x64.tar.gz` | `4a935b4d69140c632aeb965dfff5e05ddfa803b3eaabe83b2f8bfd2c84c9d68b` | N | Superseded candidate; not final |
| `D/archives-orphan-fix-final/macos-arm64/dcg-4.0.0-alpha.1-macos-arm64.tar.gz` | `e484257de452c77f7dc1655bbfec85351825748393e497e1087f2d6f0d58cb6f` | N | Superseded candidate; not final |
| `D/archives-pins-manifest/linux-x64/dcg-4.0.0-alpha.1-linux-x64.tar.gz` | `e6f51e7514537c16e013791d8ced2d25efb886f3e54aa47f3da158ae3f23acd2` | N | Superseded candidate; not final |
| `D/archives-pins-manifest/macos-arm64/dcg-4.0.0-alpha.1-macos-arm64.tar.gz` | `f6c22a13edc638e2901f68b1d53fed9cc4cfa1c40406480ec198eea58a4af63c` | N | Superseded candidate; not final |
| `D/archives-pins-manifest-final/linux-x64/dcg-4.0.0-alpha.1-linux-x64.tar.gz` | `5712d8d5101f070d33badcb1bc3e3235bc7be12247f3888b42edab70bc4d76da` | N | Superseded candidate; not final |
| `D/archives-pins-manifest-final/macos-arm64/dcg-4.0.0-alpha.1-macos-arm64.tar.gz` | `a482b84b481d4f4c4e29a1b9639e1a730850620d95f0224e5f21769d0ae2582e` | N | Superseded candidate; not final |
| `D/archives-relative-orphan-fix/linux-x64/dcg-4.0.0-alpha.1-linux-x64.tar.gz` | `f612f127381497d96fcfaac4c5eccc1f5162686609ec446eae1519c4a28c816f` | N | Superseded candidate; not final |
| `D/archives-relative-orphan-fix/macos-arm64/dcg-4.0.0-alpha.1-macos-arm64.tar.gz` | `7e1dbbbaa2f3189c0eeb7cb05f4999d4ecf043f124914b56ac6f5819d91f9e28` | N | Superseded candidate; not final |
| `D/archives-relative-orphan-fix-final/linux-x64/dcg-4.0.0-alpha.1-linux-x64.tar.gz` | `549ef69845704f36b72157a4ceb017185335e9d9df240171c07419f739787616` | N | Superseded candidate; not final |
| `D/archives-relative-orphan-fix-final/macos-arm64/dcg-4.0.0-alpha.1-macos-arm64.tar.gz` | `3e3a36b8ede1a1873cfbc7fe10c24b120aef7284c11dd9f1586b8e03f749ec00` | N | Superseded candidate; not final |
| `D/archives-relative-orphan-fix-release-candidate/linux-x64/dcg-4.0.0-alpha.1-linux-x64.tar.gz` | `7743d4da4a87ef31d50db6c69064f69c62f9974a2db7eb9e9c16025b1ee0a14f` | N | Latest alpha candidate; **not RC final** |
| `D/archives-relative-orphan-fix-release-candidate/macos-arm64/dcg-4.0.0-alpha.1-macos-arm64.tar.gz` | `a1e0fb57085f7d67e121dd54f946de0e4fc678738636bf0a54fb6672a306cc1e` | N | Latest alpha candidate; **not RC final** |
| `M/dcg-4.0.0-alpha.1-macos-arm64.tar.gz` | `7efb51fd31cf4aaabfb0466567c66bef63e05042afb79c6e95528b6159444e2c` | O | Duplicate older candidate; not final |
| `T/initial-assembly-macos-arm64/dcg-4.0.0-alpha.1-macos-arm64.tar.gz` | `c5db2c31fdeacd40daae0d442f8f576ba254ceb1bad3322c0927593d77dea32f` | O | Initial build output; not final |

### Contents and exact dependency evidence for each row

All **26** archives have the same relative 24-file list below (the top directory is `dcg-4.0.0-alpha.1-<platform>/` in each archive). Thus the list applies individually to every inventory row; it is not a proposed RC manifest:

```text
LICENSE
README.md
RELEASE-NOTES.md
SHA256SUMS
THIRD-PARTY-NOTICES.txt
bin/dcg
bin/dcgaimodel
bin/start
bin/status
bin/stop
build-info.json
config/application-local-demo.properties.example
contracts/orders.created/metadata.yaml
contracts/orders.created/v1.json
contracts/orders.created/v2.json
contracts/policy-packs.json
lib/contract-cli-4.0.0-alpha.1-all.jar
lib/contract-service-4.0.0-alpha.1.jar
licenses/dcgaimodel-LICENSE
model/data/experiments/v9-multiclass-cpu-100e/models/seed-20260826-normal-family-split.json
model/data/experiments/v9-multiclass-cpu-100e/models/seed-20260827-normal-family-split.json
model/data/experiments/v9-multiclass-cpu-100e/models/seed-20260828-normal-family-split.json
model/data/inference/frozen-v9/policy-packs-v5-compositional.json
sbom.cdx.json
```

The **full exact packaged dependency names and versions** for each row are in that row's embedded `sbom.cdx.json`, with notices in `THIRD-PARTY-NOTICES.txt`; the SBOM SHA-256 below selects the complete inventory unambiguously. These are dependency declarations/inventories, not proof of legal clearance. All O archives record Java build `dac3ed509d03e1bef75c47b497ca80bbdd1f2e04`, Rust `ef819fe58865c643a240ee067cf61d506f04a778`, MySQL Connector/J **9.7.0**, Flyway MySQL **11.7.2**, Logback classic/core **1.5.32**, and Jakarta Annotation API **2.1.1**. All N archives record Java build `d54a518c3b308d1c54a440f016d81086e0a73155`, Rust `32ca579095ed5b91749b8c33999556624e58758f`, the same MySQL/Flyway/Jakarta versions, and Logback classic/core **1.5.34**. `build-info.json` in every row says `4.0.0-alpha.1`, not `0.1.0-rc.1`.

| Archive set and platform | Embedded SBOM components | Embedded SBOM SHA-256 | Dependency versions |
|---|---:|---|---|
| O, linux-arm64 | 307 | `e934f5b0cbca641773ab113e930a132cb57b759b682305543156fb8dce231b6e` | Exact list in embedded SBOM; flagged versions above |
| O, linux-x64 | 307 | `eb54afa2d2217b932d73760bde38e6bf247225321064189125dd571a0947887d` | Same |
| O, macos-arm64 | 308 | `6ca1fee540a4c1cb62b1b971979ac057b4107a920fa47b7c7e274d367ce36cba` | Same |
| O, macos-x64 | 308 | `f501af468f8f0d6ed4c16d50e85cf683c13b89e7def6b13ab6ea83f524a48f9b` | Same |
| N, linux-x64 | 301 | `b53dfb3217f7ad9056053fe88af34ed1db2cfc06f589ac914a02e21dc1ef525b` | Exact list in embedded SBOM; flagged versions above |
| N, macos-arm64 | 302 | `9ae70ba71ab40401b8c3ed27641bd26ff9b123aa5670c302e903ffdd75c1fe6e` | Same |

## License-review component inventory

The **three previously flagged review subjects** are (1) MySQL Connector/J, (2) Logback, and (3) Jakarta Annotation API. Logback is two separate JARs. Flyway MySQL is additionally MySQL-related and must not be overlooked. Here `O`/`N` and all archive paths refer only to the alpha archives above, never to a missing RC archive.

| Component | Exact version | Project | Archive | Evidence source | MySQL-related? | Status |
|---|---|---|---|---|---|---|
| `com.mysql:mysql-connector-j` | 9.7.0 | Java CLI + service | Every O/N alpha archive; CLI classes shaded, service `BOOT-INF/lib/mysql-connector-j-9.7.0.jar` | `contract-cli/pom.xml`, `contract-service/pom.xml`; embedded SBOM and notices; JAR listings | Yes | Present in alpha; RC absent; human license review pending |
| `org.flywaydb:flyway-mysql` | 11.7.2 | Java CLI + service | Every O/N alpha archive; service `BOOT-INF/lib/flyway-mysql-11.7.2.jar` | Both POMs; embedded SBOM; service JAR listing | Yes | Present in alpha; RC absent; review pending |
| `ch.qos.logback:logback-classic` | O: 1.5.32; N: 1.5.34 | Java service | Every O/N alpha archive; service nested JAR | Embedded SBOM; service JAR listing; root POM override for N | No | Present in alpha; RC absent; review pending |
| `ch.qos.logback:logback-core` | O: 1.5.32; N: 1.5.34 | Java service | Every O/N alpha archive; service nested JAR | Embedded SBOM; service JAR listing; root POM override for N | No | Present in alpha; RC absent; review pending |
| `jakarta.annotation:jakarta.annotation-api` | 2.1.1 | Java service | Every O/N alpha archive; service nested JAR | Embedded SBOM; service JAR listing | No | Present in alpha; RC absent; review pending |
| `mysql` container image | `8.4` tag; digest not established | Compose-only database service | **Not** inside any tarball | `docker-compose.mysql.yml` line 3 | Yes | Referenced by Compose source; no RC image/bundle evidence |

## MySQL dependency details and release conclusion

The Java CLI and service POMs directly declare both `mysql-connector-j` and `flyway-mysql`. Their versions are resolved as 9.7.0 and 11.7.2 in each alpha SBOM. The alpha service JAR physically contains the corresponding nested JARs; the CLI all-JAR contains MySQL classes. `docker-compose.mysql.yml` separately references `mysql:8.4` and a MySQL JDBC URL; MySQL migration resources are in the CLI/service output. The Rust `Cargo.toml` and `Cargo.lock` show no MySQL-named dependency. A container tag is not an immutable digest, and the Compose image is not part of the tar archives.

**Exact archives found:** 26 local `dcg-4.0.0-alpha.1-*.tar.gz` outputs, each enumerated with a complete SHA-256 above. **No final `v0.1.0-rc.1` archive exists in the searched repository, Downloads, or Desktop locations.**

**Exact three components identified:** MySQL Connector/J 9.7.0; Logback classic/core 1.5.32 (O) or 1.5.34 (N); Jakarta Annotation API 2.1.1. These are alpha-archive observations, not RC components.

**Missing evidence:** a reconciled RC version policy (Java currently 4.0.0-alpha.1; Rust 0.1.0), RC-specific final archives and checksums, immutable container/Compose-bundle evidence if containers are in scope, RC SBOMs and vulnerability scans, exact-asset acceptance, and documented human redistribution approval. Existing alpha scans/acceptance cannot be relabeled as RC evidence.

**Recommended next action:** decide whether `v0.1.0-rc.1` is genuinely the intended release instead of the current `4.0.0-alpha.1`. If yes, align the manifests/release scope, then separately build and verify **new exact-version final archives** and repeat dependency/license and platform acceptance review. Do not select a historical alpha tarball by its convenient filename or promote a target-directory binary as a final archive.

Technical review status: BLOCKED  
Human legal approval: PENDING  
Tagging and publication: NOT AUTHORIZED
