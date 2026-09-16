# CLI Walkthrough

This walkthrough captures real CLI output for the sample contracts under `contracts/orders.created`.

Screen recording:

- `docs/screenshots/data-contracts-governance/cli-walkthrough.mp4`

## 0) Build the CLI jar

```bash
cd /path/to/data-contract-governance
./mvnw -pl contract-cli -am package -DskipTests
```

## 1) Help

Command:

```bash
java -jar contract-cli/target/contract-cli-4.0.0-rc.1-all.jar --help
```

Output:

```
Usage: contract [-hV] [COMMAND]
Data contract governance CLI
  -h, --help      Show this help message and exit.
  -V, --version   Print version information and exit.
Commands:
  lint          Lint contract directory
  diff          Show semantic schema diff
  check-compat  Check schema compatibility
```

## 2) Lint a contract directory

Command:

```bash
java -jar contract-cli/target/contract-cli-4.0.0-rc.1-all.jar lint --path contracts/orders.created
```

Output:

```
Lint passed: contracts/orders.created
```

## 3) Diff two versions

Command:

```bash
java -jar contract-cli/target/contract-cli-4.0.0-rc.1-all.jar diff \
  --base contracts/orders.created/v1.json \
  --candidate contracts/orders.created/v2.json
```

Output:

```
Schema diff:
+ field added: currency
~ enum value added: status.SHIPPED
```

## 4) Check compatibility

Command:

```bash
java -jar contract-cli/target/contract-cli-4.0.0-rc.1-all.jar check-compat \
  --base contracts/orders.created/v1.json \
  --candidate contracts/orders.created/v2.json \
  --mode BACKWARD
```

Output:

```
Schema compatibility: PASS
Warnings: [Enum value added: status.SHIPPED]
```
