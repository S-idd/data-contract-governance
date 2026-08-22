# User Manual

## Installation

### Prerequisites

Install:

- Java 21 or later
- Maven 3.9 or later
- Git

Verify the installation:

```bash
java -version
mvn -version
git --version
```

### Get the project and build the CLI

```bash
git clone <repository-url>
cd data-contract-governance
mvn test
mvn -pl contract-cli -am package
```

The CLI executable is created at:

```text
contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar
```

Confirm that it runs:

```bash
java -jar contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar --help
```

## Write Your First Contract

A contract is a folder under `contracts/`. Each folder contains:

- `metadata.yaml`: ownership and compatibility settings.
- `v1.json`: the first JSON Schema version.
- Later versions such as `v2.json` and `v3.json`.

Create this structure:

```text
contracts/
  orders.created/
    metadata.yaml
    v1.json
```

Create `contracts/orders.created/metadata.yaml`:

```yaml
ownerTeam: platform
domain: commerce
compatibilityMode: BACKWARD
policyPack: baseline
```

`compatibilityMode` controls what the validation protects:

- `BACKWARD`: new versions must accept payloads valid under the previous version.
- `FORWARD`: previous versions must tolerate the new version's payload semantics.
- `FULL`: both backward and forward compatibility must pass.

Create `contracts/orders.created/v1.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "orderId": {
      "type": "string"
    },
    "status": {
      "type": "string",
      "enum": ["CREATED", "PAID"]
    },
    "amount": {
      "type": "number"
    }
  },
  "required": ["orderId", "status"]
}
```

To publish a new version, add the next sequential file name. For example, create `v2.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "orderId": {
      "type": "string"
    },
    "status": {
      "type": "string",
      "enum": ["CREATED", "PAID", "SHIPPED"]
    },
    "amount": {
      "type": "number"
    },
    "currency": {
      "type": "string"
    }
  },
  "required": ["orderId", "status"]
}
```

This example adds an optional field, `currency`, and an enum value, `SHIPPED`. Under the baseline policy, it passes; the enum addition is reported as a warning.

## Run Validation

Set a convenient variable for the CLI command:

```bash
DCG="java -jar contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar"
```

### Check the contract structure

Run lint after creating or editing a contract:

```bash
$DCG lint --path contracts/orders.created
```

Expected result:

```text
Lint passed: contracts/orders.created
```

Lint verifies the folder structure, metadata, version filenames, JSON syntax, and schema validity.

### See what changed

Compare two schema versions:

```bash
$DCG diff \
  --base contracts/orders.created/v1.json \
  --candidate contracts/orders.created/v2.json
```

Example output:

```text
Schema diff:
+ field added: currency
~ enum value added: status.SHIPPED
```

### Check compatibility

Run the compatibility check before opening a pull request:

```bash
$DCG check-compat \
  --base contracts/orders.created/v1.json \
  --candidate contracts/orders.created/v2.json \
  --mode BACKWARD
```

Example output:

```text
Schema compatibility: PASS
Warnings: [Enum value added: status.SHIPPED]
```

A passing result returns exit code `0`. A compatibility failure returns exit code `1`, which causes CI to fail.

### Record a validation result locally

To retain a local history of the check result:

```bash
$DCG check-compat \
  --base contracts/orders.created/v1.json \
  --candidate contracts/orders.created/v2.json \
  --mode BACKWARD \
  --record-db checks.db \
  --contract-id orders.created \
  --commit-sha local-dev
```

## Common Errors and Fixes

### “Lint failed” because `metadata.yaml` is missing or incomplete

Ensure every contract has a `metadata.yaml` file with these fields:

```yaml
ownerTeam: platform
domain: commerce
compatibilityMode: BACKWARD
```

`policyPack` is optional. Valid compatibility modes are `BACKWARD`, `FORWARD`, and `FULL`.

### “Invalid version format” or a version is ignored

Use sequential filenames in this format:

```text
v1.json
v2.json
v3.json
```

Do not use names such as `version1.json`, `v02.json`, `latest.json`, or `v1-final.json`.

### The first version is rejected

The first schema version must be named `v1.json`. New versions must be added one at a time: `v2.json` after `v1.json`, then `v3.json`.

### “Schema compatibility: FAIL”

Read the reported breaking changes. Common breaking changes are:

- Removing an existing field.
- Changing a field type, such as `string` to `number`.
- Making a new field required.
- Removing an allowed enum value.

Typical fixes are:

- Keep the old field and mark it deprecated instead of removing it.
- Add a new field instead of changing the existing field type.
- Make newly added fields optional.
- Keep existing enum values supported.

### A new enum value is reported as a warning

The baseline policy treats an added enum value as a warning. This means the schema remains compatible, but downstream consumers may need to handle the new value.

If your consumers cannot safely ignore unknown enum values, use a stricter policy pack that treats enum additions as breaking.

### “Schema file does not exist”

Check that the paths in the command point to actual files and that both schemas are in the contract directory:

```bash
$DCG check-compat \
  --base contracts/orders.created/v1.json \
  --candidate contracts/orders.created/v2.json \
  --mode BACKWARD
```

### “Unable to read metadata.yaml for policy pack”

Ensure `metadata.yaml` is valid YAML. Indentation must use spaces, not tabs. If `policyPack` is specified, make sure its name exists in `contracts/policy-packs.json`.

### CI does not run a contract check

The CI workflow checks contracts only when files under `contracts/` changed. Confirm that the contract files are committed and that the change is inside a contract directory.

For a changed contract with two or more versions, CI runs lint and compares the two most recent versions.

### “CLI jar not found”

Build the CLI again from the repository root:

```bash
mvn -pl contract-cli -am package
```

Then rerun the command using:

```text
contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar
```
