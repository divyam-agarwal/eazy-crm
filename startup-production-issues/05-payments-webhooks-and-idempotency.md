# 05 — Payments, webhooks & idempotency

> **Composite pattern.** This is the most common *correctness* failure in small SaaS. It rarely
> produces a public postmortem — because the symptom is a customer emailing "you charged me
> twice", not an outage — but the mechanics are documented by the payment providers themselves,
> and every billing integration meets them.

The failure is never "the payment API was down." It's that **the delivery semantics of webhooks
are at-least-once and unordered, and almost every first implementation assumes exactly-once and
ordered.**

---

## The three guarantees you don't get

**1. Delivery is at-least-once, not exactly-once.**
Stripe (and every comparable provider) documents that your endpoint **can receive the same event
more than once**. This is not an edge case or a bug — it is the design. A network blip after your
handler commits but before your `200 OK` reaches Stripe produces a retry of an event you have
already fully processed.

**2. Ordering is not guaranteed.**
A later event can arrive before an earlier one. `invoice.payment_succeeded` can land before
`customer.subscription.created`. Any handler with logic like *"when I see X, the state must already
be Y"* is a latent bug.

**3. Your own side effects are not transactional with the outside world.**
The generic version of the same problem, and the reason background jobs share this file's failure
mode: if a job **completes its side effect and then crashes before recording success**, the retry
performs the side effect **again**. The system sees a failure; the real world already changed.

## What goes wrong, concretely

| Symptom | Mechanism |
|---|---|
| **Customer charged twice** | Duplicate `checkout.session.completed`, each triggering a charge or credit grant |
| **Duplicate provisioning** | Two tenants/seats/licences created from one purchase |
| **Duplicate emails** | "Welcome"/"Receipt" sent 2–5 times, once per retry |
| **Wrong subscription state** | Out-of-order `subscription.updated` events; the older one wins and downgrades a paying customer |
| **Silent revenue loss** | Handler throws → Stripe retries → eventually gives up → the payment succeeded but the account was never upgraded. **Nobody notices, because there's no error on your side either** |
| **Replay attack** | Endpoint doesn't verify the signature, so anyone can POST a fake `payment_succeeded` |

The last two are the dangerous ones: they're **silent**. A double charge generates a support
ticket. A dropped upgrade generates a churned customer who never tells you why.

---

## The fixes, in order of importance

### 1. Verify the signature. Always.

Verify the provider's signature header against your endpoint's signing secret **before parsing
the body**, and reject anything that fails. Use the raw request body — many frameworks' JSON
middleware mutates it enough to break verification, which is the usual cause of "signature
verification fails in production but works locally."

### 2. Make handlers idempotent with a database constraint, not with a check

The naive version has a race:

```
if (alreadyProcessed(event.id)) return;   // two workers both read "no"
process(event);                            // both proceed
markProcessed(event.id);
```

Two concurrent deliveries both pass the check. The correct version pushes the guarantee into the
database, where the concurrency control actually lives:

```sql
CREATE TABLE processed_webhook_events (
  event_id    TEXT PRIMARY KEY,          -- the provider's event id
  received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

```
BEGIN;
  INSERT INTO processed_webhook_events (event_id) VALUES (:id);
  -- unique violation ⇒ already handled ⇒ ROLLBACK and return 200
  ...do the business effect in the SAME transaction...
COMMIT;
```

The insert and the effect commit **atomically**. A duplicate delivery fails the `INSERT`, and you
return `200 OK` — deliberately, because "I have already handled this" is a success, not an error.

For effects that *cannot* be in the same transaction (charging a card, sending an email, calling a
third-party API), use the provider's **idempotency key** on the outbound call — Stripe supports an
`Idempotency-Key` header specifically so that a retried charge request returns the original result
instead of creating a second charge.

### 3. Treat events as triggers to reconcile, not as instructions

The robust design for out-of-order delivery: **don't apply the event's payload as a delta.** Use
the event only as a signal that something changed, then **fetch the current state from the provider
and reconcile.**

```
on webhook(event):
    subscription = stripe.subscriptions.retrieve(event.data.object.id)  # authoritative, current
    upsertLocalSubscription(subscription)
```

Now event ordering is irrelevant — every event converges on the provider's current truth. This one
change eliminates an entire bug class.

If you must apply payloads directly, store the event's `created` timestamp / version and **discard
anything older than what you've already applied**.

### 4. Acknowledge fast, process asynchronously

Return `200` as soon as you have *durably persisted the raw event*; do the work in a background
job. Providers time out webhook endpoints (Stripe's is short), and a slow handler causes retries —
which is how a slow database turns into a duplicate-charge incident. Persist first, then process.

### 5. Monitor the things that fail silently

- **Alert on webhook handler failures**, and on the provider's own delivery-failure dashboard.
- **Reconcile on a schedule**: a nightly job comparing local subscription state against the
  provider's. It will find drift. Everyone's does.
- **Alert on unexpected absence** — a day with zero `payment_succeeded` events processed is an
  incident, even though nothing errored. (Same lesson as Monzo's "row not found" alerting in
  `../aws-production-issues/03-databases-on-aws.md`.)

### 6. Test the retry path

Replay the same event twice in staging and assert the second is a no-op. Send events out of order
and assert convergence. Kill the process mid-handler and assert the retry is safe. These three
tests catch essentially every bug in this file.

---

## The same pattern beyond payments

The identical reasoning applies to every at-least-once system a small SaaS touches:

- **Background jobs** (Sidekiq, Celery, BullMQ, SQS consumers) — a job that sends an email and then
  crashes will send it again on retry. See [09](09-async-jobs-queues-and-email.md).
- **Inbound webhooks from any vendor** — Slack, GitHub, Twilio, WhatsApp Business, payment gateways.
- **Client retries** — a mobile client on a flaky connection re-submitting a "create order" request.
  The fix is the same: the client generates a request ID, the server enforces uniqueness on it.
- **Message queues** — SQS standard queues, Kafka without transactional writes.

> **The general rule: if a caller can retry, the operation must be idempotent — and the only
> reliable place to enforce idempotency is a unique constraint in the database, inside the same
> transaction as the effect.**

## Sources

- [Stripe Docs — Webhooks: handle duplicate events](https://docs.stripe.com/webhooks) *(primary)*
- [Stripe Docs — Idempotent requests](https://docs.stripe.com/api/idempotent_requests) *(primary)*
- [Three idempotency patterns for Stripe webhooks to prevent double billing](https://zenn.dev/kg_filled/articles/9cc7c0c1d85bf1?locale=en)
- [Webhook reliability patterns every SaaS learns the hard way](https://www.augerelabs.com/blog/webhook-reliability-patterns-for-saas)
- [Stripe webhook problems for SaaS billing and how to fix them](https://www.kelviq.com/blog/stripe-webhooks-saas-billing/)
