# 01 — Data loss & untested backups

## GitLab, 31 January 2017 — five backup methods, none of them working

**Company:** GitLab (then ~150 people). Infrastructure: **Azure**, PostgreSQL 9.6, a single
primary + single hot-standby secondary. **Impact: ~18 hours down, ~6 hours of data permanently
lost** — roughly **5,000 projects, 5,000 comments, 700 user accounts**.

This is the most instructive incident in this entire directory, because everything that failed
was something the team believed they had.

### The chain of events (all times UTC)

**17:20** — An engineer takes a manual LVM snapshot of the production database to load into
staging. They're preparing to test `pgpool-II` for read load balancing. *(This snapshot, taken
casually and for an unrelated reason, is the only thing that saves the company.)*

**19:00** — Database load spikes. Two things collide: a wave of spam, and a background job
hard-deleting a GitLab **employee's** account and all associated data — the employee had been
reported for abuse by a troll, and the abuse-report tooling made it too easy to action a report
without inspecting it. Many users can't post comments. It takes hours to get load under control.

**23:00** — Under that load, the secondary's replication falls behind, then **fails**: the primary
had already recycled the WAL segments the secondary still needed, and **WAL archiving was not
enabled**. The only way back is to wipe the secondary's data directory and re-run `pg_basebackup`.

Then a cascade of small obstacles, each one reasonable:

- `pg_basebackup` **hangs silently**, producing no output despite `--verbose`.
- Eventually it errors: not enough replication connections. The engineers raise `max_wal_senders`
  from `3` to `32`.
- PostgreSQL now **refuses to restart** — too many semaphores. Cause: `max_connections` was set to
  **8000**, a value applied almost a year earlier that had been working fine. They reduce it to 2000
  and Postgres restarts.
- `pg_basebackup` *still* appears to hang. An engineer runs `strace`; it's blocked in `poll()`, with
  no further explanation.

**~23:27** — An engineer, believing `pg_basebackup` left stray files in the secondary's data
directory, runs the wipe again. **They run it on the primary — `db1` — instead of the secondary.**
They kill the command within a second or two. **~300 GB is already gone.**

The cruel detail: the "hang" was **normal behaviour**. `pg_basebackup` waits silently for the primary
to begin sending replication data. It was not documented in GitLab's runbooks *or* in the official
PostgreSQL docs.

### Then the recovery attempt — five mechanisms, five failures

| # | Mechanism | Why it failed |
|---|---|---|
| 1 | `pg_dump` to S3, every 24h | **The S3 bucket was empty.** The backup ran `pg_dump` **9.2** against a **9.6** database. Across major versions `pg_dump` errors out immediately. The Omnibus package picks the binary version by reading `$PGDIR/PG_VERSION` — but the backup job ran on an *application server*, which has no data directory, so it silently defaulted to 9.2. |
| 2 | Failure notifications for the above | Cron failure emails **were sent** — and **rejected by the receiving mail server** because **DMARC was not configured** for that sender. Nobody ever learned the backups were failing. |
| 3 | Azure disk snapshots | **Not enabled on the database servers.** They were enabled for the NFS/Git servers. The team assumed the other mechanisms were sufficient. (Also: restoring a snapshot across storage accounts can take *days* — in one prior case, over a week.) |
| 4 | LVM snapshots | Working, but **built for copying prod → staging, not for disaster recovery.** Two existed: one ~24h old, one manual one **6 hours old**. |
| 5 | Streaming replication | The secondary had **just been wiped** as part of fixing replication. Gone. |

### The recovery

They used the **6-hour-old manual LVM snapshot** — which existed only by luck, because an engineer
wanted fresher data for a load test.

The restore itself was then bottlenecked by cost-saving decisions made long before:

- The staging environment ran on **Azure classic without Premium Storage**, chosen to save money.
- Its network disks were throttled to roughly **60 Mbps**, with no way to upgrade to premium storage.
- **Copying the data from staging back to production took ~18 hours.** Not CPU, not network — the disks.

Restoring also required a subtlety: the staging copy has all webhooks stripped (deliberately, so
staging can't fire real webhooks). So they restored the main database from the sanitised copy, built
a *second* database from the raw snapshot purely to recover the `web_hooks` table, and merged it in.
Finally they **incremented every database sequence by 100,000** so that IDs from the lost window
could never be silently reused.

Throughout, they kept a **public Google Doc** of progress and **livestreamed the recovery on
YouTube**, peaking at ~5,000 concurrent viewers (briefly the #2 live stream on YouTube).

### GitLab's own 5-Whys — the part worth memorising

Their published root-cause analysis ends on this exchange:

> **Why did the backup procedure fail silently?** — Notifications were sent on failure, but the
> emails were rejected, and the sender was an automated process with no other way to report errors.
>
> **Why were the emails rejected?** — They were not signed using DMARC.
>
> **Why was the backup procedure not tested on a regular basis?** — **Because there was no
> ownership. As a result nobody was responsible for testing this procedure.**

The technical root cause is a version mismatch. The **organisational** root cause is an unowned
process. Every other failure in the table above traces back to the same thing.

### What they changed

From the postmortem's issue list:

- **Prometheus monitoring for backups** — alert on backup *success*, not on failure emails.
- **Point-in-time recovery / continuous WAL archiving** for PostgreSQL (the actual fix for both the
  replication break *and* the 6-hour data loss).
- **Hourly** LVM snapshots of production databases.
- **Azure disk snapshots enabled on the database servers.**
- `max_connections` set to a sane value.
- **Change the shell prompt (`PS1`) on every host** so production and staging are visually
  unmistakable — the cheapest, most effective control on the list.

### Transferable lessons

1. **Alert on the presence of a good backup, not on the absence of an error.** "We'd be told if it
   broke" failed twice over here: the email was sent *and* silently rejected. Positive-signal
   monitoring — *"the most recent restorable backup is under 2 hours old"* — would have caught it
   on day one.
2. **Restore, on a schedule, into a real environment.** Not "verify the file exists". An automated
   monthly restore-and-query test converts five theoretical backups into one real one.
3. **Assign an owner.** An unowned safety mechanism decays to zero. Put a name on it.
4. **Make production visually distinct** — prompt colour, hostname in the prompt, a different SSH
   config, a confirmation prompt on destructive commands. The engineer's mistake was ordinary; the
   environment made it invisible.
5. **Your restore speed is a design decision you already made.** GitLab's 18-hour copy was set by
   a storage tier chosen to save money on *staging*. Ask what your slowest link is *before* you need it.
6. **Cheap-and-lucky isn't a strategy.** The only usable copy was an ad-hoc snapshot taken by
   coincidence for an unrelated task.

---

## Related: Resend, February 2024

A second, smaller company, seven years later, with a much better outcome — because the backups
actually worked. Covered in
[03 — Migrations & destructive operations](03-migrations-and-destructive-operations.md).
The comparison is the point: **same class of mistake, one-tenth the damage, entirely because the
recovery path was real.**

## Sources

- [GitLab — Postmortem of database outage of January 31](https://about.gitlab.com/blog/postmortem-of-database-outage-of-january-31/) *(primary)*
- [GitLab — GitLab.com Database Incident (live blog)](https://about.gitlab.com/blog/gitlab-dot-com-database-incident) *(primary)*
- [The Register — GitLab.com melts down after wrong directory deleted, backups fail](https://www.theregister.com/2017/02/01/gitlab_data_loss/)
