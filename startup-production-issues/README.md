# Startup & Small-SaaS Production Issues

Real production failures at **small companies and small SaaS products**, with the concrete
root cause, the recovery, and the fix. Compiled 2026-08-22.

This is the companion to `../aws-production-issues/`, and the failure taxonomy is genuinely
different. Big companies fail from **scale and coordination**. Small companies fail from
**single points of failure, safety nets that were never tested, limits nobody knew about,
and pricing models**.

## Sourcing standard

Every case study below is built from a **first-party incident report** or an equally
authoritative source, and the specific numbers, timestamps and error conditions are quoted
from it. Where a pattern is real and common but has no single famous postmortem behind it
(webhook idempotency, connection exhaustion), the file says **composite pattern** at the top
and does not attribute it to any company.

Two entries use a large-company incident to illustrate a bug class that hits small teams
hardest — they are labelled as such, and the reason is given.

## Index

| # | File | Issue class | Anchor case |
|---|------|-------------|-------------|
| 01 | [Data loss & untested backups](01-data-loss-and-untested-backups.md) | Recovery that doesn't exist | **GitLab, Jan 2017** — all 5 backup methods broken |
| 02 | [Undocumented limits in managed services](02-undocumented-limits-in-managed-services.md) | A ceiling with no metric | **Instapaper, Feb 2017** — 2TB ext3 limit |
| 03 | [Migrations & destructive operations](03-migrations-and-destructive-operations.md) | One command, no undo | **Resend, Feb 2024** — local migration hit prod |
| 04 | [Cost & viral growth](04-cost-and-viral-growth.md) | The bill as an existential event | **Cara, 2024** — ~$100k for one week |
| 05 | [Payments, webhooks & idempotency](05-payments-webhooks-and-idempotency.md) | Money correctness | *composite* — Stripe at-least-once delivery |
| 06 | [The single database](06-the-single-database.md) | Everything on one Postgres | *composite* — connections, locks, N+1, VACUUM |
| 07 | [Security, supply chain & credentials](07-security-supply-chain-and-credentials.md) | One key, total compromise | **Codecov 2021**, **CircleCI 2023** |
| 08 | [DNS, TLS, domains & vendor dependencies](08-dns-tls-domains-and-vendors.md) | Someone else's switch | **Notion Feb 2021**, Let's Encrypt 2021, Docker Hub 2020 |
| 09 | [Async jobs, queues & email](09-async-jobs-queues-and-email.md) | Work that silently doesn't happen | *composite* + GitLab's DMARC failure |
| 10 | [Time, dates & latent time bombs](10-time-dates-and-latent-time-bombs.md) | Bugs with a fuse | **Azure leap day 2012** (labelled) |
| 11 | [Patterns & checklist](11-patterns-and-checklist.md) | Synthesis | — |

### Deep-dive walkthrough

| Walkthrough | Why this one |
|---|---|
| [GitLab.com, 31 January 2017](walkthrough-gitlab-2017.md) | The most thoroughly documented data-loss incident in software — GitLab published live notes *during* the incident. A minute-by-minute reconstruction of all five backup failures, the human factors, and the 18-hour restore. Marks **[INFERRED]**/**[ASSUMPTION]** explicitly and lists what the record does not say |

## The short version

If you only take four things from this directory:

1. **A backup you have not restored is not a backup.** GitLab had five mechanisms and zero
   working ones, and the reason was organisational: *nobody owned testing them.*
2. **The limit that kills you is the one with no metric.** Instapaper's database and *all its
   backups* died to a filesystem limit that had no console warning, no alarm, and no doc.
3. **Every destructive path needs an undo.** Resend's entire outage was one migration command
   pointed at the wrong environment.
4. **Your pricing model is part of your architecture.** Cara went from $2,000/month to ~$100,000
   for a single week without a single line of code changing.
