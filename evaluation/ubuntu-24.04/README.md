# DCG and IEMS Ubuntu 24.04 evaluation bundle

This folder is assembled into a self-contained evaluator package. It targets Ubuntu 24.04 x86-64, including Ubuntu under WSL2. The accepted DCG package is copied unchanged into `dcg/`; the current IEMS executable is copied into `iems/iems.jar`.

Start with [docs/01-ubuntu-wsl2-setup.md](docs/01-ubuntu-wsl2-setup.md), then use [docs/02-evaluator-manual.md](docs/02-evaluator-manual.md). Database details and safe cleanup are in [docs/03-databases.md](docs/03-databases.md) and [docs/04-troubleshooting.md](docs/04-troubleshooting.md).

The writable evaluator area is `workspace/`. The evaluator may replace or add directories below `workspace/contracts/`. Packaged examples remain in `examples/` and accepted binaries remain in `dcg/`.

No credential is stored in the archive. Database and service passwords are generated locally at runtime. Do not publish `workspace/` or `docker/.env`; both contain private local evidence or credentials after use.

`verification/dcg-acceptance-summary.json` contains the sanitized PASS record tied to the exact DCG archive digest. The original private acceptance logs are not included.
