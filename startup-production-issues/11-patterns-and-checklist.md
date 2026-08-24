# 11 — Patterns & checklist

What the cases in this directory have in common, and a checklist sized for a small team.

---

## The nine patterns

### 1. The safety net was never tested, so it didn't exist

GitLab had five backup and replication mechanisms and **zero working ones**. Instapaper's backups
died to the same filesystem limit as production. Resend's first restore completed, then turned out
to be the wrong point in time.

**The organisational root cause, in GitLab's own words: "there was no ownership, as a result nobody
was responsible for testing this procedure."** An unowned safety mechanism decays to zero.
> Restore, on a schedule, automatically, into a real environment. Assign a name to it.

### 2. Alarm on the absence of success, not the presence of errors

This is the single most repeated lesson in the directory:

- GitLab's backup failure emails **were sent and silently rejected** (no DMARC).
- Instapaper had **no metric at all** on the limit that killed them.
- CircleCI learned of a breach **from a customer**, 13 days in.
- Codecov's poisoned script ran for **two months**; a customer found it by checking a shasum.
- Dropped webhook upgrades, undelivered email, and stalled integrations all fail **silently**.

> Every critical process needs a positive heartbeat: *"the newest restorable backup is < 2h old,"*
> *"emails delivered in the last hour > 0,"* *"last successful sync per account < 24h."*

### 3. The limit with no metric

Instapaper's 2 TB ext3 ceiling. `max_connections`. 32-bit sequences. TXID wraparound. Docker Hub's
100 pulls per IP per 6 hours. Free-tier quotas.
> For every hard limit you can name, put the current value and the ceiling on one graph, alarm at 70%.
> The dangerous ones are the ones nobody knew to graph.

### 4. Guardrails belong in the system, not in the operator

| Incident | The fix that was *rejected* | The fix that worked |
|---|---|---|
| GitLab 2017 | "check which host you're on" | **`PS1` differs per environment**; monitoring on backups |
| Resend 2024 | "be careful with migrations" | **remove write privileges from dev-reachable roles** |
| Codecov 2021 | "don't commit secrets" | **squashed/multi-stage images**; signed binaries |
| CircleCI 2023 | "don't get malware" | **short-lived OIDC creds**; scoped GitHub Apps; JIT access |

> Procedural correctness fails at scale, 100% of the time, eventually. Structural correctness
> fails closed.

### 5. At-least-once is the default, and nobody codes for it

Webhooks, background jobs, client retries, message queues — all at-least-once, all unordered.
> If a caller can retry, the operation must be idempotent, and the only reliable place to enforce
> idempotency is a **unique constraint in the database, in the same transaction as the effect.**

### 6. Recovery time is the outage

GitLab: 18 hours of it was **copying data over a 60 Mbps throttled disk**, a limit created by a
cost decision about *staging*. Resend: 11 of 12 hours were restore, six of them wasted on the
wrong backup. Instapaper: **10 hours minimum even with perfect execution.**
> Time a real restore. Write the number in the runbook. Then decide if you can live with it.

### 7. Automation amplifies a misdiagnosed signal

Azure's cluster manager concluded "the host is broken" and spread the failure across the fleet.
> Any automation that heals, moves, or removes capacity needs a **velocity limit** and a circuit
> breaker for "I am doing this suspiciously often."

### 8. The bill is an incident with a 30-day detector

Cara: $2,000/month → ~$100k in a week, with no bug and no outage. Usage-based pricing plus
autoscaling plus no cap turns any traffic event into unbounded spend.
> Billing alerts to a pager. Rate limits at the edge. **Unit cost** (per user, per request) as the
> metric — total spend only ever goes up.

### 9. Your architecture includes everyone else's

Notion was taken down by a **registrar**. Docker Hub's pricing change broke everyone's deploys.
A CA expiry broke everyone's TLS. None were fixable from inside.
> Enumerate every vendor. One line each: what breaks, how fast you notice, what you do.

---

## The checklist

Ordered so that the highest-value items come first. A small team can do the whole "must have"
section in a couple of days.

### Must have (do these first)

- [ ] **Automated restore test** into a scratch environment, on a schedule, with an alert if it
      fails or if the restored data looks wrong. This is item #1 for a reason.
- [ ] **Backup freshness alarm** — "newest restorable backup is older than N hours" pages someone.
- [ ] **Backups are independent of production's failure modes** — different storage, different
      region, and at least one *logical* (dump) backup, not just snapshots.
- [ ] **Restore duration measured and written down**, and checked against what you can survive.
- [ ] **Developer machines cannot write to production.** Separate roles; production credentials
      only in the deploy pipeline.
- [ ] **Production is visually unmistakable** — shell prompt, colour, hostname, a confirmation
      prompt on destructive commands.
- [ ] **Soft delete on everything customer-owned**, with a grace period before the reaper runs.
- [ ] **Billing alerts at several thresholds, routed to a phone.** Plus spend anomaly detection.
- [ ] **Rate limits at the edge**, per IP and per account, on every public endpoint.
- [ ] **Webhook and job handlers are idempotent** via a unique constraint inside the transaction.
- [ ] **Webhook signatures verified** on every inbound endpoint.
- [ ] **Connection budget written down**: `instances × pool_size` vs `max_connections`, alarmed at 70%.
- [ ] **`statement_timeout`, `lock_timeout`, `idle_in_transaction_session_timeout`** all set.
- [ ] **Certificate and domain expiry monitoring**, including auto-renewal *working*, not just configured.
- [ ] **No long-lived cloud access keys.** Workload identity / OIDC for CI. Secret scanning in CI.
- [ ] **A second notification channel** that doesn't share a failure mode with email.

### Should have (next)

- [ ] PgBouncer / connection pooler in front of the database.
- [ ] `pg_stat_statements` on; weekly review of the top queries by total time.
- [ ] Alarms on: longest query, longest transaction, lock waits, `age(datfrozenxid)`, disk free,
      inactive replication slots.
- [ ] Queue depth, **oldest message age**, and DLQ depth alarmed.
- [ ] A **heartbeat job** — enqueued every minute, alarms if it doesn't execute.
- [ ] Exponential backoff **with full jitter** and a retry budget on every outbound call.
- [ ] Email: SPF/DKIM/DMARC correct for every sender; delivery/bounce/complaint webhooks consumed;
      alarm on a **drop** in deliveries.
- [ ] Expand→migrate→contract for every schema change; never drop a column in the release that
      stops writing to it.
- [ ] Container images mirrored/cached so deploys and **rollbacks** don't need the public internet.
      Pin by digest.
- [ ] Own the `.com`; registrar lock on; renewal on a shared corporate card with alerts.
- [ ] A read replica for reporting and exports.
- [ ] Per-tenant quotas on expensive operations; per-tenant p99 latency visible.
- [ ] Adversarial-clock CI job (Feb 29, DST boundary, year+N).
- [ ] Expiry inventory file: every cert, credential, domain, contract — with owner and date.
- [ ] Status page hosted **off** your own infrastructure.

### Once you have customers who'd sue you

- [ ] Just-in-time production access with approval and audit, instead of standing access.
- [ ] Tested full-credential-rotation runbook.
- [ ] Selective per-tenant restore tested — "restore these 3 customers while everyone else keeps working."
- [ ] Nightly reconciliation against the payment provider.
- [ ] A documented degraded mode for the one function customers cannot lose.
- [ ] Second provider configured (unused but switchable) for payments and transactional email.

---

## What this means for a multi-tenant B2B SaaS like this repo's EasyCRM

In priority order, drawing on both this directory and `../aws-production-issues/`:

1. **Automated restore test + backup freshness alarm.** Nothing else on this list matters if this
   one is missing. (GitLab)
2. **Developer credentials cannot write to production**, and production is visually distinct. Two
   afternoons, eliminates the Resend and GitLab incidents outright.
3. **Soft-delete every customer-owned entity.** One column, one reaper job.
4. **Idempotency on every webhook and job** — payment gateway callbacks, WhatsApp/SMS status
   callbacks, order-confirmation emails. A duplicate order in a CRM is a real business error.
   Enforce it with a unique constraint, per the money rule already in `CLAUDE.md`.
5. **`tenant_id` as part of every primary key and the prefix of every index**, now, while it's free.
   Notion's stated regret; it makes future sharding a schema convention rather than a migration.
6. **Postgres hygiene from day one**: `pg_stat_statements`, `statement_timeout`, `lock_timeout`,
   `age(datfrozenxid)` alarm, connection budget. These are the failure modes that will actually
   take EasyCRM down before scale ever does.
7. **Alarm on unexpected absence** — a spike in empty result sets or 404s is the exact signature a
   tenant-isolation bug produces (the Monzo lesson), and a drop in deliveries is the signature of
   email breaking.
8. **Per-tenant rate limits and quotas** on imports, exports and report generation — the noisy
   neighbour is the multi-tenant failure mode.
9. **Billing alerts and a rate limit at the edge** before any public launch.
10. **IST is UTC+05:30 and the Indian financial year starts 1 April.** Both will silently corrupt
    reports for exactly the customers who care most.

## Sources

Primary sources, consolidated:

- [GitLab — Postmortem of database outage of January 31, 2017](https://about.gitlab.com/blog/postmortem-of-database-outage-of-january-31/)
- [Instapaper — Outage Cause & Recovery](https://medium.com/making-instapaper/instapaper-outage-cause-recovery-3c32a7e9cc5f)
- [Resend — Incident report for February 21st, 2024](https://resend.com/blog/incident-report-for-february-21-2024)
- [Cara — Finances & the Future of Cara](https://blog.cara.app/blog/finances-and-future-of-cara)
- [Codecov — Post-Mortem / Root Cause Analysis (April 2021)](https://about.codecov.io/apr-2021-post-mortem/)
- [CircleCI — Incident report for January 4, 2023](https://circleci.com/blog/jan-4-2023-incident-report)
- [Let's Encrypt — DST Root CA X3 Expiration](https://letsencrypt.org/docs/dst-root-ca-x3-expiration-september-2021/)
- [Microsoft Azure — Windows Azure Service Disruption on Feb 29th, 2012](https://azure.microsoft.com/en-us/blog/summary-of-windows-azure-service-disruption-on-feb-29th-2012/)
- [TechCrunch — Notion's outage was caused by phishing complaints](https://techcrunch.com/2021/02/15/notions-hours-long-outage-was-caused-by-phishing-complaints)
- [Dan Luu — A collection of postmortems](https://github.com/danluu/post-mortems)
