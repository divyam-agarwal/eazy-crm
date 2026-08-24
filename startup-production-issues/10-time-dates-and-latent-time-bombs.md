# 10 — Time, dates & latent time bombs

Bugs that are **already in your codebase, already deployed, and already correct-looking**, waiting
for a date. They pass every test, survive every code review, and detonate on a schedule you didn't
set.

> **Note on the anchor case:** the best-documented public example is Microsoft Azure's 2012 leap-day
> outage — a large company. It's used here because the postmortem is unusually precise about the
> mechanism, and because **the bug class hits small teams far harder**: a big provider has thousands
> of engineers and a staged rollout to catch it; a two-person team has neither, and no canary that
> runs a day ahead.

---

## Azure, 29 February 2012 — one date arithmetic error, a global outage

**Impact:** widespread Windows Azure service disruption; ~13 hours to identify the bug, fix it, and
push a new build, with a longer tail.

### What happened

When Azure's **Guest Agent (GA)** initialises a new VM, it creates a transfer certificate and gives
it a one-year validity window: `valid-from` = midnight UTC today, `valid-to` = one year later.

The implementation computed `valid-to` by **taking the current date and adding 1 to its year**.

On 29 February 2012, that produced **29 February 2013** — a date that does not exist. Certificate
creation failed. Every VM whose Guest Agent tried to generate a transfer certificate on leap day
failed to start.

The bug fired at **00:00 UTC on 29 February** (16:00 PST on 28 February) — as soon as the first
new VMs came up on the leap day.

### The cascade — where the real damage came from

The initial bug only broke *new* VM initialisation. What turned it into a major outage was the
**automated remediation**:

Azure's cluster management saw repeated VM failures on a host, concluded the **physical machine was
broken**, and began **migrating healthy VMs off it onto other hosts**. Those VMs then initialised on
the new host — where the Guest Agent again tried to create a certificate, again failed, and again
looked like hardware failure. The self-healing system **spread the failure across the fleet**.

This is the same shape as the AWS DynamoDB/EC2 cascade and Slack's autoscaler in
`../aws-production-issues/`: **automation reacting correctly to a misdiagnosed signal is how a small
bug becomes a large outage.**

### Transferable lessons

1. **Never do date arithmetic by hand.** `year + 1` on a date is a bug. Use the platform's date
   library (`Period.ofYears(1)`, `dateutil.relativedelta`, `date_add`), which handles Feb 29,
   month-length differences and DST transitions correctly.
2. **Automated remediation must have a sanity check and a rate limit.** "Many VMs failing on this
   host ⇒ the host is broken" was a reasonable heuristic and a wrong conclusion. Any automation that
   moves or removes capacity needs a **velocity cap** and a circuit breaker for "I am doing this a
   suspicious number of times."
3. **Test with adversarial clocks.** Run your test suite with the system clock set to Feb 29, Dec 31
   23:59, a DST spring-forward hour, and a leap second. It costs one CI job and finds real bugs.

---

## The rest of the family

Every one of these is a real, recurring source of small-SaaS incidents.

### Certificate and credential expiry
The most common time bomb of all, and the most preventable. Covered in
[08](08-dns-tls-domains-and-vendors.md): TLS certs, intermediate/root CA expiry, OAuth refresh
tokens, API keys with an expiry, code-signing certificates, domain registrations.

**The fix is always the same:** an inventory with expiry dates, automated renewal, **and monitoring
of the renewal itself**. Automated renewal that has silently stopped renewing is exactly GitLab's
silently failing backup.

### Timezone and DST bugs
- **Storing local time instead of UTC.** Store UTC, convert at the edges. Always.
- **Ambiguous and non-existent local times.** During a DST spring-forward, 02:30 doesn't exist;
  during fall-back, 01:30 happens twice. A scheduled job at that time either doesn't run or runs
  twice — and a "daily at 01:30" billing job that runs twice is a double charge.
- **The server's timezone changing** because someone rebuilt the base image. Pin `TZ=UTC` explicitly.
- **India-specific:** IST is **UTC+05:30** — a *half-hour* offset. Code that assumes whole-hour
  offsets, or that formats offsets as `+05`, breaks. And the Indian **financial year runs 1 April
  to 31 March**, so any "year" bucketing that assumes January–December will silently produce wrong
  reports for exactly the customers who care most.

### Integer and counter exhaustion
Covered in [02](02-undocumented-limits-in-managed-services.md): 32-bit `serial` primary keys hitting
2,147,483,647; Postgres TXID wraparound. Both are timers counting down right now, and neither has a
default alarm.

### Time-bounded logic nobody revisits
- Hardcoded "if year == 2025" branches, trial-period calculations, holiday calendars, tax rates and
  slabs, and **hardcoded future dates** used as sentinels (`9999-12-31` is safe; `2030-01-01` is a bug
  with a date on it).
- **Leap seconds** and clock skew — never compute elapsed time from wall-clock differences; use a
  monotonic clock.
- **Cache TTLs and tokens that all expire together**, producing a synchronised thundering herd.
  Jitter every TTL.

---

## How to actually defend against this class

The problem with time bombs is that normal testing runs at *now*, so it can never find them.
Four cheap practices:

1. **Run the test suite under adversarial clocks in CI** — Feb 29, Dec 31→Jan 1, a DST boundary in
   your users' timezone, and a date several years ahead. One extra CI job.
2. **Keep an expiry inventory.** One file listing every certificate, credential, domain, contract
   and hardcoded date, with its expiry and its owner. Feed it into calendar reminders *and* alerts.
   Cheap, boring, and it eliminates the entire "cert expired" genre.
3. **Graph every counter against its ceiling** — sequences, TXID age, quotas — and alarm at 70%.
4. **Give automated remediation a velocity limit.** The Azure cascade, the Slack autoscaler loop,
   the AWS NLB health-check flapping — all the same failure. Any automation that heals, moves, or
   removes things should refuse to do so more than N times per interval without a human.

## Sources

- [Microsoft Azure — Summary of Windows Azure Service Disruption on Feb 29th, 2012](https://azure.microsoft.com/en-us/blog/summary-of-windows-azure-service-disruption-on-feb-29th-2012/) *(primary)*
- [High Scalability — The Azure Outage: Time Is a SPOF, Leap Day Doubly So](https://highscalability.com/the-azure-outage-time-is-a-spof-leap-day-doubly-so/)
- [The Register — How a tiny leap-day miscalculation trashed Microsoft Azure](https://www.theregister.com/2012/03/12/azure_leap_day_confirmed/)
- [PostgreSQL — Preventing transaction ID wraparound failures](https://www.postgresql.org/docs/current/routine-vacuuming.html) *(primary)*
