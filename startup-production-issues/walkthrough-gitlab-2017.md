# Walkthrough — GitLab.com, 31 January 2017

**A minute-by-minute reconstruction of the most thoroughly documented data-loss incident in
software.** Six hours of production data destroyed, ~18 hours of downtime, five backup mechanisms
that all failed, and a recovery livestreamed to 5,000 people on YouTube.

---

## How to read this document

| Marker | Meaning |
|---|---|
| *(unmarked)* | **Documented** in GitLab's live incident blog, their published postmortem, or their public Google Doc of live notes |
| **[INFERRED]** | Not stated outright, but follows directly from documented facts |
| **[ASSUMPTION]** | Not in the record at all — my judgement, flagged so you don't cite it as fact |

Everything material here is documented. The record is unusually complete because GitLab published
their notes **during** the incident, not after — see [§9](#9-the-transparency-decision).
A short list of what the record genuinely does *not* say is in [§11](#11-what-the-record-does-not-say).

All times are **UTC**. The engineer at the centre of this was in a timezone where 23:00 UTC was
"23:00 or so local time" — **[INFERRED]** UTC or UTC+1. GitLab's postmortem anonymises everyone as
`team-member-1`, `team-member-2`, `team-member-3`; this document follows that convention.

---

## 1. The system, before anything went wrong

Understanding the failure requires knowing four things about the setup:

**The database topology.** GitLab.com ran on **a single PostgreSQL 9.6 primary and a single
secondary in hot-standby**:

- `db1.cluster.gitlab.com` — primary, taking **all** read and write load
- `db2.cluster.gitlab.com` — secondary, **used only for failover**, not for serving reads

GitLab's own postmortem is blunt that this was a known problem: *"In this setup a single database
has to handle all the load, which is not ideal."* They cite three prior incidents caused by `db1`
being a single point of failure, including a November 2016 outage from `project_authorizations`
table bloat.

**Hosting: Azure.** Not AWS — this matters later, because Azure storage-tier decisions dictate the
recovery time.

**A latent configuration landmine.** `max_connections` was set to **8000**. Wildly too high for
Postgres, which forks a process per connection. It had been set *almost a year earlier* and had
worked fine the entire time. Nobody knew it was load-bearing.

**Five backup and replication mechanisms**, all believed to be working:

| # | Mechanism | Intended purpose |
|---|---|---|
| 1 | `pg_dump` → Amazon S3, every 24h | Logical backup / disaster recovery |
| 2 | Cron failure notifications by email | Alerting if #1 breaks |
| 3 | Azure disk snapshots, every 24h | Whole-disk restore |
| 4 | LVM snapshots, every 24h at 01:00 | Copy production → staging |
| 5 | PostgreSQL streaming replication | Failover |

Four of the five were already broken on 31 January. Nobody knew.

---

## 2. 17:20 — the accidental act that saved the company

An engineer took a **manual LVM snapshot** of the production database to load into staging.

The reason was entirely unrelated to what followed: they were preparing to evaluate **`pgpool-II`**
to load-balance read queries away from the overloaded primary, and wanted staging data fresher than
the automatic 01:00 snapshot.

This snapshot is the only reason GitLab did not lose 24 hours of data instead of 6.
[§7](#7-the-recovery) covers what that hinged on.

---

## 3. 18:00–21:00 — the first incident: spam

**18:00.** Spammers began hammering the database by **creating snippets**, making it unstable.

The team responded — correctly, and with escalating force:

- Blocked the spammers **by IP address**.
- Removed a user who was **using a repository as a CDN**, which had resulted in **47,000 IPs
  signing in through a single account**, generating enormous database load.
- Removed users for spamming via snippet creation.

**21:00.** It escalated into **a lockup on writes**, causing user-visible downtime. Many users could
not post comments on issues or merge requests. Getting the load under control took hours.

**The hidden second cause.** GitLab discovered *afterwards* that part of the load was **a background
job hard-deleting a GitLab employee's own account and all associated data.** That employee had been
**reported for abuse by a troll**, and the abuse-report tooling made it too easy to action a report
without inspecting the details, so their account was accidentally scheduled for removal.

So at 21:00, the on-call engineers were fighting a load spike with **two causes**, and could only
see one. **[INFERRED]** — this is why the load didn't subside when the spam was blocked, and why the
next three hours felt like whack-a-mole.

> **Contributing factor #1:** an abuse-handling tool with no friction between "report received" and
> "user and all their data destroyed."

---

## 4. 22:00 — the second incident: replication breaks

**22:00.** A page fires: **database replication has lagged too far behind and effectively stopped.**
`db2` is roughly **4 GB behind**.

The mechanism: the write spike outpaced the secondary, and **the primary recycled the WAL segments
the secondary still needed** before the secondary could consume them. **GitLab was not using WAL
archiving**, so there was no second copy of those segments anywhere.

Once that happens, streaming replication cannot resume on its own. The only path back is to **wipe
the secondary's data directory and re-seed it with `pg_basebackup`.** That is a documented, normal
procedure. It is also the procedure that requires an engineer to type a destructive command against
a database host at 22:00 during an active incident.

### The four obstacles, in order

Each obstacle is small. Their accumulation is the story.

**Obstacle 1 — `db2` refuses to replicate.** `/var/opt/gitlab/postgresql/data` is wiped to ensure a
clean replication. *(Note: this wipe, on the correct host, is expected procedure.)*

**Obstacle 2 — `max_wal_senders` too low.** `db2` won't connect, complaining about
`max_wal_senders` — the cap on concurrent replication clients. `team-member-1` raises it from the
default **3 to 32** on `db1` and restarts PostgreSQL.

**Obstacle 3 — PostgreSQL now refuses to start at all.** It complains about **too many semaphores**.
This is a consequence of `max_connections = 8000` interacting with the raised
`max_wal_senders`. `team-member-1` reduces `max_connections` from **8000 to 2000**, and PostgreSQL
starts.

> Read that again: **during an active incident, at 22:00, on the primary database serving all of
> GitLab.com, an engineer had to guess at and change a core memory-sizing parameter that had been
> silently wrong for a year in order to get the database to start.**

**Obstacle 4 — `pg_basebackup` hangs, silently.** It connects, and then... nothing. No output,
**despite `--verbose` being set.** An engineer runs `strace`; it is blocked in a `poll()` call,
which explains nothing.

**The cruellest fact in the entire incident:** this was **normal behaviour**. `pg_basebackup`
silently waits for the primary to begin sending replication data — and another production engineer
later confirmed **this can take up to 10 minutes.** It was documented neither in GitLab's runbooks
**nor in the official PostgreSQL documentation.**

> **Contributing factor #2:** a tool that is indistinguishable from hung when working correctly.

### The human state — documented, and central

GitLab published this, and it is the most important sentence in the report:

> *"At this point frustration begins to kick in. Earlier this night team-member-1 explicitly
> mentioned he was going to sign off as it was getting late (23:00 or so local time), but didn't
> due to the replication problems popping up all of a sudden."*

An engineer who had **announced they were stopping for the night**, five hours into an incident,
kept going because a new problem appeared. This is not a character flaw. It is the predictable
output of a system with one primary database, one on-call engineer, and no forcing function to
hand over.

---

## 5. ~23:00 — the deletion

`team-member-1` reasons that `pg_basebackup` might be refusing to work because the PostgreSQL data
directory is **present but empty** — a plausible hypothesis, since `pg_basebackup` does normally
refuse to write into a non-empty directory.

They decide to remove the directory again.

**They run it on `db1.cluster.gitlab.com` instead of `db2.cluster.gitlab.com`.**

`rm -rf /var/opt/gitlab/postgresql/data/*` — on the primary.

**23:27** — they notice, "after a second or two," and terminate the command.

**Of roughly 300 GB, about 4.5 GB remains.**

### Why this was possible

Not carelessness. Four documented properties of the environment:

1. **The hostnames differ by one character in the middle of a long string** — `db1.cluster...` vs
   `db2.cluster...`.
2. **The correct action *was* to wipe a data directory.** The command was right; only the host was
   wrong. No amount of "think before you type `rm -rf`" catches this.
3. **Multiple terminals**, and an engineer who had been context-switching between two hosts for over
   an hour. **[INFERRED]** from the procedure being a repeated back-and-forth between primary and
   secondary.
4. **Six hours into an incident, past the point they had planned to stop.**

GitLab's eventual fix targets exactly this, and it is the cheapest item on their whole list:
**change `PS1` across all hosts so production and staging are visually unmistakable.**

**23:30ish** — GitLab.com is taken down deliberately, and the team posts on Twitter:
*"We are performing emergency database maintenance."*

---

## 6. 23:30–00:30 — five backups, five failures

The engineers go looking for a backup, and ask for help in Slack. What they find, in the order they
found it:

### Failure 1 — `pg_dump` to S3: **the bucket is empty**

Not stale. **Empty.**

The mechanism is a beautiful, awful piece of engineering archaeology:

- The backup job runs `pg_dump`. GitLab's Omnibus package supports both PostgreSQL **9.2** and
  **9.6**, and picks which binary to use by reading **`$PGDIR/PG_VERSION`** — a file inside the
  PostgreSQL *data directory*.
- The backup job **ran on a regular application server**, not a database server.
- Application servers have **no PostgreSQL data directory**, so `PG_VERSION` does not exist.
- With no version file, Omnibus **defaults to PostgreSQL 9.2**.
- `pg_dump` 9.2 against a 9.6 server **errors out immediately** — across major versions this is a
  hard refusal.
- The job produced **files only a few bytes in size**, then exited.

For an unknown period — **months, [INFERRED]** from "almost a year" of unchanged config and no
detection mechanism — GitLab's primary backup had been producing empty files.

### Failure 2 — the alerting on failure 1

Cron **did** send failure notifications. By **email**.

Those emails were **rejected by the receiving mail server because they were not signed using
DMARC.**

The sender was an automated process with **no other way to report an error**. The alert fired, was
transmitted, and was silently discarded — every single time, for months.

> This is the single most quoted lesson from this incident: **alert on the presence of a good
> backup, not on the absence of an error.**

### Failure 3 — Azure disk snapshots: **not enabled on the database servers**

They were enabled on the NFS servers holding Git repository data. Not on the DB hosts. GitLab's
postmortem gives the reasoning honestly: they *"assumed that our other backup procedures were
sufficient"* — and separately, restoring an Azure snapshot **across storage accounts can take hours
or days** (in one prior case, **over a week**), so they avoided relying on it.

### Failure 4 — streaming replication: **just destroyed, by hand, minutes earlier**

`db2`'s data directory had been wiped as step one of the repair procedure. The failover copy was
gone before the primary was.

### Failure 5 — older backups possibly reaped

The **Fog gem may have cleaned out older backups** as part of routine retention.

### And the one that worked, by accident

**LVM snapshots.** Working as designed — but designed for *copying production to staging*, not for
disaster recovery. Two existed:

- one from the automatic 01:00 run, **~24 hours old**
- **one taken manually at 17:20, ~6 hours old** — the `pgpool-II` snapshot from [§2](#2-1720--the-accidental-act-that-saved-the-company)

GitLab's own summary:

> *"So in other words, out of five backup/replication techniques deployed none are working reliably
> or set up in the first place."*

---

## 7. The recovery

### The complication nobody wanted at 01:00

The staging copy is **sanitised**: the production→staging sync **removes all webhooks** so that
staging can't fire real HTTP callbacks at customers. Correct, sensible, and it means the restorable
copy is missing a production table.

So the recovery needed **two** databases built from the same snapshot: one sanitised (the main
restore) and one raw (to recover `web_hooks` from).

### Documented recovery steps

| Time (UTC) | Action |
|---|---|
| 01 Feb 00:36 | Back up `db1.staging.gitlab.com` data *(back up the backup before touching it)* |
| 01 Feb 00:55 | Mount `db1.staging.gitlab.com` on `db1.cluster.gitlab.com`; begin copying staging `/var/opt/gitlab/postgresql/data/` → production |
| 01 Feb 01:05 | `nfs-share01` commandeered as temporary storage at `/var/opt/gitlab/db-meltdown` |
| 01 Feb 01:18 | Remaining production data, including `pg_xlog`, tarred as `20170131-db-meltodwn-backup.tar.gz` *(the typo is in the original)* |
| ~01 Feb 01:18 – 17:00 | **~18 hours of copying** |
| 01 Feb 17:00 | GitLab.com database restored **without** webhooks |
| 01 Feb 18:00 | Webhooks restored from the second database; final checks complete |

They also **incremented every database sequence by 100,000**, so IDs issued during the lost window
could never be silently reassigned to different objects.

### Why the copy took eighteen hours

This is the part most retellings skip, and it is the most transferable lesson in the document.

- The **staging environment ran on Azure classic without Premium Storage** — a deliberate choice to
  save money, because staging doesn't need fast disks.
- Those network disks were **throttled to roughly 60 Mbps**.
- **There was no way to move from cheap storage to premium.**
- GitLab's postmortem is explicit: *"There was no network or processor bottleneck, the bottleneck
  was in the drives."*

They considered copying the LVM snapshot versus copying the PostgreSQL data directory. Same volume
of data either way; restoring the data directory was simpler, so they chose it.

> **A cost decision about the staging environment, made months earlier by someone who was not
> thinking about disaster recovery, set GitLab's recovery time.** Your RTO is determined by
> infrastructure choices you have already made and probably haven't reviewed.

---

## 8. The damage

- **Data lost:** everything written between **17:20 and 23:27** on 31 January — projects, issues,
  merge requests, comments, snippets, user accounts.
- **Estimated:** ~**5,000 projects**, ~**5,000 comments**, ~**700 users**.
- **Not lost:** Git repositories and wikis — stored separately on the NFS servers, which *did* have
  Azure snapshots enabled and were never touched.
- **Not affected:** self-managed GitLab CE/EE instances, GitHost customers.
- **Downtime:** ~18 hours.

**A second-order casualty:** GitLab's public monitoring site, `dashboards.gitlab.com`, **could not
handle the load** from users checking it during the outage. The status surface failed under the
traffic generated by the outage it was reporting.

---

## 9. The transparency decision

While recovering, GitLab:

- Kept **live notes in a publicly visible Google Doc**, linked from Twitter.
- **Livestreamed the recovery on YouTube**, peaking at ~**5,000 concurrent viewers** — briefly the
  **#2 live stream on YouTube**.
- Posted continuous updates via `@gitlabstatus`.
- Published a live-updating blog post the same night, and the full postmortem on 10 February.

The response was `#hugops` — an outpouring of industry support rather than mockery. GitLab thanked
people for it explicitly.

**One retraction they made:** the Google Doc was initially internal and **contained the name of the
engineer who ran the command.** The engineer had added it themselves and had no objection to it
being public, but GitLab stated they would **redact names in future**, since other engineers might
not be comfortable with that.

> This is a real, non-obvious lesson: **an individual's consent to being named during a crisis is
> not freely given**, and the policy should protect people from volunteering for exposure they may
> regret.

---

## 10. The postmortem

GitLab used **5 Whys**, splitting the event into two problems. Reproduced in full, because the
second chain is the more valuable one:

### Problem 1: GitLab.com was down for ~18 hours

| Why? | Answer |
|---|---|
| Why was GitLab.com down? | The primary's data directory was removed by accident, instead of the secondary's |
| Why was the directory removed? | Replication stopped, requiring the secondary to be reset/rebuilt. This requires an empty data directory. **Restoring this required manual work as it was not automated, nor properly documented** |
| Why did replication stop? | A load spike caused the primary to remove WAL segments before the secondary could replicate them |
| Why did load increase? | **Two simultaneous events**: a spam increase, and a job removing a GitLab employee and their data |
| Why was an employee scheduled for removal? | They were reported for abuse by a troll. **The abuse-report system makes it too easy to overlook the details of those reported** |

### Problem 2: restoring took over 18 hours

| Why? | Answer |
|---|---|
| Why did restoring take so long? | It had to be done from a staging copy, on **slower Azure VMs in a different region** |
| Why was staging needed? | **Azure disk snapshots weren't enabled for DB servers, and `pg_dump` backups weren't working** |
| Why not fail over to the secondary? | Its data had been wiped as part of restoring replication |
| Why not use the standard backup? | `pg_dump` **failed silently** — 9.2 binaries against a 9.6 database |
| Why did it fail silently? | Failure notifications were sent by email, and the emails were rejected |
| Why were the emails rejected? | **They weren't signed using DMARC** |
| Why were Azure snapshots not enabled? | They assumed other procedures were sufficient, and restores can take days |
| **Why was the backup procedure not tested regularly?** | **"Because there was no ownership, as a result nobody was responsible for testing this procedure."** |

**That final answer is the root cause of the entire incident.** Every technical failure above is a
symptom of one organisational fact: no person owned verifying that the recovery path worked.

### The action items

From the postmortem's published issue list:

- **Prometheus monitoring for backups** (#1095) — positive-signal monitoring, replacing email alerts
- **Investigate point-in-time recovery & continuous WAL archiving** (#1097) — fixes both the
  replication break *and* the 6-hour loss window
- **Hourly LVM snapshots** of production databases (#1098)
- **Azure disk snapshots enabled** for database servers
- **Set `max_connections` to a sane value** (#1096)
- **Update `PS1` across all hosts** to clearly differentiate hosts and environments (#1094)
- A public tracking issue (#1684) for the status of every item above

Note the shape: **six of seven are structural or automated. Exactly zero are "be more careful."**

---

## 11. What the record does not say

Being explicit about the gaps, so nothing here is over-claimed:

- **How long the `pg_dump` backups had been broken.** Never stated. The only anchor is that
  `max_connections = 8000` had been in place "almost a year"; the backup breakage is a separate
  issue with no stated start date.
- **The exact deletion timestamp.** The postmortem says "±23:00" and the termination at 23:27. The
  precise moment `rm` began is not recorded.
- **Whether a runbook for the re-seed procedure existed at all.** The postmortem says the work "was
  not automated, nor was it documented properly," which implies something existed but was
  inadequate — it doesn't say what.
- **Exact data loss.** GitLab says explicitly *"it's hard to estimate how much data has been lost
  exactly"* — the 5,000/5,000/700 figures are their estimate, not a measurement.
- **The engineer's timezone.** "23:00 or so local time" against 23:00 UTC implies UTC or UTC+1.
  **[INFERRED]**
- **Financial and churn impact.** Not published.
- **Whether the two load sources were ever disentangled during the incident**, or only afterwards.
  The report's structure implies afterwards. **[INFERRED]**

---

## 12. The twelve lessons, ranked by value

1. **Alert on the presence of a good backup, not the absence of an error.** *"Newest restorable
   backup is older than 2 hours"* would have caught this months early.
2. **A backup you have not restored is not a backup.** Automate a real restore into a real
   environment, on a schedule.
3. **Assign an owner to every safety mechanism.** GitLab's own root cause. Unowned controls decay to zero.
4. **Your alerting channel is a production dependency that can fail silently.** Have a second
   channel that doesn't share a failure mode with the first.
5. **Make production visually unmistakable.** `PS1`, colour, hostname. Cheapest item on the list.
6. **The correct command on the wrong host is the dominant destructive-ops failure mode.** Design for
   targeting errors, not for recklessness. Compare [Resend 2024](03-migrations-and-destructive-operations.md),
   where the identical class of mistake cost 5 minutes of data instead of 6 hours — purely because
   the recovery path worked.
7. **Recovery time is set by infrastructure decisions you already made.** A staging storage tier
   chosen for cost dictated an 18-hour restore.
8. **Incident fatigue is a technical risk.** An engineer who said they were stopping, didn't. Build a
   hand-off trigger — *"if an incident passes N hours, a second engineer takes the keyboard"*.
9. **Backups must be independent of production's failure modes**, and at least one should be
   **logical** (a dump restores anywhere) rather than a snapshot tied to one substrate.
10. **Tools that hang silently while working correctly cause data loss.** When a tool's success and
    failure look identical, engineers form a wrong hypothesis and act on it.
11. **Audit config values that have "worked fine for a year."** `max_connections = 8000` was a
    tripwire nobody knew was armed.
12. **Publishing fast and completely is the correct move**, both ethically and reputationally — but
    **redact individuals by default**, because consent given mid-crisis isn't free.

---

## Sources

- [GitLab — GitLab.com Database Incident (live blog, 1 Feb 2017)](https://about.gitlab.com/blog/gitlab-dot-com-database-incident/) *(primary; written during the incident)*
- [GitLab — Postmortem of database outage of January 31 (10 Feb 2017)](https://about.gitlab.com/blog/postmortem-of-database-outage-of-january-31/) *(primary)*
- [Hacker News — GitLab Database Incident, live thread](https://news.ycombinator.com/item?id=13537052) *(contemporaneous)*
- [The Register — GitLab.com melts down after wrong directory deleted, backups fail](https://www.theregister.com/2017/02/01/gitlab_data_loss/)

**Related in this library:** [01 — Data loss & untested backups](01-data-loss-and-untested-backups.md) ·
[03 — Migrations & destructive operations](03-migrations-and-destructive-operations.md) ·
[06 — The single database](06-the-single-database.md) ·
[09 — Async jobs, queues & email](09-async-jobs-queues-and-email.md)
