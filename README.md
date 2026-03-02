# Data Contract Governance (V1)

Open-source Java/Spring Boot tooling to prevent breaking schema changes before merge/deploy.

## Prerequisites
- Java 21
- Maven 3.9+

## Build
```bash
cd D:\ideas
mvn test
```

## Build CLI Fat Jar
```bash
cd D:\ideas
mvn -pl contract-cli -am package
```

## CLI Usage

Help:
```bash
java -jar contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar --help
```

Lint sample contract:
```bash
java -jar contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar lint --path contracts/orders.created
```

Diff sample versions:
```bash
java -jar contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar diff --base contracts/orders.created/v1.json --candidate contracts/orders.created/v2.json
```

Check compatibility:
```bash
java -jar contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar check-compat --base contracts/orders.created/v1.json --candidate contracts/orders.created/v2.json --mode BACKWARD
```

Record compatibility result to SQLite:
```bash
java -jar contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar check-compat --base contracts/orders.created/v1.json --candidate contracts/orders.created/v2.json --mode BACKWARD --record-db checks.db --contract-id orders.created --commit-sha local-dev
```

Record compatibility result to PostgreSQL:
```bash
java -jar contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar check-compat --base contracts/orders.created/v1.json --candidate contracts/orders.created/v2.json --mode BACKWARD --record-jdbc-url "jdbc:postgresql://localhost:5432/contracts" --record-db-user contracts_user --record-db-password change-me --contract-id orders.created --commit-sha local-dev
```

## CI Contract Checks (Changed Contracts Only)
GitHub Actions runs full tests and then checks only changed contract directories.

Local dry-run of the same changed-contract check:
```bash
BASE_SHA=<older_commit_sha> HEAD_SHA=<newer_commit_sha> bash scripts/ci/check-changed-contracts.sh
```

## Contract Service API (Read-Only)
Run service:
```bash
cd D:\ideas\contract-service
mvn spring-boot:run
```

Run service with PostgreSQL check-store:
```bash
cd D:\ideas\contract-service
SPRING_APPLICATION_JSON='{"checks":{"db":{"url":"jdbc:postgresql://localhost:5432/contracts","username":"contracts_user","password":"change-me"}}}' mvn spring-boot:run
```

Endpoints:
```bash
curl http://localhost:8080/contracts
curl http://localhost:8080/contracts/orders.created
curl http://localhost:8080/contracts/orders.created/versions
curl http://localhost:8080/contracts/orders.created/versions/v1
curl http://localhost:8080/checks
curl "http://localhost:8080/checks?contractId=orders.created"
```

OpenAPI / Swagger UI:
```bash
http://localhost:8080/swagger-ui/index.html
http://localhost:8080/v3/api-docs
```

## One-Command Demo (Windows PowerShell)
```powershell
cd D:\ideas
.\scripts\demo\make-demo.ps1
```
This script:
- builds CLI fat jar
- records one compatibility check in SQLite
- starts `contract-service`
- prints Swagger URL and sample API outputs
- stops service automatically

## Sample Contracts
- [orders.created metadata](contracts/orders.created/metadata.yaml)
- [orders.created v1](contracts/orders.created/v1.json)
- [orders.created v2](contracts/orders.created/v2.json)

## Project Docs
- [Requirements](docs/Requirements.md)
- [System Design](docs/SystemDesign.md)
- [Architecture Decisions](adr/ArchitectureDecisionRecord.md)
