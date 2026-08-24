# 02 — Undocumented limits in managed services

The defining small-company failure: **a hard ceiling that has no metric, no alarm, no console
warning, and no documentation** — until you hit it, at which point it takes the production
database *and every backup* at the same time.

---

## Instapaper, 9–10 February 2017 — the 2TB file that ended a database

**Company:** Instapaper (a small team; owned by Pinterest at the time). Infrastructure:
**Amazon RDS for MySQL**. **Impact: 31 hours fully down, then ~5 more days of degraded/limited
access.**

### What happened

At **12:30 PM PT on Wednesday 9 February 2017**, Instapaper's `bookmarks` table — the table
holding every article every user has ever saved, i.e. the entire product — stopped accepting
writes.

The cause was a limit that did not appear anywhere in the RDS console, in RDS monitoring, or
in Instapaper's own dashboards:

> **RDS MySQL instances created before April 2014 were backed by an ext3 filesystem, which
> has a 2 TiB per-*file* limit.** Instances created after that date use ext4, where the
> equivalent per-file ceiling is 16 TiB.

Instapaper's instance predated the cutoff. Their `bookmarks` table hit exactly 2 TB and the
filesystem refused to grow the file. Nothing about the instance type, the storage allocation,
or the RDS console gave any indication this boundary existed.

### Three different ceilings, routinely conflated

The reason this limit was invisible is that it lives one layer below the one everybody watches.
There are three independent numbers here, and hitting any of them stops writes:

| Ceiling | Scope | Value then | Value today |
|---|---|---|---|
| RDS **allocated storage** | The whole DB *instance* — every database, table and index on it | 6 TB | **64 TiB** (RDS MySQL/MariaDB/PostgreSQL/Oracle on gp3/io1/io2; 16 TiB for SQL Server; Aurora auto-grows to 128 TiB) |
| Filesystem **max file size** | One file on disk | 2 TiB (ext3) | 16 TiB (ext4) |
| Engine **max relation size** | One table or index, as the engine defines it | — | 64 TiB (InnoDB tablespace), 32 TB (PostgreSQL) |

The widely-cited "6 TB RDS limit" — raised to 16 TiB in November 2017 and to 64 TiB since — is
the *first* row: how big the EBS volume attached to the instance may be. It is not a per-file or
per-table number. Instapaper died on the *second* row, with the first row nowhere near exhausted.

### Why one table was one file: `innodb_file_per_table`

The per-file limit only becomes a per-*table* limit because of an InnoDB storage setting. With
`innodb_file_per_table = ON` — the default since MySQL 5.6, and the RDS default — each table gets
its own `.ibd` file, with its secondary indexes inside that same file. So `bookmarks` was a single
2 TB file, and it hit the wall alone while the rest of the schema was irrelevant to the failure.
(With the setting `OFF`, every table shares one `ibdata1` file, which reaches the same ceiling
*sooner* — the setting changes who dies first, not whether the ceiling exists.)

### Why it was so much worse than a normal capacity problem

Three multipliers, each of which is the real lesson:

1. **The backups died with the primary.** In their own words: *"A filesystem-based limitation
   we weren't aware of and had no visibility into rendered not only our production database
   useless, but all our backups, too."* The backups were the same logical database on the same
   filesystem generation — so the constraint applied to them identically. **Their backup
   strategy had zero independence from the failure mode.**
2. **They had no filesystem access.** RDS is managed: the only interface is MySQL itself. The
   obvious fix — `rsync` the data files to a bigger filesystem — was not available to them
   without **direct involvement from Amazon's own engineers**, which is not something you can
   requisition at 12:30 on a Wednesday. Recovery speed became a function of AWS support response.
3. **There was no fast path.** The eventual fix was to copy the entire database onto a new
   ext4-backed filesystem. Even executed perfectly, they estimated a **minimum of ~10 hours**
   of downtime just to reconstruct the database. Reality was 31 hours plus a week of tail.

### Transferable lessons

1. **Ask what the *substrate* limits are, not just the service limits.** The RDS docs discussed
   storage allocation; the killer was a filesystem-generation detail inherited from the instance's
   creation date. Anything you provisioned years ago may be running on defaults that no longer
   exist for new resources.
2. **Old resources carry old defaults, invisibly.** A resource created in 2019 does not silently
   upgrade itself when the provider improves the defaults in 2021. Periodically ask: *"if I created
   this from scratch today, would I get something different?"* — and for databases specifically,
   a scheduled recreate-and-migrate is a legitimate maintenance activity.
3. **A backup that shares a failure mode with production is not a backup.** This is the same
   lesson as [GitLab](01-data-loss-and-untested-backups.md) approached from a completely different
   angle. Backups need **independence**: different storage, different region, ideally a different
   format (a logical `pg_dump`/`mysqldump` restores onto *anything*; a filesystem snapshot only
   restores onto the same substrate).
4. **On managed services, your MTTR includes the vendor's response time.** Before adopting a fully
   managed service, ask: *what class of problem can I not fix myself, and what's my plan when one
   happens?* For a small team that trade is usually still correct — but it should be a decision,
   not a surprise.
5. **Graph absolute size against the ceiling, for every ceiling you can name.** Table size, disk
   size, row counts, connection counts, IOPS, IDs remaining on 32-bit sequences.

### Why this exact failure cannot happen on PostgreSQL

Worth knowing, because it changes *which* number you graph. Postgres does not store a relation as
one file. Each table, each index and each TOAST table is written as a chain of **1 GB segment
files** — `base/<db_oid>/<relfilenode>`, then `.1`, `.2`, `.3`, … — specifically so the engine
never depends on large-file support in the filesystem underneath it. A 2 TB Postgres table is
~2,000 ordinary 1 GB files. There is no per-file wall to hit.

What replaces it:

- **32 TB per relation** — a hard engine limit (2³² blocks × the 8 KB default `BLCKSZ`), not a
  filesystem one. Note this is *per table and per index separately*, so a table can exceed 32 TB
  in total on-disk footprint while no single relation does.
- **Segmentation means file count, not file size, is the pressure.** Roughly one inode per GB per
  relation. That makes the `df -i` row in the table below a real concern on a large Postgres box
  rather than a theoretical one.
- **The ceilings that actually bite first** are the ones already listed below: 32-bit sequence
  exhaustion and TXID wraparound. Both arrive at realistic data volumes; 32 TB does not.

The transferable lesson is unchanged — *ask what the substrate's limits are, not just the
service's*. It simply lands on different numbers, because the substrate is different.

---

## The same shape, elsewhere — limits worth graphing today

Concrete ceilings that regularly take down small SaaS, all with the same signature (no default
alarm, hard stop when reached):

| Limit | Where it bites | The metric to graph |
|---|---|---|
| **32-bit integer primary keys** | `INSERT` fails permanently at 2,147,483,647 on a hot table (events, logs, line items). Migrating a live PK to `bigint` is a genuinely hard, hours-long operation | `max(id)` as a % of `2^31` for every `serial` PK |
| **PostgreSQL TXID wraparound** | Writes stop entirely if `VACUUM` can't keep up. Both [Notion and Figma](../aws-production-issues/03-databases-on-aws.md) hit the precursor to this | `age(datfrozenxid)` vs `autovacuum_freeze_max_age` |
| **Max relation size** | A single Postgres table or index stops growing at 2³² blocks (32 TB at the default 8 KB `BLCKSZ`); InnoDB tablespaces cap at 64 TiB | largest `pg_relation_size()` vs 32 TB |
| **Disk full on the DB volume** | Postgres/MySQL stop accepting writes; WAL/binlog growth from a stuck replication slot is the classic cause | free bytes, *and* replication slot lag |
| **Inode exhaustion** | Disk shows free space, writes still fail — many small files (sessions, cache, uploads) | `df -i`, not just `df -h` |
| **Connection limits** | See [06 — The single database](06-the-single-database.md) | connections vs `max_connections` |
| **Provider account quotas** | Sending limits, API rate limits, resource-count quotas that silently cap growth | current usage vs quota, per quota |

**The rule:** for every hard limit you can name, put the current value and the ceiling on the
same graph, and alarm at 70%. The limits that hurt are the ones nobody put on a graph — which
is precisely the ones nobody knew existed.

## Sources

- [Brian Donohue — Instapaper Outage Cause & Recovery](https://medium.com/making-instapaper/instapaper-outage-cause-recovery-3c32a7e9cc5f) *(primary)*
- [funkypenguin — Implicit 2TB limit breaks Instapaper for 31 hours](https://www.funkypenguin.co.nz/blog/what-you-dont-know-can-hurt-you/)
- [The Register — Instapaper in 31-hour outage](https://theregister.com/2017/02/10/instapaper_outage)
- [AWS — RDS now supports database storage size up to 16TB](https://aws.amazon.com/about-aws/whats-new/2017/11/amazon-rds-now-supports-database-storage-size-up-to-16tb-and-faster-scaling-for-mysql-mariadb-oracle-and-postgresql-engines) *(the limit that later moved)*
