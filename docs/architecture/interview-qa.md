# EasyCRM — Architecture Interview Q&A

Answers assume the **completed** system described in
[`2026-07-29-target-architecture.md`](2026-07-29-target-architecture.md).

**Scope decisions made during this session:**
- Channels are **pull-only** — IndiaMART + TradeIndia. Email ingest and inbound webhooks are
  dropped, and ExportersIndia leaves scope with them (no usable API; those leads arrive via manual
  entry or the CSV import module).
- Consequence: every **lead-channel** table is `@TenantId`-scoped with no carve-out.
- **WhatsApp keeps its webhook** (decided) — WABA has no pull API, so inbound replies and
  delivery/read receipts arrive by webhook or not at all. Razorpay likewise. So the `GLOBAL_TABLES`
  carve-out returns, but narrowed to **one shared, signature-verified webhook inbox** rather than
  being spread across every channel.

---

## Q1 — How are lead-gen channels wired to create enquiries automatically?

### Shape

One `LeadSource` port, one adapter per portal, one shared downstream pipeline.

```java
interface LeadSource {
    ChannelType channel();                        // INDIAMART | TRADEINDIA
    Stream<RawLead> fetch(LeadPullWindow window); // raw provider payload, unparsed
}
```

Both portals are **pull**: IndiaMART's Lead Manager CRM integration issues the seller a CRM key from
their own panel and is polled over a `start_time`/`end_time` window, rate-limited to roughly **one
call per 5 minutes per key**. The poll cadence is imposed by the provider, not chosen.

Onboarding is self-serve: the owner pastes the key in Settings → Channels, we make one validation
call immediately, and reject at save time rather than failing silently at 3 AM.

### Pipeline

```
poll → INSERT channel_message (direction=IN, raw JSONB)   ← store raw FIRST
     → normalize + enrich → dedupe
     → EnquiryService.createFromChannel(...)
     → EnquiryCreatedEvent → assignment + first-contact follow-up + nudge
```

**Store raw before parsing.** A parser bug or a changed provider response must never lose a lead —
that is the one failure a customer notices and churns over. Parsing is a separate, replayable step
over stored rows.

**Idempotency** = unique `(tenant_id, channel, provider_message_id)` + `ON CONFLICT DO NOTHING`.
Enquiry creation only runs on the path where the insert actually inserted. That makes everything
above it safe to be sloppy — notably the **deliberate 5-minute overlap** on each poll window, which
protects against clock skew and requests that succeed server-side but time out on ours. Re-fetching
is free; missing a lead is not.

### Normalization rules

Phone → E.164; GSTIN validated with the existing Luhn-mod-36 checksum. Product interest stays
**free text** — no fuzzy SKU matching, because wrong auto-mapping surfaces later as a mispriced
quotation. Customer matching: GSTIN, then normalized phone; fuzzy name is **surfaced for
confirmation, never auto-merged** (same rule as the import module).

### Failure handling

Resilience4j circuit breaker per provider; exponential backoff + jitter written into `next_poll_at`;
after N consecutive auth failures the credential is marked `INVALID` and a banner is surfaced to the
owner — because the real failure mode is the seller's portal subscription lapsing, not the portal
being down. Silent degradation for three weeks is the outcome being designed against.

---

## Q1a — How is the IndiaMART key protected from the panel to KMS?

Named threat per hop, not "it's encrypted."

| Hop | Threat | Control |
|---|---|---|
| Browser → API | Network intercept; log leakage | TLS 1.3 + HSTS; key in **POST body, never a query param** (query strings land in ALB/nginx logs, browser history, `Referer`) |
| In-request | Accidental logging | `apiKey` on the request-log denylist; DTO `toString()` masked; **validation-error handler strips rejected values** (Spring's default echoes them into the 400 body); never in MDC, metric tags, or span attributes |
| Encryption | — | Envelope: KMS `GenerateDataKey` → AES-256-GCM with a **fresh DEK per credential**, random 96-bit nonce, plaintext DEK wiped |
| At rest | DB dump, replica leak, SQLi, curious DBA | `bytea` ciphertext + wrapped DEK; CMK never leaves the KMS HSM; every `Decrypt` is a CloudTrail event |
| Cross-tenant row tampering | Attacker with DB **write** moves a blob between rows | **AAD = `tenantId\|channel\|column`**, mirrored into the KMS encryption context — the GCM auth tag fails, so tenant A's blob cannot be decrypted in tenant B's row |
| Read-back | Exfiltration via API | No endpoint ever returns it; GET returns `{maskedKey: "••••7c4a", status, verifiedAt}`. No reveal endpoint — lost keys are regenerated in IndiaMART's panel |

**Why envelope rather than direct KMS `Encrypt`:** a 40-char key fits in KMS's 4 KB limit, so direct
would work — but the *decrypt* path runs every poll cycle for every tenant, and KMS throttles per
account. Envelope allows caching the unwrapped DEK in memory with a short TTL.

**Why a fresh DEK per credential:** GCM nonce reuse under one key is catastrophic (leaks plaintext
XOR, enables tag forgery). Rather than manage a nonce counter that a restore-from-backup could
replay, the class of bug is sidestepped: one DEK, one credential, one encryption.

`key_version` on the column allows re-wrapping under a rotated CMK as a background job, no downtime.

**Honest limit:** a fully compromised **app server** can decrypt, because the app must decrypt to
call IndiaMART. What this buys is that the **database stops being a credential store**. That is the
realistic threat model for multi-tenant SaaS; claiming more would be dishonest. Similarly, Jackson
deserializes into an immutable `String`, so heap scrubbing of the plaintext is not achievable —
only the window is narrowed.

---

## Q1b — How does exactly one instance pick up a given tenant?

Two stages. Conflating them is where this goes wrong.

### Stage 1 — the claim: `FOR UPDATE SKIP LOCKED`

```sql
BEGIN;
SELECT tenant_id, channel, cursor_watermark
  FROM channel_poll_state
 WHERE next_poll_at <= now() AND status = 'ACTIVE'
 ORDER BY next_poll_at
 FOR UPDATE SKIP LOCKED
 LIMIT 20;

UPDATE channel_poll_state
   SET next_poll_at = now() + interval '5 minutes',
       locked_by = :instanceId, locked_until = now() + interval '2 minutes'
 WHERE (tenant_id, channel) IN (:claimed);
COMMIT;   -- short: no network I/O inside this transaction
```

`FOR UPDATE` takes a row lock; `SKIP LOCKED` makes a competing transaction **step over** it instead
of blocking. Two instances running this concurrently therefore receive **provably disjoint row
sets** — enforced by the Postgres lock manager, not by application logic that could be subtly wrong.
No Redis, no Redlock (contested under clock skew), no ZooKeeper.

### Stage 2 — the lease

The row lock dies at `COMMIT`, and the poll hasn't started yet. The transaction **must** be short —
never hold a row lock across a 10-second third-party HTTP call, for the same reason PDF rendering
moved outside its transaction.

What provides exclusion for the long operation is the **`next_poll_at` stamped forward inside that
same transaction**: the claim query filters `next_poll_at <= now()`, so the row is invisible to
every other instance for 5 minutes. **Lock for the claim, lease for the work.**

```
t=0     inst-A: BEGIN → lock → stamp next_poll_at=+5m → COMMIT   (~2ms)
t=1ms   inst-B: same query → row invisible
t=0-8s  inst-A: HTTP poll → ingest → advance cursor_watermark
```

**Crash safety.** If A dies mid-poll the row simply isn't polled for 5 minutes; the overlap window
recovers anything missed. `locked_until` + a reaper handles a hang longer than the lease. And if the
lease expires while A is *still running*, two instances poll concurrently — **which is fine**: the
duplicates collide on the unique `provider_message_id` constraint. Idempotency is precisely what
permits a *lease* (wrong under GC pauses and clock skew) instead of true distributed mutual
exclusion, which would be harder to get right.

**Why not ShedLock:** it locks the *whole job*, so one instance runs the poller and processes
tenants serially while the others idle. `SKIP LOCKED` partitions at the *tenant* level — N instances
give ≈N× throughput with zero coordination. Adding a pod is the scaling story.

**Two things the same table gives free:**
- **Rate limiting is the same mechanism.** No two tenants share a CRM key, so a key maps to exactly
  one row, and `next_poll_at = now() + 5min` *is* the quota. No separate token bucket.
- **Backoff + jitter live here.** Without jitter, every tenant that failed during a shared outage
  retries on the same tick forever — a self-inflicted thundering herd against a provider that is
  already struggling.

---

## Q2 — When are follow-ups created, and what triggers each?

**Core distinction:** a *follow-up* is a due-dated commitment row; a *reminder* is the delivery of a
nudge about it. Different triggers, different failure modes — conflating them makes "the reminder
didn't fire" and "the follow-up was never created" indistinguishable in a bug report.

### Three origins

**A — System, event-driven on state transitions.** Defaults per-tenant configurable (a chemicals
distributor and a machine-tools trader have different cycles):

| Trigger | Event | Follow-up | Default due |
|---|---|---|---|
| IndiaMART lead ingested | `EnquiryCreatedEvent` | First contact | +2 business hours |
| Quotation sent | `QuotationSentEvent` | Check response | +3 days |
| Quotation nearing validity | daily sweep | Expiry chase | `valid_until − 2d` |
| Quotation accepted | `QuotationAcceptedEvent` | Confirm dispatch date | +1 day |
| Order dispatched | `OrderStatusChangedEvent` | Delivery confirmation | ETA +1 day |

The SLA clock starts **at ingest, not at first human touch** — portal leads decay in hours, and the
9 PM lead not sitting unseen until Thursday is the reason a distributor pays for this.

**B — Human, at activity-logging time.** The rep logs the call outcome and schedules the next touch
in the same form. The enforcing rule:

> **A follow-up cannot be completed into a vacuum.** Closing one requires either scheduling the next
> touch, or marking the parent enquiry/quotation lost or won with a reason.

"Follow-ups that never leak" is therefore an invariant enforced at the one place leakage happens —
the moment someone ticks *done* — not a discipline problem.

**C — Rule-based staleness sweeps (daily).** Enquiry `NEW` with no activity for N days, quotation
`SENT` with no activity for N days, order overdue against promised dispatch.

### Trigger mechanics

**A** rides the existing Spring `ApplicationEvent` seam — follow-up creation is a **new subscriber,
not an edit** to `QuotationService`. Phase matters:

```java
@TransactionalEventListener(phase = AFTER_COMMIT)
```

If the quotation send rolls back, a follow-up pointing at a document the customer never received
must not exist. But `AFTER_COMMIT` runs outside the original transaction, needs `REQUIRES_NEW`, and
a failure there silently loses the follow-up. The rigorous fix is a **transactional outbox** —
deliberately not built: origin C is required anyway and catches this within a day, so a lost event
costs one day of latency on one follow-up, not a permanently leaked deal. If follow-up SLAs became
contractual, the outbox is where the effort goes.

**B** commits **synchronously in the same transaction** as the activity — the promise to the
customer and its record are one atomic unit.

**C** uses the same per-tenant claim pattern as the pollers: `runAs(tenantId)` + `SKIP LOCKED`.

### The reminder job (every few minutes, per tenant)

```sql
SELECT * FROM follow_up
 WHERE status = 'PENDING' AND due_at <= now() AND reminded_at IS NULL
-- index: (tenant_id, status, due_at)
```

`reminded_at` stamped in the same transaction as the send → a re-run after a crash is idempotent.
It is a **nullable timestamp, not a boolean or a delete**, because snooze clears it so the item
legitimately re-reminds, and "was this person reminded, and when" is a real support question.

Three delivery decisions that are really product decisions:
- **Digest, not per-item** — one "6 follow-ups due today", not six pings. Six pings gets the number
  muted, and a muted channel is a dead channel.
- **Quiet hours** — per-tenant business hours in IST; overdue items roll into the next window.
- **In-app badge always, WhatsApp for the daily digest only** — the badge carries no fatigue cost.

Manager escalation is a **report, not a nag**: overdue-beyond-threshold surfaces on the manager
dashboard, scoped by `VisibilitySpecification` (exec → own, manager → team, owner → all).

### Cancellation cascade — the highest-leverage detail

Enquiry `LOST`, quotation `REJECTED`, or order closed ⇒ **open follow-ups on that entity are
cancelled**, via the same event subscribers in reverse. Once a rep is nagged three times about a
deal they closed last week, they learn the list is wrong and stop opening it — at which point the
reminder system runs perfectly and the product has already failed. **Stale reminders are worse than
no reminders**, so terminal-state cascade is a correctness requirement, not polish. Same reasoning:
reassigning an enquiry reassigns its open follow-ups, or they point at someone who left.

### Where the user meets it

`GET /api/v1/follow-ups/mine` is the mobile home screen. "3 due today" is deliberately the first
thing a sales exec sees; completing is one tap plus the mandatory next-step choice.

---

## Q3 — WABA: capabilities, partner, APIs

### Flag: WhatsApp has no pull API

Dropping webhooks was right for *lead ingest* (IndiaMART and TradeIndia both offer pull). WABA does
not — inbound replies and delivery/read receipts arrive by webhook or not at all. Options: send-only
(lose all status and replies), or send + **one** signature-verified webhook route. **Decided: keep
the webhook** — "customer *read* your quotation 20 minutes ago" is the highest-value signal the
integration produces and feeds Q2's follow-up timing directly. Without it, WABA is a send pipe that
`wa.me` already provides for free.

### Jobs

Deliver the quotation PDF under **the distributor's own number** (their customers know that number —
this is what makes it multi-tenant at the WhatsApp layer, and the hardest part); capture the reply as
an `ACTIVITY`; feed delivery/read status into follow-up timing.

### Direct Cloud API vs BSP → start on a BSP, behind a port

The send API is trivial either way. The hard parts are per-tenant number provisioning, Meta Business
Verification for a tier-2 distributor who has never opened Business Manager, and having someone to
call when a number's quality rating goes RED. A BSP (360dialog / Gupshup — Meta-approved,
India-priced) absorbs all three; direct wins on margin and control only at volume.

```java
interface MessagingProvider {
    SendResult sendTemplate(TenantId t, E164 to, TemplateRef tpl, Map<String,String> vars, MediaRef pdf);
    SendResult sendSession(TenantId t, E164 to, String body);   // inside 24h window
    MediaRef   uploadMedia(TenantId t, byte[] pdf, String mime);
    List<InboundEvent> parseWebhook(String rawBody, String signature);
}
```

`CloudApiProvider` and `BspProvider` both implement it — switching is one bean, the domain never
learns. Same reasoning as `LeadSource`.

### Capabilities used

| Capability | Use |
|---|---|
| Template messages (UTILITY) | Quotation-sent message — required, we initiate outside any window |
| Media / document | The rendered PDF, attached |
| Interactive reply buttons | "Accept" / "Need revision" / "Call me" |
| 24-hour service window | Free-form negotiation after the customer replies |
| Status webhooks | `sent → delivered → read → failed` into `channel_message` |
| Inbound webhook | Replies logged as `ACTIVITY` |
| Quality rating / messaging limits | Surfaced before Meta throttles the tenant |

Not used: catalogs, flows, payments, groups.

### APIs (Cloud API, Graph v21+)

```
POST /{phone-number-id}/messages       template | text | document | interactive
POST /{phone-number-id}/media          upload PDF → media_id
GET  /{media-id}                       resolve inbound media, then download
POST /{waba-id}/message_templates      submit for approval
GET  /{waba-id}/message_templates      poll approval status
GET  /{phone-number-id}?fields=quality_rating,throughput
Webhook field "messages"               inbound + status
Embedded Signup: FB JS SDK → code → per-tenant System User token
```

Per-tenant `waba_id` / `phone_number_id` / token reuse **exactly the Q1a AES-GCM + KMS envelope**,
same AAD binding, same masked read-back — the payoff for building it generically.

### The two state machines that bite

1. **The 24-hour window.** `service_window_expires_at` per contact, refreshed on every inbound. Send
   path branches: open → session message, closed → **must** be an approved template. Wrong ⇒ API
   error `131047`, or silently paying marketing rates. It is a first-class column, never inferred.
2. **Template approval is async and can be rejected.** Own status machine
   (`DRAFT → PENDING → APPROVED → REJECTED/PAUSED`); the send path refuses non-approved templates
   with a clear error; we ship pre-approved UTILITY templates so a new tenant sends on day one.

Meta moved to **per-message** pricing for templates (utility free inside an open service window), so
cost is a function of *when* we send — metering per tenant feeds P3 entitlements.

### Reliability

- **Outbox, never a third-party call inside a transaction** — write `channel_message` `QUEUED`,
  commit, worker sends, stamp the returned `wamid`. Same rule as the poller and the PDF render.
- **Idempotency is ours** — WABA has no idempotency key. Unique constraint on
  `(tenant_id, entity_type, entity_id, template, version)` so a double-tap on flaky 4G sends once.
  Inbound dedupes on `wamid`, making webhook redelivery a no-op.
- **Retry only 5xx/429** with backoff + jitter. Never blind-retry 4xx — `131026` (not a WhatsApp
  user) and `131047` are permanent, and retrying burns quality rating.
- **Media cache** — upload once per quotation *version*, cache `media_id` beside `pdf_object_key`;
  Meta expires media ~30 days, so the cache carries an expiry. The message carries the document
  **and** the share link (document = better UX, link = access analytics + revocability).

### Product judgment: an "Accept" tap does not confirm an order

It logs an activity, notifies the rep, and creates an immediate follow-up. There is no
authentication that the person holding the phone may commit their company to ₹4 lakh of goods — a
button tap is *intent*, not a purchase order. Auto-confirming would be the same category of error as
trusting client-computed totals.

---

## Q4 — Order confirmed: what happens, where it goes, how

### What "confirmed" means

An order is created exactly one way: from an accepted quotation **version**. The order
**snapshots, never references** — lines, rates, discounts, HSN, GST breakup are copied from the
frozen version, not re-derived from the catalog. Accepting a three-week-old quote after a price
change must reflect what was quoted; re-deriving would be a silent repricing whose first symptom is
a wrong PDF. Same snapshot rule quotation items already follow.

### Inside the transaction

1. Validate the version is `SENT`, not expired or superseded.
2. Consume the `Idempotency-Key` — a double-tap on flaky 4G is the normal case. Same key returns the
   *same* order: not a duplicate, not a 409.
3. Allocate `SO/25-26/0041` — **gapless per financial year**.
4. Copy line snapshots. Recompute nothing; the quotation totals are already server-authoritative.
5. `@Version` optimistic lock guards later status transitions.

**Why not a Postgres sequence for numbering:** sequences are deliberately non-transactional, so a
rollback leaves a permanent gap — correct for performance, wrong when an auditor asks where
`SO/25-26/0038` went. `DocumentNumberService` locks a `document_counter` row `FOR UPDATE` on
`(tenant, doc_type, financial_year)`. That serializes issuance per tenant per doc type — a
throughput ceiling accepted knowingly, because a distributor issues tens of orders a day.

### After commit — `OrderConfirmedEvent`

| Subscriber | Does |
|---|---|
| Audit | actor, before/after JSONB, IP |
| WhatsApp | order confirmation template to the customer |
| Activity | `ORDER_CONFIRMED` on the enquiry timeline |
| Follow-up | "confirm dispatch date", +1 day |
| Entitlement | meter the order against the plan (P3) |
| Account 360 | roll into customer lifetime value (P4) |
| Tally bridge | mark the order `PENDING_SYNC` |

None of these edit `OrderService` — that is the point of the seam.

### Three destinations

**1. Internally** — `CONFIRMED → PACKED → DISPATCHED → DELIVERED → CLOSED`, illegal transitions
rejected by an explicit state machine. Each transition publishes `OrderStatusChangedEvent`.

**2. To the customer** — WhatsApp confirmation + order PDF, same outbox/template machinery as Q3.

**3. To Tally** — see below.

### The Tally bridge

**The constraint that determines the design: Tally is a desktop app on the accountant's LAN.** No
public IP, home-grade router, switched off at 6 PM. We are a cloud service. **We cannot push to it.**

Tally Prime does expose an HTTP/XML gateway on port 9000, and the naive design is to POST vouchers to
it — requiring port forwarding on a tier-3 distributor's router, a static IP they don't have, and an
inbound hole into an accounting machine. Unshippable, and a security liability.

| Tier | Mechanism |
|---|---|
| **1 — Export file** (day one, free) | `GET /api/v1/orders/export?format=tally-xml` → importable voucher batch. Dumb, reliable, every Tally version, zero infrastructure |
| **2 — Connector agent** (paid) | Windows service on the accountant's PC **polls us** for `PENDING_SYNC` orders and POSTs XML to `localhost:9000` |

The **direction** is the design: the agent makes outbound HTTPS only — no port forwarding, no
firewall change, works behind NAT/CGNAT. Same reason CI runners and monitoring agents poll. PC off
for a week ⇒ orders queue and drain on return.

Three details that make it work:
- **Idempotency across the bridge** — Tally's voucher XML has a `REMOTEID` field for exactly this;
  we set it to our order UUID, so a retry after a crashed report *updates* rather than creating a
  second voucher. Without it, a flaky retry duplicates sales orders in the customer's books — the
  worst thing this product could do to them.
- **Name mapping** — our "SS 304 Seamless Pipe 2 inch" vs their Tally stock item `SS PIPE 304 2"`.
  Unmapped names create junk masters in Tally, which is unforgivable. An explicit mapping table,
  **populated by the import module** which already read their masters at onboarding — the second
  payoff for building import as a real pipeline rather than an ops script.
- **Sync status visible in the UI** — `SYNCED / PENDING / FAILED` + error text per order. The
  failure mode being designed against is discovering at month-end that 40 orders never reached Tally.

### What we deliberately do NOT do

No invoice, no stock deduction, no ledger posting, no payment entry. We send a **Sales Order**
voucher, not a Sales Invoice: an order is a commitment, an invoice is a taxable event carrying GST
liability, e-invoicing/IRN obligations above the turnover threshold, and e-way bill implications.
Generating one takes on statutory correctness for someone else's compliance and puts us in
competition with the CA who recommends the software. That boundary is why the product is sellable —
"your Tally is untouched" has to be literally true, not a slide.

### Aside — what "entitlement" and "meter" mean

**Entitlement = what the tenant's plan allows.** Three kinds:

| Type | Example | Enforcement |
|---|---|---|
| Seats | Starter = 3 users | Hard block |
| Quota per period | 100 quotations/mo, 5,000 import rows | Soft or hard by metric |
| Feature flags | IndiaMART + Tally agent are Growth-tier | Hard block, upgrade card |

`Plan` holds limits, `Subscription` links tenant→plan, `usage_counter(tenant_id, metric, period)`
holds the count. The guard sits after authz, before the service call, and returns
`402 {code: PLAN_LIMIT_EXCEEDED, limit, used, upgradeUrl}` so the UI can say "100 of 100 used".

**"Meter the order"** = on `OrderConfirmedEvent`, atomically
`UPDATE usage_counter SET used = used + 1 …` — in SQL, not read-then-write in Java, or two
concurrent confirms both read 40 and both write 41. It feeds the usage dashboard, the 80%-of-plan
nudge, and overage on usage-priced plans.

Two nuances:
- **Check before, count after.** The guard runs before the action, the meter on `AFTER_COMMIT` — a
  rolled-back order must not consume quota.
- **Orders are metered, not gated.** Never hard-block an order confirmation on a quota; that blocks
  the customer's revenue to force an upsell. Seats and integrations are hard limits, the
  money-making path is soft-limited with a warning and overage. The check-then-act race (two
  concurrent creates both passing at 99/100) is accepted — being off by one on a soft limit costs
  nothing, a lock on the hot path costs everything.

---

## Q5 — Payments, subscriptions, and how webhooks reach us

### Why Razorpay

Our customer pays ₹2,000–8,000/month and **pays by UPI**. Stripe India gives no practical UPI Autopay
coverage; Razorpay gives UPI, netbanking, RuPay, e-NACH. Card-first billing fails on most of the
target market. **We never touch card data** — hosted/embedded Checkout keeps PAN out of our network
and us out of PCI-DSS scope. `invoice_ref` stores Razorpay identifiers only.

### The India-specific bit

RBI's e-mandate framework: recurring card debits need AFA at registration, a **pre-debit notification
24h before every charge**, and per-transaction ceilings that re-trigger AFA. This is why card-on-file
autorenewal silently fails in India in ways it never does in the US. Practically, **UPI Autopay is
the primary rail**. The consequence for us: mandate authorization is **asynchronous and can fail
hours later**, so `TRIALING → ACTIVE` is never a synchronous response to a button click — it is
always a webhook.

### Model and flow

`Plan` is a **global table** (tenant-independent — a reviewed `GLOBAL_TABLES` entry).
`Subscription` is per-tenant: `TRIALING → ACTIVE ⇄ PAST_DUE → SUSPENDED`, or `→ CANCELLED`.
**No card upfront for the trial** — an Indian SMB will not enter payment details to try software.

1. Owner picks plan → `POST /api/v1/subscription`
2. Razorpay Subscriptions API create → checkout options to the browser
3. Customer authorizes the UPI mandate
4. **`subscription.activated` webhook** → `ACTIVE`, entitlements materialized
5. Cycle: `subscription.charged` extends `current_period_end`; `payment.failed` → `PAST_DUE`

**The client sends a `planId`, never an amount.** Server looks up the price. Accepting a price from
the frontend is the billing equivalent of trusting client-computed GST totals — same class of error,
larger blast radius.

### How webhooks reach us

`POST /public/webhooks/razorpay`, configured in the Razorpay dashboard, HTTPS only. No JWT, no tenant
context; excluded from the auth filter chain and CSRF, **included in rate limiting**. Tenant is
unknown at arrival, so the inbox is global-scoped and tenant is resolved via
`razorpay_subscription_id`; everything after runs inside `runAs`. In dev, Razorpay can't reach
localhost — hence the repo's `backend-restart-ngrok-up` skill.

### Verification — the two details people get wrong

`X-Razorpay-Signature` = HMAC-SHA256 of the body with the webhook secret.

1. **Verify the raw bytes.** Deserializing with Jackson and re-serializing to check the signature
   fails — key order, whitespace and number formatting all change. Capture the body as `byte[]` and
   HMAC exactly what arrived. The most common webhook-handler bug there is.
2. **Constant-time compare** — `MessageDigest.isEqual`, not `String.equals`, which short-circuits on
   first mismatch and leaks signature bytes through response timing.

Verify **before** parsing. Bad signature → 400, log source IP, process nothing.

### Idempotency and ordering

**Idempotency:** unique constraint on `x-razorpay-event-id`, `ON CONFLICT DO NOTHING`, process only
when the insert inserted — identical mechanism to IndiaMART's `provider_message_id` in Q1.

**Ordering is not guaranteed** — `subscription.charged` can precede `subscription.activated`.
Applying transitions in arrival order leaves tenants in wrong or stuck states, and billing bugs
escalate hardest. So: **the webhook is a signal that something changed, not a description of what it
now is.** On any billing event we call Razorpay's API for authoritative state and set ours from that.
Out-of-order delivery becomes harmless — both orderings converge on "go read current state."

### Respond fast, process async

Verify signature → insert into `webhook_event` inbox → return `200`. A worker does the business
logic. Razorpay times out in seconds and retries, so inline business logic creates
timeout → retry → duplicate processing — a feedback loop that worsens under exactly the conditions
where it must work.

### Webhooks are not the only path

A **daily reconciliation job** pulls subscription status from Razorpay for every `ACTIVE`/`PAST_DUE`
tenant and corrects drift. Webhooks get lost — misconfigured URL after a deploy, expired cert,
provider incident, an inbox row that failed processing unnoticed. Sole-source-of-truth means a paying
tenant stays locked out or a cancelled one keeps access. Same belt-and-braces philosophy as Q2's
staleness sweep: the event path is fast, the reconciliation path is correct, the system converges.

### Dunning and suspension

`PAST_DUE` → ~7-day grace with Razorpay's own retry schedule, in-app banner, WhatsApp/email nudges →
`SUSPENDED`. **Suspension is read-only, never destructive** — they can log in, view, and export. DPDP
requires us to hand over their data anyway, and a distributor whose CRM vanished over a failed UPI
mandate never returns, and tells every other distributor in their market.

### The irony

We don't do invoicing for customers, but we need it for ourselves — 18% GST, our GSTIN, a proper tax
invoice so they can claim input credit. That runs through Razorpay Invoices, because it is the one
place invoicing is *our* compliance problem rather than someone else's.

---

## Q6a — Design patterns, mapped to actual code

A pattern that can't be pointed at isn't in the system.

### Structural

| Pattern | Where |
|---|---|
| **Ports & Adapters** | `LeadSource`, `MessagingProvider`, `MasterDataSource`, storage port — domain declares, `infrastructure` implements |
| **Strategy** | Runtime adapter selection: `INDIAMART`/`TRADEINDIA`, `CloudApiProvider`/`BspProvider`, `CsvSource`/`ExcelSource` |
| **Anti-corruption layer** | The `channels` module — IndiaMART's payload never reaches `Enquiry`; their schema change is our adapter change |
| **Repository** | Spring Data JPA |
| **DTO + Mapper** | MapStruct; entities never leave `domain` |
| **Facade** | Published service interfaces `sales` uses for `catalog`/`crm`, enforced by the ArchUnit rule (service, never repository or entity) |

### Behavioural

| Pattern | Where |
|---|---|
| **Observer / Pub-Sub** | `ApplicationEventPublisher`; every Q2/Q4 side-effect is a subscriber, not an edit |
| **State** | Order, quotation, `ImportBatch`, `Subscription` — explicit transition tables; `DELIVERED → CONFIRMED` is rejected, not merely unlikely |
| **Chain of Responsibility** | Import enrichers then validators, ordered; Spring Security filter chain |
| **Template Method** | Import pipeline parse → map → validate → enrich → commit, per-entity hooks |
| **Specification + Composite** | `VisibilitySpecification` AND-composed into every list query |
| **Builder / Factory** | `buildItems`, Lombok `@Builder`; `DocumentNumberService`, provider factory on `ChannelType` |

### Spring idioms

- **Proxy / AOP** — `@Transactional`, `@PreAuthorize`, `@Async`, audit aspect. Trap worth naming:
  **self-invocation bypasses the proxy**, so an internal call gets no transaction — invisible in
  code review.
- **Decorator** — `TenantAwareTaskDecorator` wraps the `Runnable` to copy-then-clear tenant context
  across threads; without it `@Async` work runs tenant-less and RLS returns nothing.
- **Unit of Work** — JPA persistence context, `open-in-view: false` so it closes at the service
  boundary.
- **`@ControllerAdvice`** — one global handler, RFC 7807 `ProblemDetail`, 404-not-403 in one place.

### Distributed / integration

| Pattern | Where |
|---|---|
| **Transactional Outbox** | WhatsApp sends — `channel_message` `QUEUED`, commit, then dispatch |
| **Inbox / idempotent receiver** | `provider_message_id`, `wamid`, Razorpay `event_id` — one mechanism, three integrations |
| **Lease / optimistic claim** | `SKIP LOCKED` + `next_poll_at` (Q1b) |
| **Circuit Breaker · Retry · Bulkhead** | Resilience4j per provider |
| **Envelope encryption** | Q1a, KMS-wrapped DEKs |
| **Agent / polling sidecar** | Tally connector — outbound-only, their LAN is unreachable |
| **Reconciliation loop** | Razorpay daily sync (Q5), staleness sweep (Q2) |

### Deliberately NOT used

- **Saga** — no distributed transactions to compensate. Modular monolith on one Postgres; local
  `@Transactional` is atomic. Sagas would be microservice cost without microservice ownership.
- **CQRS / event sourcing** — reporting uses projections over the same model; write volume is tens
  of orders/tenant/day. Buys audit (the aspect already provides it) at the cost of every developer
  understanding it.
- **Microservices** — module boundaries + ArchUnit are the *extraction seam*; until a module needs
  independent scaling, one deployable.

### Aside — bulkhead

**Resource isolation**, from ship hull compartments. The dangerous case is a dependency going
**slow**, not down — dead fails fast, slow holds threads. IndiaMART at 30s/call on a shared pool
exhausts it in a minute, and login + quotation builder + Razorpay webhooks all die because of a
lead-sync nobody's current request needs.

```yaml
resilience4j.bulkhead:
  indiamart: { maxConcurrentCalls: 5 }
  whatsapp:  { maxConcurrentCalls: 20 }
  razorpay:  { maxConcurrentCalls: 10 }
```

Semaphore flavour (cap concurrency, no extra threads) or thread-pool flavour (real isolation, more
overhead). Also present as: separate `@Async` executors for imports vs channels (a 5,000-row import
must not starve WhatsApp sends), and the poller's `LIMIT 20` batch, which bounds connections
regardless of how many tenants are due.

| Pattern | Bounds | Reacts to |
|---|---|---|
| Timeout | one call's duration | nothing — hard limit |
| Bulkhead | how many calls run at once | nothing — hard limit |
| Circuit breaker | whether to call at all | observed failure rate |

**Why you need the bulkhead even with a breaker:** the breaker only helps after it trips, and a
merely-slow dependency may never produce enough *failures* to trip it at all.

---

## Q6b — Debugging a slow SQL query, step by step

Discipline: **measure → hypothesize → change one variable → re-measure.** Most "fixes" are
index-shotgunning, which adds write cost and misses the real problem.

**0. Establish the fact.** Which endpoint, which tenant, since when, p50 or p99. "The app is slow"
and "one tenant's follow-up list takes 8s" are different investigations. Get a trace id.

**1. Confirm it's the DB at all.** Read the span breakdown — time may be in the app, a third party,
or serialization. **Rule out connection-pool wait first:** `hikaricp.connections.pending` and acquire
time. If threads wait for connections, every query looks slow while none is. Cause is almost always
someone holding a connection across something slow — which is exactly why PDF render moved outside
the transaction and WhatsApp/Razorpay go through an outbox.

**2. Find the statement.** `pg_stat_statements` ordered by `total_exec_time` **and**
`mean_exec_time` — high total + low mean = a fast query run 50,000 times; high mean = one slow
query. `log_min_duration_statement = 500ms`; `hibernate.generate_statistics` for per-request counts.

**3. Is it N+1? — check before EXPLAIN.** In a JPA app this is the likeliest answer and **it doesn't
look like a slow query**, it looks like 200 fast ones. Jumping to `EXPLAIN` finds a perfect plan and
concludes nothing is wrong. Detect via query count per request; fix with `@EntityGraph`,
`join fetch`, `@BatchSize`. `open-in-view: false` already makes this fail loudly instead of hiding
behind view serialization.

**4. `EXPLAIN (ANALYZE, BUFFERS)`** with realistic data and tenant context set. Reading for: seq scan
on a large table; estimated-vs-actual off 10×+ (stale stats → `ANALYZE`, then autovacuum tuning);
nested loop with high `loops`; sort spilling to disk (`work_mem`); BUFFERS read≫hit (working set
doesn't fit cache — sizing, not query); index present but unused (type mismatch or a function
wrapping the column).

**5. The multi-tenant twist.** Three things that wouldn't bite single-tenant:
   - **Every index must lead with `tenant_id`.** RLS adds a tenant predicate to every query, so
     `(due_at)` alone is near-useless. `(tenant_id, status, due_at)` in that order is a decision.
   - **The RLS policy expression must be planner-friendly.** A policy calling a non-`STABLE`
     function can't be treated as constant within the statement, so it may re-evaluate per row and
     won't become an index condition — every query becomes a filtered seq scan and nothing in the
     application code looks wrong.
   - **Tenant skew breaks the planner.** One tenant with 400k enquiries, 900 with 300 each. Postgres
     plans from **table-wide** statistics, so it picks a plan right for the average tenant and
     catastrophic for the whale. Symptom: "only slow for one customer." Ladder: extended statistics
     → query restructuring → partition by tenant (last; big commitment).

**6. Check the pagination wall.** `OFFSET 10000` materializes and discards 10,000 rows to return 20 —
page 500 costs 500× page 1. Fix is already the target: cursor pagination,
`WHERE (created_at, id) < (?, ?) ORDER BY … LIMIT 20`, O(limit) at any depth.

**7. Fix, verify, guard.** Re-run the same measurement, not a hand-timed refresh. Then a **query-count
assertion test** (code review does not catch N+1 reintroduction), a p99 alert on the route, and — if
an index was added — check the write cost, since import writes thousands of rows per batch.

**8. If inherently expensive**, cheapest first: covering index → denormalized rollups (Account 360
already is one) → scheduled materialized view for reports → read replica so analytics can't degrade
the quotation builder.

**What I would not do is cache first.** It hides the problem, adds invalidation bugs, and in a
multi-tenant system a cache-key mistake is a cross-tenant leak — the exact bug class this
architecture exists to make structurally impossible.

---

## Q7 — Observability (AWS-native)

Deployed on **ECS Fargate behind an ALB with Aurora PostgreSQL**. Instrumentation stays Micrometer +
OpenTelemetry — only the export targets are AWS. That vendor-neutrality is the point.

| Concern | Service |
|---|---|
| Logs | CloudWatch Logs + Logs Insights |
| Metrics | CloudWatch Metrics via **EMF** |
| Traces | AWS X-Ray |
| Collector | **ADOT** sidecar |
| Resources | Container Insights |
| Database | **RDS Performance Insights** + Enhanced Monitoring |
| Alerting | CloudWatch Alarms → SNS → Slack/PagerDuty |
| Synthetic | CloudWatch Synthetics canary |
| Frontend | CloudWatch RUM |
| Infra audit | CloudTrail (already carrying KMS `Decrypt` from Q1a) |

### Highest-leverage feature: trace id on every response

`X-Trace-Id` on every response including errors, surfaced in the UI error toast, carried in support
tickets. It collapses "which of 40,000 requests was yours?" into a lookup. The ALB injects
`X-Amzn-Trace-Id`, so the trace starts at the load balancer and we propagate that id into MDC.

### Logs

**MDC** (Mapped Diagnostic Context) is a thread-local `Map<String,String>` that Logback merges into
every line on that thread — set `tenantId`/`userId`/`traceId` once in the filter that establishes
`TenantContext`, so `log.info("Quotation sent")` emits them automatically. **Clear it in `finally`**:
thread pools reuse threads, so a leak labels tenant A's lines with tenant B — same copy-then-clear
discipline as `TenantAwareTaskDecorator`.

**Never logged** (encoder denylist + review rule): **share-link tokens** (a bearer credential —
logging one publishes it), IndiaMART/WABA credentials, JWTs, Razorpay signatures, PII beyond ids.
Rule: **log the identifier, not the entity.**

**CloudWatch Logs retention defaults to Never Expire** — the classic AWS bill surprise. Set
`retention_in_days` explicitly per log group in Terraform (30d app logs), export to S3+Glacier for
longer. The **audit log is unaffected** — it lives in Postgres precisely because it is legally
retained and must not be governed by log retention.

### Metrics — EMF and the cardinality bill

Don't `PutMetricData` per event (API call each, rate-limited, priced). Use **Embedded Metric
Format**: a structured stdout line from which CloudWatch extracts metrics — the metric costs the
price of a log line you were already writing.

```json
{"_aws":{"CloudWatchMetrics":[{"Namespace":"EasyCRM",
   "Dimensions":[["Channel"]],"Metrics":[{"Name":"LeadsIngested","Unit":"Count"}]}]},
 "Channel":"INDIAMART","LeadsIngested":7,"tenantId":"a3f8-..."}
```

`Channel` is a **dimension** (becomes a metric axis). `tenantId` is a **property** — searchable in
Logs Insights, creates no series. That distinction is the whole per-tenant answer: custom metrics
cost ~**$0.30/metric/month per unique dimension combination**, so 1,000 tenants × 10 metrics as
dimensions ≈ **$3,000/month** to monitor an app that costs less to run. Aggregates in dimensions,
tenant identity in properties.

**Business metrics are the tier that earns its keep** — `LeadsIngested`, `QuotationsSent`,
`OrdersConfirmed`, `WhatsappDeliveryRate`, `FollowupsOverdue`. If IndiaMART changes response shape
and our parser yields zero leads: **CPU flat, latency flat, error rate zero** — every infra metric
says healthy, and it *is* healthy, it just isn't doing its job. Only `LeadsIngested == 0` catches it.
Infra metrics say the system is *up*; business metrics say it's *working*.

### Traces — X-Ray sampling rules

Sampling exists because a full trace per request costs more to store than the service costs to run.

- **Head-based** — decide at the root span, propagate via the `traceparent` sampled flag. Cheap, but
  decides *before* knowing whether the request was interesting, so a 10% sample drops 90% of errors.
- **Tail-based** — a collector buffers all spans of a trace and decides after completion, so it can
  keep 100% of errors and slow requests. Correct, but stateful.

**X-Ray sampling rules are centralized and dynamic** — defined in console/Terraform, fetched at
runtime, so **changing sampling needs no redeploy**. The **reservoir** is the good part: guarantee N
traces/sec regardless of volume, then a fixed rate on the remainder — so low-traffic routes aren't
statistically invisible the way a flat 10% makes them.

| Route | Reservoir | Rate |
|---|---|---|
| `/public/q/*` | 1/s | 1% |
| `POST /quotations/*/accept` | 5/s | 100% |
| Scheduled jobs | — | 100% |
| Everything else | 1/s | 10% |

**X-Ray has no native tail sampling** — for "always keep errors" you add the ADOT Collector's
`tail_sampling` processor in front of it, or use CloudWatch Application Signals. Also: the
`TaskDecorator` must propagate **trace** context as well as tenant, or every `@Async` import and
outbox send starts an orphaned trace; store `traceparent` on `channel_message` so the send span links
back to the click that queued it minutes earlier.

### Database — the Q6b answer, AWS edition

**RDS Performance Insights** gives DB load in average active sessions sliced by wait event and top
SQL — the tall bar *is* the offending query, and the wait type says CPU vs IO vs lock.
`pg_stat_statements` and `log_min_duration_statement` via parameter group; `EXPLAIN (ANALYZE,
BUFFERS)` unchanged. The connection-pool-exhaustion misdiagnosis shows up as **`DatabaseConnections`
climbing while `CPUUtilization` stays flat** — a very legible signature.

### Health, alarms, cost

ALB target-group health check hits `/actuator/health/readiness`. **Readiness must not check third
parties** — failing it deregisters targets, converting a Razorpay outage into a total outage of ours.
Composite alarms so one bad deploy fires once. **Anomaly detection**, not static thresholds, for
business metrics — lead volume is diurnal, so "leads < 5/hour" fires every Sunday and gets muted
within a week. Synthetics canary runs login → view quotation every 5 min from outside the VPC.

Skipping **AMP + Managed Grafana** initially: CloudWatch is already there, IAM-integrated, one bill,
no cluster to size. AMP/AMG earns its place when PromQL and Grafana dashboards are specifically
wanted.

### Sidecars in ECS

A sidecar is just **multiple container definitions in one task**. In `awsvpc` mode all containers
share an ENI, so they talk over `localhost` — no DNS, no hop. Works on Fargate and EC2.

- **`"essential": false` on the collector** — an essential container exiting kills the whole task, so
  marking it essential makes observability a hard dependency of serving quotations. Trade-off: ECS
  won't restart an individual container, so pair it with an **absence-of-telemetry alarm** — a
  silently blind service looks identical to a healthy one on every dashboard.
- **`dependsOn: {condition: START}`** or the app boots first and drops startup spans into a closed
  port — exactly the traces you want when a deploy goes wrong.
- **Task role vs task execution role** — collector exports (`xray:PutTraceSegments`,
  `cloudwatch:PutMetricData`) need the **task** role; image pull and log shipping need the
  **execution** role. Getting it backwards means the collector starts fine and silently fails.
- **Fargate bills the sidecar** at task level, so it scales with fleet size, not telemetry volume.

**Sidecar vs central collector:** a per-task sidecar sees only its own task's spans, so it
*structurally cannot tail-sample*. Sidecars now; a central collector ECS service (via Service
Connect) at the point where losing error traces starts to hurt. `daemon` scheduling is EC2-only.

### How logs reach CloudWatch from ECS

```
stdout → container runtime → awslogs driver (agent) → PutLogEvents → /ecs/easycrm-api
```

App logs to **stdout, never a file** — Logback gets `ConsoleAppender` only; there's no durable
filesystem in Fargate. Stream name is `{prefix}/{container}/{taskId}`, which is why Logs Insights
queries always target the log *group*. Permissions on the **execution** role.

**The Java problem:** `awslogs` treats one line as one event, so a 40-line stack trace becomes 40
shredded events interleaved with other threads. `awslogs-multiline-pattern` exists and is fragile.
**The real fix is the JSON logging already chosen** — `logstash-logback-encoder` puts the trace in a
`stack_trace` field inside one JSON object on one line. One event, MDC attached, searchable. The
structured-logging decision solves correlation *and* multi-line.

**`mode: non-blocking` matters.** The driver defaults to **blocking**: if CloudWatch throttles, the
write to stdout blocks, which blocks the application thread that called `log.info()`. A CloudWatch
hiccup becomes an application stall. Non-blocking + `max-buffer-size` drops logs instead of applying
backpressure. Same principle as `essential: false` — **telemetry failures degrade telemetry, never
the service.**

**FireLens (Fluent Bit sidecar)** when you need filtering before ingest (dropping health-check noise
at $0.50/GB is the cheapest optimization there is), fan-out to S3/Glacier, or parsing. Costs another
sidecar and another failure mode — start with `awslogs`.

**EMF rides this same path** — it's just a stdout line containing the `_aws` node, extracted on
ingest. No extra API call, network path, or IAM. That's why it's the cheap way to emit business
metrics.

---

## Q8 — Frontend: stack, patterns, debugging, performance, observability

| Concern | Choice | Why |
|---|---|---|
| Build | Vite + React + TypeScript | fast, boring, correct |
| Server state | **TanStack Query** | ~90% of state is server state |
| Client state | **Zustand** | session + UI prefs only |
| Forms | React Hook Form + Zod | the builder is a nested `useFieldArray` |
| Tables | TanStack Table + virtualization | import preview renders 3,000 rows |
| UI | shadcn/ui + Tailwind | own the components, no upgrade treadmill |
| i18n | react-i18next | English + Hindi from day one, not retrofitted |
| Hosting | **S3 + CloudFront** | static bundle, edge-cached |

**The driving constraint: tier-2/3 Indian mobile networks.** Slow 4G is the target profile, not the
edge case — hence a **<200 KB gzipped initial bundle** and CI Lighthouse on a throttled profile. A
2 MB app that's fine in a Bangalore office is unusable in Rajkot; "works on my machine" is a literal
description of the failure.

### Server state is not client state

Almost nothing here is genuinely client state — enquiries, quotations, customers live on the server,
and every browser copy is a **cache**. Naming it a cache surfaces the real questions: when is it
stale, how do we revalidate, what happens on refocus, how do we dedupe concurrent requests for one
key. TanStack Query answers all of them; Redux makes you hand-roll each and get them subtly wrong.
Zustand holds only the auth session and UI prefs.

### Types are generated, not written

springdoc → OpenAPI → `openapi-typescript`, **CI fails on drift**. A hand-maintained
`QuotationResponse` interface is a lie waiting to happen — backend renames a field, TypeScript still
compiles, you find out in production. It also protects a specific rule: **money crosses the wire as a
JSON string**, so the generated type says `string` and no one can do float arithmetic on rupees. The
type system enforces the money discipline that `BigDecimal`/`NUMERIC` enforces server-side.

### Auth

Access token **in memory** (not localStorage — XSS-readable); refresh token in an
httpOnly/Secure/SameSite cookie. A 401 triggers exactly one refresh; concurrent in-flight requests
**queue** on it, then retry or hard-log-out together. Without the queue, a page loading six panels
fires six refreshes and five fail against a rotated token — random logouts.

### Patterns

Custom hooks are the composition unit; container/presentational is dead. Each feature's `api/` folder
exposes typed hooks and components never call `fetch`.

| Pattern | Where |
|---|---|
| **Facade** | `features/*/api/` — one place for auth headers, error normalization, query keys |
| **Adapter** | Generated client; normalizes RFC 7807 `ProblemDetail` into typed errors |
| **Compound components** | Radix/shadcn — `<Select><Select.Trigger/>…</Select>` |
| **Provider/Context** | Auth, i18n, theme — deliberately **not** server state |
| **Observer** | Query cache subscriptions — invalidate a key, subscribers re-render |
| **Command + rollback** | Optimistic mutations: apply, snapshot, roll back on error |
| **State machine** | 4-step import wizard; quotation status mirroring the backend |
| **Error boundary** | Per-route, so one broken panel doesn't white-screen the app |
| **Query-key factory** | `qk.quotations.detail(id)` — typo-proof keys in one file |

**Structure mirrors backend modules** (`features/enquiries`, `.../imports`) — a backend module change
touches exactly one frontend folder. Vertical slices, not horizontal `components/hooks/utils` layers
that force five directories per feature.

**Not used:** Redux; **barrel files** (`index.ts` re-exports break tree-shaking — material at a
200 KB budget); premature component abstraction (generalize on the third use, not the first).

**Mirroring the backend rule:** the builder shows live client totals for responsiveness and **the
server response overwrites them on save, always** — the client preview is never authoritative,
exactly as `QuotationService.buildItems` behaves.

### Debugging

**The cross-stack workflow is the answer.** The error toast shows `X-Trace-Id`; paste it into
CloudWatch Logs Insights and you have the backend logs and X-Ray trace for that exact request. "A
customer says sending failed sometimes" becomes a lookup instead of archaeology. That's why Q7 puts
the trace id on every response — the frontend surfacing it is the half that makes it usable.

Tools, in reach-for order:
1. **TanStack Query Devtools** — shows every cache entry as fresh/stale/fetching/error. The most
   common "bug" is a stale read after a mutation forgot to invalidate a key: visible in two seconds.
2. **React DevTools Profiler** — for builder lag. Usually a context value or an inline object
   recreated every render.
3. **Network tab** — slow API, or a request waterfall? Waterfalls are more common and more fixable.
4. **Playwright trace viewer** — DOM snapshot + console + network per step, so a CI-only flake is
   debuggable without CI access.
5. **`why-did-you-render`** for suspected render loops.

Same discipline as Q6b: **localize the layer first** — data, render, network, or backend. Guessing
costs more than checking.

### Performance

**Two screens decide the product**; everything else is a list and a form.

- **Quotation builder** — nested `useFieldArray`, 30 line items, naive version re-renders all 30 per
  keystroke. Fixes: RHF **uncontrolled inputs** (values in refs, typing doesn't re-render), memoized
  rows, debounced totals. Keyboard-first is the requirement and per-keystroke jank destroys it.
- **Import preview** — 3,000 editable rows, **virtualized** to the ~30 visible. Non-negotiable, not
  an optimization: 3,000 rendered inputs lock a mid-range Android entirely.

**Bundle:** route-level `React.lazy`, vendor chunk split, and a size budget that **fails the build**
— not a report someone reads. Regressions arrive one 40 KB dependency at a time.

**Fonts:** Hindi means Devanagari, and full Noto Sans Devanagari is heavy — **subset** it and load
only for the Hindi locale. Same underlying problem as the backend's PDF font: Indian scripts need
real coverage, and getting it wrong means `#` glyphs in one place and a 300 KB download in the other.

**Data:** `staleTime` per resource (catalog is stable for minutes, follow-ups are not);
`keepPreviousData` so pages swap without a loading flash; prefetch on hover for list→detail; cursor
pagination matching the backend so deep pages cost the same as page one.

### Observability — CloudWatch RUM

- **Core Web Vitals** (LCP, INP, CLS) from real users on real networks — our CI Lighthouse run is not
  executing over a Jio connection in a Rajkot warehouse.
- **JS errors** with source maps uploaded at deploy.
- **X-Ray integration** — RUM propagates trace ids into backend calls, giving a genuine
  **browser-click-to-SQL trace**. The payoff for one platform.
- **Custom events** for what infra can't answer: builder abandonment, import-wizard step drop-off,
  time-to-first-quotation for a new tenant.
- Sample sessions, but **always capture errors** — same logic as trace sampling.
- **Error boundaries report to RUM with the trace id**, so a white-screened panel leaves a
  diagnosable record instead of a silent failure.

**Honest caveat:** RUM is weaker than Sentry at error *triage* — grouping, regression detection,
release comparison. AWS-native is the right default; being dogmatic about it isn't.

### Testing

Vitest + Testing Library + **MSW** — mock at the network layer, not by stubbing hooks, so tests
exercise real Query cache behaviour.

**Playwright on four critical paths:** login; enquiry → quote → send; the import wizard on a
deliberately dirty CSV; and **the cross-tenant 404**. That last is an E2E assertion that tenant A
cannot reach tenant B's quotation — the most important security property in the system, tested from
the browser on every commit. Four isolation layers are only as good as the proof they still hold.

---
