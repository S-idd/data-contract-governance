# Contracts Folder

This folder stores contract definitions and versions.

Example:

```txt
contracts/
  policy-packs.json
  orders.created/
    metadata.yaml
    v1.json
```

Rules:
- One folder per contract ID.
- Use `metadata.yaml` for owner/domain/compat mode.
- Optional: set `policyPack` in `metadata.yaml` to override the default pack.
- Use versioned schemas: `v1.json`, `v2.json`, ...
