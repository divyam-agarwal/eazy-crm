# 09 — Async jobs, queues & email

> **Composite pattern**, anchored on one concrete, documented failure: the email delivery problem
> that hid GitLab's broken backups for months.

The defining property of this class: **the failure is silent**. Nothing 500s, no dashboard turns
red, and the user simply doesn't get the thing they were promised. You find out from a support
ticket, weeks later — or, as in GitLab's case, during the disaster the silent failure enabled.

---

## The anchor case: GitLab's failure notifications that were silently rejected

From GitLab's [January 2017 postmortem](01-data-loss-and-untested-backups.md):

> **Why did the backup procedure fail silently?** — Notifications were sent upon failure, but
> because the emails were rejected there was no indication of failure. The sender was an
> automated process with no other means to report any errors.
>
> **Why were the emails rejected?** — Emails were rejected by the receiving mail server due to
> the emails not being signed using **DMARC**.

Read that again, because it is the whole lesson of this file. The monitoring *worked*. The alert
*fired*. The email *was sent*. And it was **silently dropped by the receiving mail server for a
DNS/authentication reason nobody was checking** — for long enough that when the disaster came,
the backups had been failing for an unknown period.

**A notification channel is a production dependency, and it can fail silently in both directions.**

---

## 1. Email deliverability — the invisible dependency

Transactional email is where most small SaaS products deliver their most critical actions:
password resets, OTPs, invoices, invitations, alerts. It is also the least monitored part of
the stack.

**How it fails silently:**
- **Missing or wrong SPF / DKIM / DMARC records** → mail is rejected or silently binned. Adding a
  new sending provider without updating SPF is the classic version.
- **DMARC policy at `p=reject` without aligning all senders** → your own alerts, invoices and
  password resets stop arriving.
- **Shared-IP reputation damage** — one bad actor on your provider's shared pool, or your own
  bounce rate creeping up, and delivery quietly degrades.
- **Sending to invalid addresses** — high bounce rates get you throttled or suspended by the
  provider, usually with an email you also don't receive.
- **Provider sandbox / warm-up limits** — a new account is often capped or restricted until
  verified; the cap is hit exactly at launch.
- **SMTP retries** — most servers retry for **24–72 hours** before giving up. Resending manually
  during that window **creates duplicates**, because the original is still queued.

**Fixes:**
- Configure **SPF, DKIM and DMARC properly** for every sending domain and subdomain, and
  **re-verify after adding any new sending service**. Monitor DMARC aggregate reports.
- **Track delivery, not sending.** `250 OK` from your provider's API means *accepted*, not
  *delivered*. Consume the provider's delivery/bounce/complaint webhooks and store the outcome.
- **Alarm on the rates**: bounce rate, complaint rate, and — most importantly — **a drop in
  successful deliveries**. Absence is the signal.
- **Never let email be the only path** for anything critical. Password reset should have an
  alternative; alerting should have a second channel (a chat webhook, a pager) that does not
  share a failure mode with email.
- **Separate your domains**: transactional mail on one subdomain, marketing on another, so a
  marketing campaign's complaint rate can't destroy password-reset delivery.
- Suppress and clean bounced addresses automatically.

---

## 2. Background jobs — at-least-once, and side effects that aren't transactional

Every job runner worth using (Sidekiq, Celery, BullMQ, SQS consumers, Cloud Tasks) is
**at-least-once**. Same semantics as webhooks in
[05](05-payments-webhooks-and-idempotency.md), same consequences:

> If a job completes its side effect and then crashes before recording success, the retry performs
> the side effect **again**. The system sees a failure; the real world has already changed.

**The failure catalogue:**

| Failure | Mechanism | Fix |
|---|---|---|
| **Duplicate side effects** | retry after a partial success | idempotency key + unique constraint, inside the transaction |
| **Retry storms** | **fixed-delay** retries create a synchronised wave against a service that is already struggling | **exponential backoff with full jitter**, plus a retry budget |
| **Poison messages** | one permanently-failing job retried forever, consuming the whole worker pool | max attempts → **dead-letter queue**, and *alarm on DLQ depth* |
| **Silent job loss** | job enqueued inside a transaction that rolls back; or enqueued referencing a row that isn't committed yet | **transactional outbox**: write the job to a table in the same transaction, a relay publishes it |
| **Unbounded queue growth** | producers outpace consumers; nobody watches | alarm on **queue depth and oldest-message age** |
| **Jobs that never run** | worker deployment silently died; queue name typo; wrong Redis DB | **heartbeat job** — a job enqueued every minute that alarms if it doesn't execute |
| **Head-of-line blocking** | one slow tenant's 50,000 queued jobs starve everyone else | separate queues by priority and by tenant class; cap per-tenant concurrency |
| **Lost work on deploy** | workers SIGKILLed mid-job | graceful shutdown, `SIGTERM` handling, short visibility timeouts, idempotency |

**The three metrics that catch nearly all of it:**
1. **Queue depth** per queue.
2. **Oldest message age** per queue — better than depth, because it catches a stalled consumer
   that a shallow-but-stuck queue would hide.
3. **DLQ depth**, alarmed at > 0.

**The transactional outbox, since it solves the most insidious one:**

```
BEGIN;
  INSERT INTO orders (...) VALUES (...);
  INSERT INTO outbox (topic, payload) VALUES ('order.created', ...);
COMMIT;
-- a separate relay polls `outbox`, publishes, marks sent
```

The job cannot exist without the row, and the row cannot exist without the job. It removes the
entire class of "we enqueued a job for a record that was never committed" and its mirror image.

---

## 3. Third-party API rate limits and integration failures

A CRM, an accounting integration, a WhatsApp/SMS sender — anything that syncs with someone else's
API — fails in ways that are easy to miss:

- **Rate limits (429)** hit during bulk operations: an import, a nightly sync, a backfill. Naive
  retries make it worse. **Respect `Retry-After`**, back off exponentially with jitter, and use a
  **token-bucket limiter on your side** so you never exceed the documented rate in the first place.
- **Partial success in bulk calls** — 90 of 100 records succeeded. If your code treats the response
  as all-or-nothing you will either lose 90 or duplicate them on retry. Record per-item outcomes.
- **Silent schema/API changes** — a field becomes optional, an enum gains a value. Log and alarm on
  parse failures rather than swallowing them.
- **Expired OAuth refresh tokens** — the integration stops syncing and nobody notices for weeks.
  **Alarm on "last successful sync age" per connected account**, not on error rate.

> **The recurring theme in every section above: alarm on the *absence of success*, not on the
> presence of errors.** Errors are the easy case — something told you. The failures in this file
> are all cases where nothing told you anything at all.

## Sources

- [GitLab — Postmortem of database outage of January 31](https://about.gitlab.com/blog/postmortem-of-database-outage-of-january-31/) *(primary; the DMARC finding)*
- [DMARC.org — Overview](https://dmarc.org/overview/) *(primary)*
- [Background job retry policy checklist: how to prevent queues from amplifying production failures](https://www.momentslog.com/development/background-job-retry-policy-checklist-how-to-prevent-queues-from-amplifying-production-failures)
- [AWS — Exponential backoff and jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/) *(primary)*
- [microservices.io — Transactional outbox pattern](https://microservices.io/patterns/data/transactional-outbox.html)
