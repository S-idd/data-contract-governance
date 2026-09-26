# Evaluator manual

Run every command from the extracted bundle root.

## 1. Verify and create a clean workspace

```bash
./scripts/verify-install.sh
./scripts/init-workspace.sh
```

Write contracts under `workspace/contracts/<contract-id>/`. Each directory needs:

```text
metadata.yaml
v1.json
candidate.json
```

Use `examples/contracts/orders.created/` only as a reference. Copy it when a quick smoke test is useful:

```bash
cp -R examples/contracts/orders.created workspace/contracts/
```

## 2. Deterministic CLI check

```bash
./scripts/check-contract.sh orders.created BACKWARD sqlite
```

Exit `0` and `Schema compatibility: PASS` mean compatible. Exit `1` and `FAIL` mean DCG correctly blocked a breaking proposal. Exit `2` means invalid input, configuration, or unavailable persistence.

The comparison direction matters: the first schema is the installed base and the second is the proposed candidate. Reversing them asks a different compatibility question.

## Policy packs

The clean workspace includes the verified `baseline`, `strict`, and `relaxed` packs. The contract selects one in `metadata.yaml`:

```yaml
ownerTeam: platform
domain: commerce
compatibilityMode: BACKWARD
policyPack: baseline
```

For the included example, `baseline` passes an enum addition with a warning, `strict` rejects it, and `relaxed` passes without that warning. Demonstrate this only in the writable copy:

```bash
sed -i 's/policyPack: baseline/policyPack: strict/' workspace/contracts/orders.created/metadata.yaml
./scripts/check-contract.sh orders.created BACKWARD sqlite
sed -i 's/policyPack: strict/policyPack: baseline/' workspace/contracts/orders.created/metadata.yaml
```

The expected strict result is exit `1`; that is a successful demonstration of enforcement.

## 3. DCG service without AI

```bash
DCG_AI_ENABLED=false ./scripts/start-dcg.sh
./scripts/status-dcg.sh
```

Open `http://127.0.0.1:8080/ui`. The username is `demo`; the generated password is stored locally in `workspace/password`.

Stop it with:

```bash
./scripts/stop-dcg.sh
```

## 4. DCG service with AI advisory

```bash
./scripts/stop-dcg.sh
DCG_AI_ENABLED=true ./scripts/start-dcg.sh
./scripts/status-dcg.sh
```

AI labels and scores are advisory only. Deterministic DCG makes the final PASS/FAIL decision. If the model is unavailable, deterministic enforcement remains active.

## 5. IEMS with SQLite

Keep DCG running in deterministic or AI-advisory mode, then run:

```bash
./scripts/start-iems.sh sqlite
curl --fail --silent http://127.0.0.1:8090/actuator/health | jq .
./scripts/run-iems-postman.sh
./scripts/stop-iems.sh
```

The Newman script pins Newman 6.2.2, injects the generated local admin password into a private copied environment, and writes results below `workspace/evidence/`. Do not publish that private environment file.

## 6. PostgreSQL and MySQL

```bash
./scripts/database-up.sh
./scripts/check-contract.sh orders.created BACKWARD postgres
./scripts/check-contract.sh orders.created BACKWARD mysql
./scripts/start-iems.sh postgres
./scripts/stop-iems.sh
./scripts/start-iems.sh mysql
./scripts/stop-iems.sh
./scripts/database-down.sh
```

The databases use generated local credentials and separate `dcg_history` and `iems_app` databases. Every `start-iems.sh` invocation first lints and checks all bundled IEMS contracts. A deterministic failure prevents IEMS from starting.

## Safe end-of-session shutdown

```bash
./scripts/stop-iems.sh
./scripts/stop-dcg.sh
./scripts/database-down.sh
```

## Complete fresh-WSL2 acceptance

Run this only on a new Ubuntu 24.04 x86-64 WSL2 installation after `verify-install.sh` passes and all required ports are free:

```bash
export ACCEPTANCE_EVIDENCE="$PWD/workspace/evidence/ubuntu-wsl2-$(date -u +%Y%m%dT%H%M%SZ)"
python3 scripts/ubuntu-wsl2-acceptance.py --evidence "$ACCEPTANCE_EVIDENCE"
jq '{status, target, checks, cleanup, error}' "$ACCEPTANCE_EVIDENCE/results.json"
```

The runner requires AI advisory availability, exercises the IEMS Postman collection, records DCG history in all three databases, starts IEMS on all three databases, removes only its disposable container volumes, and requires all task ports to be free afterward.
