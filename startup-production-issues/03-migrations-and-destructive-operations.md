# 03 — Migrations & destructive operations

The single most common way a small SaaS destroys its own production data: **a command that was
correct, run against the wrong target.** Not a bug — a targeting error.

---

## Resend, 21 February 2024 — a local migration command pointed at production

**Company:** Resend (email API for developers; small team). **Impact: full outage 05:01–17:05 UTC
(~12 hours)** — no API, no email sending, no dashboard — plus **~5 minutes of permanent data loss**.

### What happened

In Resend's own words:

> *"While building a feature, we performed a database migration command locally, but it incorrectly
> pointed to the production environment instead, which dropped all tables in production."*

That's the whole root cause. An engineer running a routine local migration during feature work
had an environment variable / connection string resolving to production.

### Timeline (UTC)

| Time | Event |
|---|---|
| 04:50:00 | Last data safely persisted (start of the loss window) |
| 04:56:27 | Migration executes — **all production tables dropped** |
| 04:57 | Table drops detected |
| 05:01 | Restore from backup begins |
| 11:02 | **First restore completes — and fails.** The wrong backup timestamp had been selected |
| 12:05 | Second restore begins, on **upsized hardware**: 128 GB → 256 GB RAM, 32 → 64 ARM cores |
| 17:05 | Fully resolved |

**Data loss: the 04:50:00 → 04:56:27 window**, about 5 minutes 27 seconds.

### The two things that decided the outcome

**What went right:** the backups were real and current. Compare
[GitLab](01-data-loss-and-untested-backups.md), where the same class of mistake cost **6 hours of
data** and 18 hours of downtime because no backup mechanism actually worked. Resend lost 5 minutes.
That gap is entirely the difference between a tested recovery path and an assumed one.

**What went wrong:** *restore time*, not the drop. **11 of the 12 outage hours were spent restoring.**
And the first six of those were wasted — the restore completed and then turned out to be from the
wrong point in time, so the whole thing had to start again. The second attempt only completed in
reasonable time because they **doubled the hardware** underneath it.

The lesson hiding in that: **restore duration is a capacity-planning problem, and almost nobody
plans for it.** You size your database for steady-state query load, and then one day you need it
to ingest an entire backup as fast as physically possible. Those are different machines.

### What Resend committed to

1. Re-populate the 5-minute data-loss window (from downstream/source-of-truth records).
2. **Remove write privileges from the database roles that are reachable from developer machines.**
3. Harden local development safety so a local command can't resolve to production.
4. Build redundancy so **sending** specifically survives a database outage.
5. Expand disaster-recovery testing frequency.
6. Add incident banners in the dashboard so users learn from the product, not from Twitter.

Item 2 is the important one, and it's the general answer: not "be careful" but **structurally
remove the capability**.

### Transferable lessons

1. **Developer credentials must not be able to write to production. Ever.** A dev-laptop role
   should be read-only at most, and ideally have no production reach at all. This is a one-line
   `GRANT` change that would have prevented the entire incident.
2. **Make the environment impossible to confuse.** Distinct connection-string prefixes, a required
   `--env=production` flag with no default, a confirmation prompt that makes you type the database
   name, a coloured shell prompt (GitLab's `PS1` fix), separate credential stores. Layer several —
   any one of them alone will eventually be bypassed.
3. **Migration tooling should refuse destructive operations against production without an explicit
   override.** `DROP`, `TRUNCATE`, and destructive `ALTER` are a different risk class from `CREATE`.
   Gate them separately and require a second pair of eyes.
4. **Time your restore, and size for it.** Run a real restore of a production-sized backup and put
   the number in your runbook. Then ask whether you can afford it. Resend's answer was "not at this
   instance size."
5. **Verify the backup you're about to restore *before* you spend six hours on it.** Confirm the
   timestamp and do a cheap sanity check first — a `COUNT(*)` on a known table, the latest row's
   `created_at`. Six of the twelve outage hours were a restore of the wrong thing.
6. **Have a degraded mode for your core promise.** Resend's #4 is exactly right: an email API whose
   only job is to send email should be able to keep *sending* even when the dashboard and metadata
   store are down. Identify the one function customers cannot lose, and give it an independent path.

---

## A safe-migration playbook for small teams

Distilled from this incident, GitLab's, and general practice. Most of it costs nothing.

**Access & targeting**
- Production write credentials live only in the deploy pipeline — never on a laptop, never in `.env`.
- Separate roles: `app_rw` (application), `app_ro` (humans, analytics), `migrator` (CI only).
- Every destructive command requires an explicit, non-defaulted environment argument.

**Schema changes**
- **Expand → migrate → contract**, never a single breaking step:
  1. *Expand*: add the new column/table, nullable, no constraint. Deploy.
  2. *Migrate*: dual-write, backfill in batches, verify counts match.
  3. *Contract*: switch reads, then drop the old column — **in a later, separate deploy**.
- **Never drop a column in the same release that stops writing to it.** Leave it dead for a
  release cycle so a rollback doesn't lose data.
- Backfill in **batches with sleeps**, never one transaction over a large table — long
  transactions block `VACUUM` and hold locks (see [06](06-the-single-database.md)).
- On Postgres, always set a `lock_timeout` on migrations, so a migration that can't get its lock
  fails fast instead of queueing behind — and blocking — every query on the table.

**Safety net**
- Take an explicit **pre-migration snapshot**, and record its identifier in the deploy log.
- Know your **restore duration** and whether you can meet your RTO with it.
- Rehearse: restore last night's backup into a scratch environment on a schedule, automatically.

**Deletes**
- **Soft-delete anything customer-owned** (`deleted_at`), with a reaper job on a long grace period.
  See the Atlassian case in `../aws-production-issues/06-automation-blast-radius.md` — this one
  column is the highest-leverage defence in this entire library.

## Sources

- [Resend — Incident report for February 21st, 2024](https://resend.com/blog/incident-report-for-february-21-2024) *(primary)*
- [Hacker News discussion of the Resend incident](https://news.ycombinator.com/item?id=39476446)
- [Resend — Incident report for January 10, 2024](https://resend.com/blog/incident-report-for-january-10-2024) *(primary; same team, different failure)*
