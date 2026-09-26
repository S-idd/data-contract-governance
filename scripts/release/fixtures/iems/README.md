# IEMS evaluation notification fixture

`seed_notification.py` is the SQLite Postman fixture helper from IEMS commit
`6ae4970712f6f58150e935d95b71524a7c675d40`, at
`scripts/postman/seed_notification.py`. Its original introduction was IEMS commit
`da1c723ffe9b037c335dfc245013d958a4e6755c` (`test(postman): add complete IEMS API demo`).
The Python file is copied byte-for-byte from that pinned version.

DCG keeps this evaluator fixture locally so the evaluation assembly does not depend
on whether an IEMS checkout contains its development helper scripts. Assembly copies
it to `iems/postman/seed_notification.py` and includes it in `SHA256SUMS` and the archive.
`run-iems-postman.sh` invokes it before every run because the collection marks its
notification read and deletes it. It writes only to the existing demo SQLite database
passed by the runner and requires the existing `demo-admin` account.
