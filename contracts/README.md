# Contracts Folder

This folder stores contract definitions and versions.

Example:

```txt
contracts/
  orders.created/
    metadata.yaml
    v1.json
```

Rules:
- One folder per contract ID.
- Use `metadata.yaml` for owner/domain/compat mode.
- Use versioned schemas: `v1.json`, `v2.json`, ...
