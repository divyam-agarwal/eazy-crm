# 06 — The single database

> **Composite pattern**, with concrete public anchors where they exist.

Almost every small SaaS is one Postgres (or MySQL) instance wearing a trenchcoat. That is the
correct architecture — it is not the problem. The problem is that **the single database is also
the single point of failure for every failure mode simultaneously**, and the six failure modes
below account for the overwhelming majority of small-SaaS outages.

They share a signature: **the database doesn't crash. It stops being available to your
application**, which from the user's side is identical.

---

## 1. Connection exhaustion — the #1 small-SaaS outage

**The arithmetic nobody does:**

```
total_connections = app_instances × pool_size
                  + background_workers × pool_size
                  + cron/one-off tasks
                  + your psql session
                  + migrations
                  + the monitoring agent
```

Postgres `max_connections` defaults to 100, and managed providers scale it with instance size —
a small RDS instance may allow only ~100–200. Four app pods with a pool of 20, plus 4 workers with
a pool of 10, is already 120. **You are one autoscale event away from an outage.**

**Why it's vicious:**
- Postgres forks a **backend process per connection**. Connections aren't free even when idle;
  a few hundred idle connections consume real memory and scheduler time.
- When the limit is hit, **new connections are refused** — including the `psql` session you need
  to diagnose it, and the migration you were about to run.
- It fails at exactly the wrong moment: a traffic spike scales your app out, which *increases*
  connection demand precisely when the database is busiest.
- A single **long-running or idle-in-transaction** session holds its slot indefinitely.

**Fixes:**
- **Put PgBouncer (or RDS Proxy) in front, in transaction pooling mode.** Hundreds of client
  connections multiplex onto a few dozen real ones. This is the single highest-leverage
  infrastructure change for a growing Postgres app. Caveat: transaction pooling breaks session-level
  features — prepared statements (depending on driver), `SET`, advisory locks, `LISTEN/NOTIFY`.
- **Cap pool size deliberately**, and write the arithmetic down. Smaller pools are usually faster:
  a pool of 5–10 per instance beats 50, because the database can only actually do a few things at once.
- **Reserve superuser connections** (`superuser_reserved_connections`) so you can always get in.
- **Alarm on connection count as a % of `max_connections`.** At 70%.
- Set **`idle_in_transaction_session_timeout`** and `statement_timeout` so a stuck session can't
  hold a slot or a lock forever.

## 2. The missing index / N+1 that only appears at scale

A query that's fine on 10,000 rows becomes a sequential scan at 10 million. The classic small-SaaS
version: an ORM lazily loading a relation inside a loop, turning one page render into 500 queries.
Both are invisible in development, where the dataset is tiny.

**The compounding effect:** a slow query holds its connection for longer, which pushes you toward
failure mode #1. **Slow queries and connection exhaustion are the same incident** most of the time.

**Fixes:** `pg_stat_statements` enabled from day one (it is the single most useful thing you can
turn on); alert on p99 query time and on rows-scanned-vs-returned ratios; log queries over 500ms;
review `EXPLAIN` for any new query touching a table that grows unboundedly; assert query *counts*
in tests for hot endpoints, which catches N+1 at PR time rather than in production.

## 3. Lock contention & long transactions

An `ALTER TABLE` needs an `ACCESS EXCLUSIVE` lock. If a long-running query holds a conflicting lock,
your migration waits — and **every subsequent query on that table queues behind your migration**,
because Postgres lock requests are ordered. The table is now effectively down, and the cause is a
migration that "hasn't even started yet."

**Fixes:** always `SET lock_timeout = '3s'` at the top of migrations so they fail fast instead of
building a queue; never run schema changes inside a long transaction; batch backfills with sleeps;
use `CREATE INDEX CONCURRENTLY`; keep application transactions short and never do network I/O
(an API call, an email send) inside an open transaction.

## 4. VACUUM falling behind → bloat → TXID wraparound

Postgres `UPDATE`/`DELETE` leaves dead tuples that `VACUUM` reclaims. If autovacuum can't keep up —
usually because of a **long-running transaction**, an **abandoned replication slot**, or a very hot
table — dead tuples accumulate. Disk grows, queries slow, and eventually the transaction ID counter
approaches wraparound, at which point **Postgres refuses all writes** to protect data integrity.

This is not theoretical for small companies: it is precisely what forced
[Notion](../aws-production-issues/03-databases-on-aws.md) to shard, and
[Figma](../aws-production-issues/03-databases-on-aws.md) hit the same wall.

**Fixes:** monitor `age(datfrozenxid)` and table bloat; alarm well before `autovacuum_freeze_max_age`;
hunt long-running transactions and idle replication slots (an inactive slot pins WAL *and* blocks
vacuuming forever); tune autovacuum to be more aggressive on hot tables rather than trusting defaults.

## 5. No read/write separation, and no read replica when you need one

Every analytics query, every CSV export, every report the founder runs at month-end competes with
transactional traffic on the same instance. One unbounded export can saturate I/O and take down
sign-ups.

**Fixes:** a read replica for reporting and exports, even a small one; `statement_timeout` on the
analytics role specifically; paginate and stream exports rather than materialising them; move heavy
reporting off the OLTP path entirely once it hurts.

## 6. Multi-tenant noisy neighbours

In a shared-table multi-tenant SaaS, **one large customer's data volume degrades everyone**. Their
100,000-row account makes an unindexed `WHERE tenant_id = ?` scan slow for every tenant sharing the
table. Their bulk import saturates write I/O. Their report locks a table.

**Fixes:** index `(tenant_id, ...)` as a **prefix on every index**, not `tenant_id` alone; per-tenant
rate limits and quotas on expensive operations (imports, exports, report generation); run bulk
tenant operations on the replica or in a separate worker pool with lower priority; watch per-tenant
query time, not just aggregate — an average across 500 tenants hides the one tenant having an outage.
*(Same principle as "graph the hottest partition" in `../aws-production-issues/09-patterns-and-checklist.md`.)*

---

## The monitoring set that catches all six

Small, cheap, and almost nobody has all of it:

| Metric | Alarm at | Catches |
|---|---|---|
| connections / `max_connections` | 70% | #1 |
| longest running query | > 30s | #2, #3, #4 |
| longest transaction (incl. `idle in transaction`) | > 60s | #3, #4 |
| `pg_stat_statements` top queries by total time | review weekly | #2 |
| lock waits / blocked queries | any sustained | #3 |
| `age(datfrozenxid)` | 50% of `autovacuum_freeze_max_age` | #4 |
| table & index bloat | growth trend | #4 |
| replication slot lag / inactive slots | any inactive slot | #4, disk |
| disk free on the data volume | 25% remaining | #4, and see [02](02-undocumented-limits-in-managed-services.md) |
| p99 query time **per tenant** | deviation from median tenant | #6 |

## When to actually shard (and what to do first)

Almost certainly **not yet**. The honest ladder, cheapest first:

1. Add the missing indexes. Fix the N+1s.
2. Add PgBouncer.
3. Add a read replica; move reporting and exports to it.
4. Vertical scale — it is astonishing how far one large instance goes, and it costs an afternoon.
5. Move the highest-volume append-only tables (events, audit logs, activity feeds) out to their
   own store.
6. Vertically partition — split table groups onto separate instances (Figma's first move; it bought
   them years).
7. Only then shard horizontally.

**But make one decision now, while it's free:** pick your future shard key today and make it part
of the primary key and the prefix of every index. For a multi-tenant B2B SaaS that key is
`tenant_id`. Notion's explicitly stated regret was not making the partition key part of the primary
key from the start. Retrofitting it later is a migration; doing it now is a schema convention.

## Sources

- [PostgreSQL — Routine Vacuuming & preventing transaction ID wraparound failures](https://www.postgresql.org/docs/current/routine-vacuuming.html) *(primary)*
- [PostgreSQL — `pg_stat_statements`](https://www.postgresql.org/docs/current/pgstatstatements.html) *(primary)*
- [PgBouncer — Features & pooling modes](https://www.pgbouncer.org/features.html) *(primary)*
- [Notion — Herding elephants: lessons learned from sharding Postgres](https://www.notion.com/blog/sharding-postgres-at-notion) *(primary)*
- [Figma — How Figma's Databases Team Lived to Tell the Scale](https://www.figma.com/blog/how-figmas-databases-team-lived-to-tell-the-scale/) *(primary)*
