# Version 4 Cassandra Research Gate

- Plan ID: `PLAN-2026-V4-CASSANDRA-RESEARCH`
- Status: `Draft`
- Created date: `2026-05-23`
- Purpose: evaluate Cassandra before any implementation commitment

## 1. Decision Rule

Cassandra must not be implemented until this research gate produces one of these outcomes:

1. `No-go`: Cassandra is not a good fit for DCG.
2. `Research spike`: more evidence is needed before design.
3. `Projection-only`: Cassandra may store derived read models, while Postgres/MySQL remains the source of truth.
4. `Metadata backend proposal`: Cassandra may become a metadata backend only after a full RFC and test plan.

## 2. Workload Hypothesis

Fill this before any design work:

1. Target workload:
2. Expected writes per second:
3. Expected reads per second:
4. Data volume after 6 months:
5. Retention requirement:
6. Required query patterns:
7. Required consistency:
8. Recovery time objective:
9. Recovery point objective:
10. Why Postgres/MySQL is insufficient:

## 3. Data Ownership Question

Choose one:

1. Cassandra stores canonical `check_runs`, `check_run_logs`, and `audit_logs`.
2. Cassandra stores derived history or analytics projections only.
3. Cassandra is not needed.

Default recommendation for V4:

Use Cassandra only as a possible projection store unless research proves it can safely handle correctness-critical metadata workflows.

## 4. Correctness Questions

Research must answer:

1. How will queued runs be claimed without double-processing?
2. How will `QUEUED -> RUNNING -> PASS/FAIL` transitions stay correct?
3. How will pagination behave with deterministic ordering?
4. How will audit logs remain durable and queryable?
5. What happens during partial writes?
6. What happens during node failure or network partition?
7. Which consistency level is required for writes and reads?

## 5. Operations Questions

Research must answer:

1. How do we run Cassandra locally for tests?
2. How do we run it in Docker for repeatable smoke checks?
3. How are schema changes applied?
4. How are backups created?
5. How is restore verified?
6. What health checks and metrics are required?
7. What are the sizing and compaction expectations?
8. What failure modes should appear in the incident runbook?

## 6. Security Questions

Research must answer:

1. How are credentials injected?
2. How is TLS configured?
3. How are roles and permissions scoped?
4. How do we avoid logging secrets?
5. How does Cassandra auth fit the existing deployment profiles?

## 7. Test Bar

Before implementation, a Cassandra RFC must define tests for:

1. Create queued run.
2. Claim next queued run.
3. Complete run.
4. Requeue run.
5. Append and list logs.
6. List runs by contract and commit.
7. Page runs with deterministic ordering.
8. Record and query audit logs.
9. Handle duplicate or conflicting writes.
10. Recover after simulated backend failure.

## 8. Recommendation Template

Use this format after research:

```text
Recommendation: No-go | Research spike | Projection-only | Metadata backend proposal

Reason:

Evidence:

Risks:

Required implementation work:

Required test work:

Required runbooks:

Decision owner:
```