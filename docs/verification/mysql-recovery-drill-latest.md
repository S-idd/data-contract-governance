# MySQL isolated recovery drill evidence

- Status: PASS
- Completed at (UTC): 2026-08-22T11:39:16Z
- Source database: dcg_recovery_src_20260822170853_16372 (disposable)
- Restored database: dcg_recovery_restore_20260822170853_7135 (disposable and isolated)
- Backup method: consistent logical backup (mysqldump with --single-transaction, --routines, and --triggers)
- Recovery point objective achieved: 0 seconds for the injected test check run: it was persisted before the backup and was present after restore.
- Recovery time objective achieved: 2 seconds from source service stop to a healthy service reading the restored check run.
- Database restart recovery: 5 seconds for the application to become healthy and read the persisted source check run after an isolated MySQL container restart.
- Duplicate-job guard: PASS. The original check submission was replayed after restart with the same Idempotency-Key and returned the same run ID.
- Validation: Flyway history, notification_deliveries, the persisted check run, and a contract-service read were all verified against the restored target.

## Scope

This is an application-level isolated logical-restore drill. It proves that the DCG schema and data can be restored and read. It does **not** prove production managed-backup encryption, binary-log retention, point-in-time recovery, regional durability, or managed failover; capture those provider control-plane settings separately before production approval.
