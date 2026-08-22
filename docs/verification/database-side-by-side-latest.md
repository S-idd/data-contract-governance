# DCG PostgreSQL vs MySQL capacity baseline

- Status: PASS
- Recorded at (UTC): 2026-08-22T11:28:21.322715Z
- Workload: 100 queued-run writes and 100 indexed paginated reads per database after 10 warmup writes.
- Pool: max 10, minimum idle 2, connection timeout 3 seconds.

| Backend | Write ops/s | Write p95/p99 ms | Read ops/s | Read p95/p99 ms | Pool after run |
| --- | ---: | ---: | ---: | ---: | --- |
| PostgreSQL | 2614.9 | 0.50 / 0.55 | 994.6 | 2.80 / 4.52 | active=0, waiting=0 |
| MySQL | 1111.9 | 1.22 / 1.49 | 1129.9 | 1.01 / 2.02 | active=0, waiting=0 |

## Scope

This is a reproducible local baseline, not a production capacity claim. Repeat it against the selected production-like topology and add CPU, I/O, lock/deadlock, query-plan, and error-rate evidence before setting acceptance budgets.
