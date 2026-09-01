# Policy Packs (How Compatibility Policies Work)

This document explains how policy packs control compatibility outcomes in `data-contract-governance`, how they are resolved, and how to iterate safely.

The runnable compatibility engine (`RuleId`, `DefaultDiffEngine`, `DefaultRuleEngine`, and
`PolicyPackDefaults` in `contract-core`) is the authoritative definition of supported rules.
Keep this document aligned with that code whenever rules change.

**Overview**
Policy packs decide whether each compatibility rule is treated as `BREAKING`, `WARNING`, or `IGNORE`. A check run:

- Fails if there is at least one `BREAKING` change
- Passes with warnings if there are no breaking changes and at least one `WARNING`
- Passes cleanly if everything is `IGNORE`

Policy packs are applied on top of the **baseline rules**, then overridden by any rules you specify.

**Where Policy Packs Live**
Default location:

`/Users/siddarthkanamadi/Personal_Projects/dcg/data-contract-governance/contracts/policy-packs.json`

You can override the location by setting:

`contracts.policy-packs=/absolute/path/to/policy-packs.json`

**Policy Pack Config Format**
`policy-packs.json` has a `defaultPack` and a `packs` map:

```json
{
  "defaultPack": "baseline",
  "packs": {
    "baseline": {
      "description": "Baseline compatibility rules",
      "rules": {
        "FIELD_REMOVED": "BREAKING",
        "FORWARD_OPTIONAL_FIELD_ADDED": "IGNORE",
        "FIELD_TYPE_CHANGED": "BREAKING",
        "REQUIRED_FIELD_ADDED": "BREAKING",
        "ENUM_VALUE_REMOVED": "BREAKING",
        "ENUM_VALUE_ADDED": "WARNING",
        "CONSTRAINT_TIGHTENED": "BREAKING",
        "CONDITIONAL_RESTRICTION_ADDED": "BREAKING",
        "SCHEMA_RESTRICTION_ADDED": "BREAKING"
      }
    },
    "strict": {
      "description": "Treat enum additions as breaking",
      "rules": {
        "ENUM_VALUE_ADDED": "BREAKING"
      }
    },
    "relaxed": {
      "description": "Ignore enum additions",
      "rules": {
        "ENUM_VALUE_ADDED": "IGNORE"
      }
    }
  }
}
```

The complete baseline is shown above for clarity. A policy pack may omit any rule it does not
override: omitted rules inherit the baseline implementation in `PolicyPackDefaults`.

**Rule IDs and Severities**
Rule IDs are defined in:

`/Users/siddarthkanamadi/Personal_Projects/dcg/data-contract-governance/contract-core/src/main/java/com/ideas/contracts/core/RuleId.java`

Valid rule IDs:

- `FIELD_REMOVED`
- `FORWARD_OPTIONAL_FIELD_ADDED`
- `FIELD_TYPE_CHANGED`
- `REQUIRED_FIELD_ADDED`
- `ENUM_VALUE_REMOVED`
- `ENUM_VALUE_ADDED`
- `CONSTRAINT_TIGHTENED`
- `CONDITIONAL_RESTRICTION_ADDED`
- `SCHEMA_RESTRICTION_ADDED`

Rule meanings:

- `FIELD_REMOVED` — a field is removed, including a recursively discovered nested field.
- `FIELD_TYPE_CHANGED` — a field type or nullability changes.
- `REQUIRED_FIELD_ADDED` — an optional field becomes required, or a new required field is added.
- `ENUM_VALUE_REMOVED` / `ENUM_VALUE_ADDED` — an enum member is removed or added.
- `CONSTRAINT_TIGHTENED` — a supported validation constraint becomes stricter, for example a
  bound, length, pattern, `format`, `const`, `multipleOf`, `uniqueItems`, or
  `additionalProperties` restriction.
- `CONDITIONAL_RESTRICTION_ADDED` — a conditional restriction is added or changed.
- `SCHEMA_RESTRICTION_ADDED` — a schema-level restriction is added or changed.

The loader evaluates nested properties, array items, local references, and supported `oneOf`
branches recursively. It reports the engine's concrete rule IDs rather than inventing separate
labels such as `ARRAY_ITEM_CHANGED` or `NULLABILITY_CHANGED`.

Valid severities:

- `BREAKING`
- `WARNING`
- `IGNORE`

Notes:

- Rule IDs are case-insensitive and `-` is normalized to `_`.
- Unknown rule IDs throw an error at startup.
- If a rule is omitted, the baseline rule is used.
- If a rule severity is `null`, it defaults to `BREAKING`.

`FORWARD_OPTIONAL_FIELD_ADDED` applies only when the old consumer object has
`additionalProperties: true` or omits the keyword. Its baseline severity is
`IGNORE`; a policy pack may promote it to `WARNING` or `BREAKING`. An old
consumer object with `additionalProperties: false` is always breaking, and a
schema-valued `additionalProperties` remains conservatively breaking. This
does not alter the ordinary `FIELD_REMOVED` rule. Genuine base-to-candidate
removals in either BACKWARD or FORWARD mode use `FIELD_REMOVED` and therefore
honor that rule's configured severity independently of the optional-addition
path.

A genuinely new required field uses `REQUIRED_FIELD_ADDED` in FORWARD as well
as BACKWARD evaluation; it is not inferred from a reversed removal. `FULL`
retains both directionally attributed findings when the same change is
material in both checks, so its text output contains the BACKWARD finding and
the corresponding `[FORWARD]` finding by design.

**How a Policy Pack Is Chosen**
For each contract:

1. The service reads `policyPack` from the contract’s `metadata.yaml`.
2. The name is normalized to lowercase.
3. If the pack doesn’t exist, the `defaultPack` is used.
4. If `defaultPack` is missing, it falls back to `baseline`.

Location of metadata:

`/Users/siddarthkanamadi/Personal_Projects/dcg/data-contract-governance/contracts/<contractId>/metadata.yaml`

Example:

```yaml
ownerTeam: platform
domain: commerce
compatibilityMode: BACKWARD
policyPack: strict
```

**Compatibility Modes vs Policies**
Compatibility modes control *direction* of checks:

- `BACKWARD`
- `FORWARD`
- `FULL`

Policy packs control *severity* of each rule. Both are applied together.

**How the Service Uses Policy Packs**
The service resolves policy packs via:

- `PolicyPackRegistry` in `contract-service`
- `PolicyPackConfig` in `contract-core`

**How the CLI Uses Policy Packs**
The CLI loads the same `policy-packs.json` and reads `policyPack` from the contract’s `metadata.yaml`, then applies the same rules as the service.

**Safe Iteration Workflow**
Use this when you change `policy-packs.json`:

1. Update the policy pack rules.
2. If needed, update `metadata.yaml` to point to the pack.
3. Run policy unit tests.
4. Run a manual CLI check on a known contract.

Policy unit test commands:

```bash
./mvnw -pl contract-core -Dtest=DefaultContractEngineCompatibilityTest test
./mvnw -pl contract-service -Dtest=ContractCatalogPolicyPackTest test
```

Manual CLI build command:

```bash
./mvnw -pl contract-cli -am package -DskipTests
```

Manual CLI check (uses contract metadata + policy pack):

```bash
java -jar /Users/siddarthkanamadi/Personal_Projects/dcg/data-contract-governance/contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar \
  check-compat \
  --base /Users/siddarthkanamadi/Personal_Projects/dcg/data-contract-governance/contracts/orders.created/v1.json \
  --candidate /Users/siddarthkanamadi/Personal_Projects/dcg/data-contract-governance/contracts/orders.created/v2.json \
  --mode BACKWARD
```

Expected behavior with `orders.created`:

- `baseline`: PASS + warning for enum addition
- `strict`: FAIL for enum addition
- `relaxed`: PASS without warning for enum addition

**Troubleshooting**
If a policy change doesn’t appear:

- Confirm the contract’s `metadata.yaml` points to the pack.
- Confirm the pack name exists in `policy-packs.json`.
- Confirm the service or CLI is reading the correct `contracts` root.
- Check for startup errors: unknown rule IDs will fail fast.
