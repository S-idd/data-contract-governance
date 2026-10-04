# IEMS evaluation notification fixture

`seed_notification.py` started from IEMS commit
`6ae4970712f6f58150e935d95b71524a7c675d40`, at
`scripts/postman/seed_notification.py` (introduced by `da1c723`). DCG now maintains
this copy with SQLite, PostgreSQL and MySQL evaluation support.

Assembly copies the helper to `iems/postman/seed_notification.py` and includes it
in `SHA256SUMS` and the archive. `run-iems-postman.sh` invokes it before every run
because the collection marks its notification read and deletes it.

SQLite uses Python's standard sqlite3 module against the existing demo database.
PostgreSQL/MySQL use the native client inside the matching Compose service, writing
to `iems_app` as the configured database user. Credentials come from the container
environment, not command-line password values. No Python drivers or host database
clients are required. A missing admin causes the seed step to fail before Newman.
