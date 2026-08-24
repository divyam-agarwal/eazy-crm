# 03 — Databases on AWS

The single most common source of "we are about to hit a wall" engineering at AWS-primary
companies. Managed databases remove *operational* work; they do not remove the ceiling.
Every case below is a story about discovering a **hard limit that no amount of money
could raise**.

---

## 3.1 — Notion: sharding Postgres on RDS before it stopped accepting writes

**Company:** Notion (AWS-primary, Amazon RDS Postgres).

### What happened

By mid-2020 Notion's Postgres monolith — five years and four orders of magnitude of growth
old — was failing. Symptoms:

- On-call engineers were being woken by **database CPU spikes**.
- Simple catalog-only migrations became "unsafe and uncertain."
- **`VACUUM` stalled consistently**, so dead tuples were never reclaimed and disk usage
  climbed without bound.
- And the real deadline: a stalled `VACUUM` means the transaction ID counter marches toward
  **TXID wraparound**, at which point Postgres *refuses all writes*. This is not a
  performance problem — it is a hard stop with a date on it.

### Why it was hard

- The forcing function wasn't gradual. Wraparound is a cliff, and the mitigation
  (`VACUUM FREEZE`) was itself failing.
- The data model is a deeply recursive tree of **blocks** — the hardest possible shape for
  a sharding key, because a naive key scatters a single page's children across shards.
- They had to migrate a live, write-heavy production database with no maintenance window
  that customers would tolerate.

### How they fixed it

**Shard key: workspace ID.** Every block belongs to exactly one workspace, and users work
within a single workspace, so almost every query is single-shard. This is the whole trick —
they found the entity that *naturally* bounds the query graph.

**Shard count: 480 logical shards over 32 physical RDS instances** (15 logical per physical).
480 was chosen deliberately as a **highly composite number**: it lets them go 32 → 40 → 48
physical hosts by moving logical shards, instead of being forced to *double* the way a
power-of-2 scheme would. They bounded each shard at ≤500 GB per table and ≤10 TB per physical
database, sized to keep RDS replication and `VACUUM` healthy.

**Migration: audit log + catch-up script, not logical replication.** They rejected Postgres
logical replication and naive dual-writes in favour of writing every incoming mutation to an
**audit log**, then running a catch-up script that applies the log to the new shards. This
gave them restartability, backpressure control, and the ability to verify and re-run — where
a failed dual-write leaves you with silent divergence. Backfill, then verify, then a
switchover with a short window of downtime.

In 2023 they re-sharded again: **96 physical instances × 5 logical shards**, exactly the
incremental move the 480 choice was designed to allow.

### Transferable lesson

1. **Choose the shard key from the product's natural tenancy boundary** — for Notion it was
   workspace; for a B2B SaaS it is almost always **tenant/organisation**. If most queries are
   scoped to one tenant, sharding is tractable; if they aren't, fix that first.
2. **Pick a highly composite shard count.** 480, not 512. It buys you incremental rebalancing forever.
3. **Migrate via a durable log you can replay and verify**, not via dual-writes you can only hope are consistent.
4. **`VACUUM` health and TXID age are availability metrics**, not database-nerd trivia. Alert on
   `age(datfrozenxid)` before you alert on CPU.
5. Their own stated regrets are the most useful part: **shard earlier**, invest in the catch-up
   script for zero-downtime, and **make the partition key part of the primary key from day one**
   so application routing is trivial.

---

## 3.2 — Figma: 100× growth, and the day RDS IOPS became the ceiling

**Company:** Figma (AWS-primary, RDS Postgres).

### What happened

Figma's Postgres footprint grew ~100× from 2020. Their first move was **vertical partitioning**:
split groups of related tables onto their own database instances. That bought years. Then it
stopped working, for a precise reason: *the smallest unit of vertical partitioning is a single
table*, and some individual tables were now **multiple terabytes and billions of rows**. There
was nothing left to split off.

Two hard limits arrived together:
- `VACUUM` on those tables caused **reliability impact** (the same failure family as Notion).
- The highest-write tables approached the **maximum IOPS Amazon RDS can provide to a single
  database instance**. You cannot buy your way past this; it is a property of the instance.

### Why it was hard

- They had **months of runway**, not years, when they concluded vertical partitioning was exhausted.
- Every off-the-shelf answer required either a cross-engine migration (CockroachDB, TiDB, Spanner)
  or a rewrite (NoSQL — which can't express Figma's relational model), and both are 18-month
  projects with an unknown tail. They explicitly chose to **keep their hard-won RDS Postgres
  operational expertise** over adopting a technically-purer system they'd have to learn under
  pressure.
- Existing sharding middleware (Vitess) assumed patterns their application didn't match.

### How they fixed it

They built horizontal sharding **on top of** RDS Postgres. The design choices are worth studying:

- **Colos (colocation groups):** groups of related tables that share a sharding key and physical
  layout, so joins and transactions within a colo stay local and developers reason about one unit.
- **Separate logical sharding from physical sharding.** They first shipped *logical* shards as
  **views on the existing unsharded databases**. Nothing moved; everything was reversible. Only
  once the application was provably correct against logical shards did they perform physical splits.
  This is the single most important idea in the whole project: **de-risk by decoupling the
  correctness change from the data movement.**
- **DBProxy**: a query engine that intercepts SQL, parses it to an AST, extracts the shard key,
  and routes to the right physical database — including scatter-gather when the key is absent.
- **Shadow planning framework**: replay *live production traffic* against the proposed sharding
  scheme to discover which queries would break, and from that define the supported SQL subset.
  They found the incompatible queries from real traffic rather than by code review.
- **Hash the shard key** rather than migrating to random IDs — accepting worse range scans in
  exchange for even distribution and no ID migration.

Result: their first physical shard split (September 2023) cost **~10 seconds of partial primary
availability and zero replica impact**, after nine months of incremental rollout.

### Transferable lesson

1. **Vertical partitioning is the cheap first move and it has a known end**: when one table alone
   exceeds an instance, you're done. Know how far away that is.
2. **Ship the routing layer before you move any data.** Logical shards as views, verified in
   production, then physical splits. Reversible steps only.
3. **Use production traffic to define your supported query subset.** Shadow analysis finds the
   `JOIN` across shard keys that nobody remembered writing.
4. **"Boring technology we operate well" often beats "correct technology we've never run."**
   Especially with months of runway.
5. RDS single-instance **IOPS is a real ceiling** — check where you are against it, today.

---

## 3.3 — Monzo, 29 July 2019: one Cassandra flag, wrong by default

**Company:** Monzo (AWS-primary, self-managed Cassandra on EC2). Impact: ~10 hours of
customer-facing failures — logins, missing transactions, wrong balances, failed payments,
and support chat unreachable.

### What happened

Monzo scaled their Cassandra cluster from **21 to 27 nodes**. The plan: add six nodes in an
*inactive* state, then stream data to them deliberately.

They set `auto_bootstrap: false`, believing it only controlled whether new nodes stream data.
It does more than that: **the flag also controls whether new nodes join in an active or
inactive state.** The six new nodes joined *active*. They immediately took ownership of token
ranges — of real data partitions — while holding **no data at all**.

Monzo reads and writes at **quorum** (2 of 3 replicas). With empty nodes now counted among the
replicas for a partition, a read could get two "not found" answers from empty new nodes and
satisfy quorum with **404 — no such row**. The database confidently returned "this doesn't
exist" for data that did exist.

Detection was slow: engineers initially **discounted Cassandra**, because cluster metrics
looked normal. They'd tested this change in staging — but with **one** new node, not six.
With one node, quorum still finds the data on the two old replicas, so the bug is invisible.

### Why it was hard

- **The failure mode was silent data absence, not error.** Every layer reported success.
  A 404 looks like a legitimate answer; a 500 looks like a bug. This is far harder to alert on.
- **The staging test was the wrong shape, not the wrong environment.** One node vs six is the
  difference between "quorum still has the data" and "quorum doesn't." Scale-invariant testing
  is a trap.
- Configuration flags with **implicit secondary effects** are undetectable by review; you only
  learn from the source or from production.

### How they fixed it

*Immediate:* after ~1 hour, engineers queried Cassandra directly, confirmed missing rows,
and identified that the new nodes held token ranges without data. They **decommissioned the six
nodes over ~90 minutes**, then spent **7+ hours replaying external events and running
reconciliation** to restore consistency — finishing around 23:00.

*Durable:* fix and **document every Cassandra setting** they depend on, rather than trusting
defaults; add monitoring for **"row not found"** rates as a first-class signal; and structurally,
migrate from one large cluster to **multiple smaller clusters** so a mistake affects one
bounded blast radius instead of the whole bank.

### Transferable lesson

1. **Alert on unexpected absence, not just on errors.** A sudden rise in 404s / empty result
   sets / `Optional.empty()` on a hot path is an incident signal.
2. **Test the change at the shape it will run at.** Adding 1 node ≠ adding 6. Quorum-based
   systems have behaviours that only appear past a threshold.
3. **Never trust a boolean flag's name.** Read the source or the docs for secondary effects
   before flipping anything in a consensus/replication system.
4. **Recovery time was dominated by reconciliation, not by the fix.** Because they had a durable
   external event log to replay, recovery was possible at all. Without it, this is unrecoverable
   data loss.

---

## 3.4 — DynamoDB hot partitions *(composite pattern, not one company)*

Extremely common; near-universally under-appreciated.

### What happens

DynamoDB spreads a table across partitions by hash of the partition key. **Each partition has
its own hard throughput ceiling — roughly 3,000 RCU and 1,000 WCU.** Table-level capacity, and
on-demand mode, do not change this.

So a table provisioned at 100,000 WCU will still throttle at 1,000 WCU if the traffic targets
one key. Classic triggers:

- A partition key with low cardinality: `status`, `country`, `tenant_type`, `date`.
- A "current day" key: `PK = 2026-08-20` — 100% of today's writes land on one partition.
- A hot tenant in a multi-tenant table (`PK = tenantId`) — the biggest customer alone exceeds
  a partition's limit.
- A `Scan` without pagination in a reporting path, which drains the table's capacity in seconds
  and starves the transactional workload.

### Why it's hard

- **The dashboard lies.** Consumed capacity at the table level looks fine — say 8% — while
  requests throttle, because the metric averages across partitions. You are looking at a healthy
  aggregate on top of a saturated shard.
- **Throwing capacity at it does nothing**, which is the opposite of the instinct under pressure.
- Partition splits happen automatically over time ("split for heat"), so the same schema can be
  fine for months and then throttle when a single tenant grows.

### How teams fix it

- **Write sharding**: `PK = tenantId#<0..N>`, fanning writes across N synthetic partitions, then
  scatter-gather on read. N is chosen per-tenant based on volume.
- **Time bucketing** with a suffix: `PK = 2026-08-20#07` instead of `2026-08-20`.
- **Replace `Scan` with `Query`** by adding a composite sort key that supports the access pattern;
  paginate everything; move reporting to a separate read path (exported to S3/Athena) instead of
  competing with OLTP traffic.
- **Cache** (DAX or application-level) in front of read-hot keys.
- **Buffer** writes through Kinesis/SQS so bursts are smoothed rather than passed through.

### Transferable lesson

> **DynamoDB throttling is almost always a data-modelling problem, not a capacity problem.**

The general principle beyond DynamoDB: **any system that partitions by key has a per-partition
ceiling, and aggregate metrics hide per-partition saturation.** The same is true of Kafka
partitions, Kinesis shards, and database shards. Always graph the **hottest partition**, not
the average.

---

## 3.5 — Aurora / RDS failover and connection storms *(composite pattern)*

### What happens

Two recurring shapes:

**Connection storms.** A runaway query or a traffic spike pushes CPU toward 100%; the instance
slows; application connection pools respond by opening *more* connections; the instance now
spends its time on connection handling and context switching, and becomes unresponsive. Hitting
`max_connections` returns errors to *healthy* traffic. Failover then triggers — and every client
in the fleet reconnects at once, which is a second connection storm aimed at a cold instance.

**Failover that doesn't route.** A documented production case: an Aurora cluster with one writer
and one reader, with **RDS Proxy** in front using the *read-only* endpoint. The reader suffered a
host-level failure. RDS Proxy **waited for the reader to return rather than rerouting reads to
the healthy writer**, so read queries failed for ~10 minutes even though a perfectly good
instance was available.

### Why it's hard

- Failover is advertised as "seconds," and it *is* — for DNS. The recovery time your users
  experience is dominated by **client behaviour after failover**: DNS TTL caching in the JVM,
  stale pooled connections, and simultaneous reconnect.
- **RDS Proxy is both the fix and a new failure domain.** It genuinely absorbs connection churn
  and shortens failover — and it also introduces its own routing policy that can hold a stale
  view of health.
- Nobody exercises failover, so the first real one is also the first rehearsal.

### How teams fix it

- **Put a proxy/pooler in front** (RDS Proxy, PgBouncer) so the database sees a small, stable
  set of connections and the churn is absorbed outside it. Especially mandatory with Lambda —
  see [04](04-serverless-and-decomposition-limits.md).
- **Cap application pool size deliberately**: `total_connections = pods × pool_size` must be
  well under `max_connections`, with headroom for admin sessions and migrations.
- Set a **low JVM DNS TTL** (`networkaddress.cache.ttl`) and use the cluster endpoints correctly
  (writer endpoint vs reader endpoint vs custom endpoints).
- **Health-check readers and fail reads over to the writer** rather than assuming the proxy will.
- **Rehearse failover on a schedule** (AWS's own chaos-engineering workshop has a module for
  exactly this) so the runbook is proven and the reconnect storm is measured.
- Reconnect with **exponential backoff and jitter**, never in a tight loop.

### Transferable lesson

**Managed failover moves the problem to the client.** Your availability during failover is a
property of your connection pool configuration, your DNS caching, and your retry policy — none
of which AWS controls.

---

## Sources

- [Notion — Herding elephants: lessons learned from sharding Postgres at Notion](https://www.notion.com/blog/sharding-postgres-at-notion) *(primary)*
- [pganalyze — How Notion Runs PostgreSQL at Scale on Amazon RDS](https://pganalyze.com/blog/how-notion-runs-postgres-at-scale)
- [Figma — How Figma's Databases Team Lived to Tell the Scale](https://www.figma.com/blog/how-figmas-databases-team-lived-to-tell-the-scale/) *(primary)*
- [Figma — The growing pains of database architecture](https://www.figma.com/blog/how-figma-scaled-to-multiple-databases/) *(primary)*
- [pganalyze — How Figma built DBProxy for sharding Postgres](https://pganalyze.com/blog/5mins-postgres-figma-dbproxy-sharding-postgres)
- [Monzo — We had issues with Monzo on 29th July. Here's what happened](https://monzo.com/blog/2019/09/08/why-monzo-wasnt-working-on-july-29th) *(primary)*
- [AWS Database Blog — Scaling DynamoDB: how partitions, hot keys, and split for heat impact performance](https://aws.amazon.com/blogs/database/part-1-scaling-dynamodb-how-partitions-hot-keys-and-split-for-heat-impact-performance/) *(primary)*
- [OneUptime — How to Troubleshoot Aurora Failover Events](https://oneuptime.com/blog/post/2026-02-12-troubleshoot-aurora-failover-events/view)
- [AWS Chaos Engineering Workshop (includes an Aurora cluster failover module)](https://catalog.workshops.aws/chaos-engineering/en-US)
