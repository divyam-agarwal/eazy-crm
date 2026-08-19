# EasyCRM — Billing, Plans and Entitlements (design spec)

**Date:** 2026-08-19
**Status:** Design. Nothing here is built.
**Scope:** subscription tiers, per-seat pricing, entitlement enforcement, usage metering, the
billing vendor decision, and Indian payment/tax compliance.

## Relationship to other docs

| Doc | What it is |
|---|---|
| `2026-07-22-easycrm-design.md` | Parent spec. Billing is **P3**, sketched at §45 and §411 |
| `../architecture/2026-07-29-target-architecture.md` | §2.2 and §3.5 sketch the endpoints and the entitlement guard |
| `../architecture/2026-08-19-aws-target-architecture-design.md` | The five-service AWS design this must fit into |
| **this doc** | The subsystem in implementable detail |

The parent spec's **R1.2** note says: *"hand-invoice the first ~20 tenants; don't build billing
before there's someone to bill."* That advice still stands. This document specifies the system so
it can be built when there is revenue to justify it — and Part 7 orders the work so the pieces
that are useful without billing (invitations, entitlements) come first.

## What already exists

| | State |
|---|---|
| `tenant.status` (TRIAL / ACTIVE / SUSPENDED) | Column exists; `AuthService` sets TRIAL at signup with a 14-day `trial_ends_at` and rejects SUSPENDED logins |
| `tenant.gstin`, `tenant.state_code` | Exist — needed for the tax invoice |
| `app_user.role` (OWNER / SALES_MANAGER / SALES_EXEC), `app_user.status` | Exist |
| `platform.gst`, `DocumentNumberService`, PDF pipeline | Exist |
| **`tenant.plan`** | **Does not exist** (BF5) — the parent spec's data model lists it; no migration ever added it |
| **Trial expiry** | **Nothing ever leaves TRIAL** (BF6). No job, no transition |
| **User invitations** | **Do not exist** (BF7). A tenant cannot have a second user today |

---

# Part 0 — Decisions

| # | Decision | Rejected | Why |
|---|---|---|---|
| B1 | **Hybrid pricing**: flat tier price including N seats, per-seat above N | Pure per-seat; flat with a hard seat cap; usage-based on documents | Sells on one headline number, which is how this segment buys, while still monetising growth. Pure per-seat suppresses seat growth in Indian SMBs, where adding a part-time helper reads as a penalty |
| B2 | **A seat is any `app_user` with status ACTIVE**, owner included | Excluding the owner; billing only users who logged in | Countable at any instant, explainable in one sentence, impossible to game. Login-activity billing needs per-period tracking and makes the bill unpredictable in advance |
| B3 | **Seats auto-expand; billed with true proration settled on the next invoice** | High-water mark; hard block until upgrade | Originally high-water mark, to avoid building proration. B5 makes proration free, which removed that reason. **Settlement is deferred to the next invoice, not charged immediately** — an immediate mid-cycle charge is a separate transaction that runs into RBI one-off authorisation rules |
| B4 | **Hybrid metering**: derive hard limits by counting source tables; store counters only for what has no source row | Counter table for everything; event-driven counters | An eventually-consistent counter enforcing a hard limit is not a hard limit. Derived counts are exact, synchronous, and cannot drift |
| B5 | **Chargebee Starter on Razorpay** | Razorpay Subscriptions alone; Zoho Billing; Stripe Billing; merchant-of-record | Free to $250K cumulative billing — roughly a decade away at this scale — and purpose-built for seat-based billing. Absorbs RBI e-mandate rules, which after BF2/BF3 you actively do not want to own |
| B6 | **Chargebee issues the GST tax invoice** | EasyCRM issues its own | Least code. Note that EasyCRM *could* have issued them cheaply — `DocumentNumberService`, `platform.gst` and the PDF pipeline already do everything required. That option remains open if vendor templates prove limiting |
| B7 | **Entitlements travel in the JWT; usage counts stay local** | A billing-service call on every metered write; per-service caches | Keeps the entitlement check a purely local operation in all five services. No new synchronous hop on any write path |
| B8 | **Trial expiry downgrades to Free** | Expiry suspends the tenant (today's shape) | Once a Free tier exists, suspension throws away the relationship and the data for no reason. Suspension is reserved for payment failure |
| B9 | **Limits are enforced on create, never on read** | Locking the account until the tenant is back under limits | A tenant who trials with 200 customers and lands on Free keeps all 200 readable and exportable. Anything else holds a customer's own data hostage — bad product, and awkward under DPDP |
| B10 | **SUSPENDED is read-only plus export, not locked out** | Reject login (today's shape) | "Export your data before you go" is impossible if login is refused. Requires a change to `AuthService` |
| B11 | **Never block signup or a product write on the billing vendor** | Synchronous Chargebee call at signup | Revenue infrastructure must not sit on the critical path of product usage. Chargebee unreachable at signup → tenant is created locally on trial; a retry job creates the Chargebee customer |

---

# Part 1 — The product model

## 1.1 Tiers

| | **Free** | **Pro** | **Enterprise** |
|---|---|---|---|
| Price | ₹0 | ₹1,499/mo | Negotiated |
| Included seats | 1 | 3 | Negotiated |
| Extra seat | blocked | ₹399/mo | Negotiated |
| Quotations / month | 50 | unlimited | unlimited |
| Customers | 100 | unlimited | unlimited |
| Products | 200 | unlimited | unlimited |
| Price lists | 1 | unlimited | unlimited |
| Import module | ✗ | ✓ (5,000 rows/batch) | ✓ |
| WhatsApp shares / month | 50 | 1,000 | Negotiated |
| Reports | ✗ | ✓ | ✓ |
| SSO, audit export | ✗ | ✗ | ✓ |

Trial is **14 days of Pro**, then Free (B8).

## 1.2 The two principles that resolve every hard case

**B9 — enforce on create, never on read.** Downgrades never make existing data unreachable. The
entitlement guard sits on `POST`/`PATCH` paths only; `GET` is never gated by a limit.

**B3 corollary — seats over the limit on downgrade stay active.** You cannot silently deactivate
someone's salespeople. New invites are blocked and a persistent banner appears; the pressure to
upgrade comes from being unable to grow, not from breaking what already works.

Consequence to accept: a tenant who trials with 3 seats and lands on Free keeps 3 seats
indefinitely. At this scale the revenue impact is negligible and the goodwill is not.

---

# Part 2 — Domain model and the entitlement layer

## 2.1 Tables

All in `identity-svc`, which becomes the account context — tenant, users, auth, subscription. This
keeps the AWS design at five services, and it is coherent: the service that owns the tenant is the
service that mints the JWT carrying the entitlements.

| Table | Scope | Key columns |
|---|---|---|
| `plan` | **global**, seeded, RLS-exempt | `code`, `base_price`, `included_seats`, `extra_seat_price`, `limits JSONB`, `chargebee_plan_id` |
| `subscription` | tenant-scoped, RLS | `plan_code`, `status`, `current_period_start/end`, `chargebee_subscription_id`, `chargebee_customer_id`, `mandate_max_amount`, `price_snapshot`, `seat_quantity`, `cancel_at_period_end` |
| `usage_counter` | tenant-scoped, RLS | `(tenant_id, period, metric)` UNIQUE, `value` |
| `billing_event` | **global** | `provider_event_id` **UNIQUE**, `event_type`, `payload JSONB`, `received_at`, `processed_at` |
| `invoice_ref` | tenant-scoped, RLS | `chargebee_invoice_id`, `period`, `amount`, `status`, `pdf_url` |

`plan` and `billing_event` join `tenant`, `refresh_token`, `share_link` and `shedlock` in the
ArchUnit `GLOBAL_TABLES` allowlist.

`limits` is JSONB rather than columns so a new limit does not need a migration. The trade-off is no
database-level validation of its shape — a schema test covers it instead.

### Three columns that matter more than they look

- **`price_snapshot`.** When Pro rises to ₹1,999, existing customers keep ₹1,499 until deliberately
  migrated. Without it, a price change silently re-prices the entire base — which, with registered
  mandate amounts (BF3), can also fail every auto-debit at once.
- **`billing_event.provider_event_id UNIQUE`** is the webhook idempotency mechanism. Chargebee
  retries aggressively; the unique constraint plus the existing challenge #15
  `DataIntegrityViolation` handler makes duplicate delivery a no-op with no new machinery.
- **`mandate_max_amount`.** Stored locally so the application can warn *before* a seat addition
  pushes the subscription past the registered mandate ceiling (BF3).

## 2.2 The entitlement layer

The check needs the tenant's **limits** and their **current usage**. Naively that is a call to
`identity-svc` on every metered write — a second synchronous hop on the quotation path, on top of
the `master-data` hop the AWS design already adds.

**Put the limits where the check happens, and keep the count local.**

- `identity-svc` puts `plan` and the resolved `limits` into the **JWT** at login and refresh. Every
  service reads them with zero network calls.
- The **count** is always derived from the service's own schema. `sales` counts quotations in
  `sales`. No cross-service read, no cross-schema query.
- Staleness is bounded by the access-token TTL (15 minutes). The upgrade flow forces a token
  refresh so an upgrade takes effect immediately, and a `SubscriptionChanged` event on the existing
  cache-invalidation queue covers anything that caches.

The entitlement check is therefore a **purely local operation in every service**, and adds no
network hop to any write path.

### Enforcement shape

In keeping with how this codebase enforces invariants — structurally, not by remembering —
the guard is an annotation:

```java
@RequiresEntitlement(Metric.QUOTATION)
@PostMapping("/api/v1/quotations")
public QuotationResponse create(@RequestBody CreateQuotationRequest req) { … }
```

with an **ArchUnit rule** requiring every create endpoint on a metered resource to carry one, so a
new metered endpoint cannot ship unguarded. Lives in `platform.entitlement`.

### Error contract

```
402 Payment Required
{ "code": "PLAN_LIMIT_EXCEEDED", "metric": "QUOTATION",
  "limit": 50, "used": 50, "upgradeUrl": "/settings/billing" }
```

`402` rather than `403`: the request is well-formed and authorised, and payment is what unblocks it.
This matches the contract already sketched in the target architecture.

## 2.3 BF8 — entitlements in the JWT raise the stakes on RS256

The AWS design's **D11** (RS256 + JWKS, replacing today's HS256 shared secret) was justified as
hygiene. Once the JWT carries `plan` and `limits`, a shared symmetric secret means **any of the
five services can mint itself a token claiming `plan: ENTERPRISE`.** That is privilege escalation,
not hygiene. D11 becomes a prerequisite of B7, not an independent improvement.

---

# Part 3 — Billing lifecycle

## 3.1 Division of responsibility

| Concern | Owner |
|---|---|
| Plan catalogue, prices, proration, dunning, invoices, customer portal | Chargebee |
| Mandate lifecycle, RBI e-mandate rules, payment retries | Chargebee → Razorpay |
| GST tax invoice generation and delivery | Chargebee |
| MRR, churn, ARPU reporting | Chargebee (native) |
| Feature limits — quotations/month, customers, import | **EasyCRM** |
| Entitlement enforcement on the write path | **EasyCRM** |
| Seat count, reported as subscription quantity | **EasyCRM** |
| Product-usage analytics that inform pricing | **EasyCRM** |

**No vendor can own the entitlement layer.** "Has this tenant created 50 quotations this month" is
a count in the `sales` schema checked on the write path; Chargebee has no concept of a quotation.
What a vendor removes is proration arithmetic, dunning ladders, invoice generation, mandate
lifecycle, retries, tax computation, the billing portal, and tracking card-network and RBI rule
changes.

`subscription` is therefore a **projection synced from webhooks**, not the source of truth. It
exists so the entitlement check stays local and so a Chargebee outage cannot stop quotation
creation (B11).

## 3.2 Tenant state machine

```
TRIALING  ──14 days──────────►  ACTIVE (Free)     downgrade; data kept, writes limited
TRIALING  ──subscribe────────►  ACTIVE (Pro)
ACTIVE    ──payment failed───►  PAST_DUE          full access + banner; Chargebee dunning runs
PAST_DUE  ──dunning exhausted►  SUSPENDED         read-only + export
PAST_DUE  ──payment──────────►  ACTIVE
SUSPENDED ──payment──────────►  ACTIVE
ACTIVE    ──cancel───────────►  cancel_at_period_end, then ACTIVE (Free)
```

`TenantStatus` gains `PAST_DUE`. Two code changes fall out:

1. **`AuthService` must allow SUSPENDED logins in read-only mode** (B10). It currently rejects
   them outright, which makes data export impossible.
2. **Trial expiry must downgrade rather than suspend** (B8) — and the job that would do it does not
   exist yet (BF6).

Data is never deleted on downgrade, suspension or cancellation.

## 3.3 Integration

**Inbound — `POST /public/webhooks/chargebee`.** The second unauthenticated route in the product,
after `/public/q/{token}`.

1. Verify the signature (constant-time comparison).
2. Insert `billing_event`; a duplicate `provider_event_id` collides on the unique constraint and is
   absorbed as a no-op.
3. **Return 200 immediately.** Chargebee retries on non-2xx, so acknowledgement must not wait on
   processing.
4. Process asynchronously off the outbox, updating the `subscription` projection.

Events consumed: `subscription_created`, `subscription_changed`, `subscription_activated`,
`subscription_cancelled`, `subscription_renewed`, `payment_succeeded`, `payment_failed`,
`invoice_generated`.

**Outbound.**

| Trigger | Call |
|---|---|
| Tenant signup | Create Chargebee customer with `gstin`, `state_code`, business name |
| Seat added or removed | Update subscription quantity (proration deferred to next invoice, B3) |
| **Tenant edits GSTIN** | **Update the Chargebee customer** (BF4) |
| Plan change | Update subscription |

**BF4 — the silent one.** Chargebee generates the tax invoice from the customer record it holds. If
a tenant edits their GSTIN in EasyCRM and it is not pushed, every subsequent invoice carries a
stale GSTIN and the customer cannot claim input tax credit on it. This fails silently for months
and is discovered at filing time.

**Reconciliation, nightly.** Webhook-only sync drifts — a single missed delivery leaves the
projection permanently wrong with nothing to notice. The job compares local subscription state
against Chargebee and emits `billing_drift`, alarmed at > 0.

## 3.4 Failure policy

| Failure | Behaviour |
|---|---|
| Chargebee unreachable at signup | Tenant created locally on trial; retry job creates the customer (B11) |
| Webhook missed | Nightly reconciliation corrects it |
| Projection stale | Entitlements stale by at most the JWT TTL; reconciliation bounds the rest |
| Payment fails | Chargebee dunning runs; tenant moves to PAST_DUE with full access |
| Mandate cap exceeded | Debit fails → PAST_DUE. Prevented upstream by the pre-flight warning (BF3) |

---

# Part 4 — Metering and analytics

## 4.1 Metering (B4)

**Derived — counted from source tables at check time.** Exact, synchronous, cannot drift, no extra
write path. Covers quotations/month, customers, products, price lists.

```sql
SELECT count(*) FROM quotation WHERE created_at >= :period_start
```

RLS scopes this to the tenant, so **no `WHERE tenant_id` is written** — per the standing working
agreement. The supporting index still leads with `tenant_id`:
`CREATE INDEX ON quotation (tenant_id, created_at)`.

**Counted — stored in `usage_counter`.** For anything with no source row to count: WhatsApp
messages sent, PDF renders, import rows. Written by the service where the action happens, reported
onward by event.

**Peak metrics use a single atomic statement**, never read-modify-write:

```sql
UPDATE usage_counter SET value = GREATEST(value, :current)
 WHERE tenant_id = :t AND period = :p AND metric = :m
```

**BF1** — a peak is not derivable retrospectively. A tenant going 3 → 5 → 3 seats inside a month
shows 3 at month end. Any peak-based metric must be recorded as it happens. B3's move to proration
removes the *billing* dependence on this, but the principle governs every future peak metric.

**Soft vs hard limits.** Limits enforced against derived counts are hard. Limits enforced against
event-fed counters (WhatsApp sends, reported by `notification-svc`) are **soft** — a tenant may
overshoot slightly during the propagation window. Acceptable for COGS protection; never acceptable
for a limit that gates a paid feature.

## 4.2 Analytics

**Chargebee provides MRR, ARR, churn, ARPU and trial conversion natively.** That answers most of
"billing metrics tracking" without building anything.

**What stays yours:** product-usage analytics that inform pricing — quotation volume by tier,
import-module adoption, seat utilisation, which limits tenants actually hit. Built from the event
stream into the analytics read model (AWS doc sub-project 8).

**What belongs in CloudWatch:** operational billing health only — webhook failure rate, `billing_drift`,
PAST_DUE count, reconciliation job heartbeat. **MRR is not a CloudWatch metric.** It is a business
figure computed from the subscription projection, and putting it in CloudWatch invites exactly the
per-tenant dimensioning the AWS design forbids.

---

# Part 5 — Indian payment and tax compliance

These bind regardless of vendor.

## 5.1 RBI e-mandate (BF2)

- **AFA (additional factor of authentication) limit is ₹15,000 per transaction** for e-mandates on
  cards, PPIs and UPI. Above it, every debit needs authentication.
- The **₹1,00,000 exemption applies only to insurance premiums, mutual-fund subscriptions and
  credit-card bill payments.** SaaS subscriptions are **not** in that category.
- A **pre-debit notification at least 24 hours before every debit**, with transaction detail and an
  opt-out, is mandatory. Chargebee/Razorpay handle this; it must not be disabled.

At ₹1,499/month there is no AFA problem. An annual plan at ₹17,988 would exceed ₹15,000 and require
authentication at every renewal — **which is an argument against offering annual billing on cards**
until that trade-off is deliberately accepted.

## 5.2 BF3 — the mandate ceiling versus the seat model

Default mandate maxima are **₹5,000 for UPI** and ₹10,00,000 for netbanking.

```
Pro base                    ₹1,499
+ 9 extra seats × ₹399      ₹3,591
                            ──────
                            ₹5,090   >  ₹5,000 UPI mandate default
```

**A tenant growing from 3 to 12 seats silently breaks their own auto-debit.** Two mitigations, both
required:

1. **Register the mandate with deliberate headroom at signup** — 3× the initial bill, not 1×.
2. **Warn before the seat that crosses `mandate_max_amount`**, and route the tenant through
   re-authorisation rather than letting the next renewal fail.

This is the single most likely way this billing system fails in production, and it fails as a
declined payment rather than as an error anyone sees.

## 5.3 GST

18% GST applies to the subscription. The invoice must carry EasyCRM's GSTIN, the tenant's GSTIN,
the SAC code for software services, and place-of-supply-derived IGST vs CGST+SGST — the customer's
state (`tenant.state_code`) against EasyCRM's registered state. Chargebee generates this (B6); the
obligation that remains on EasyCRM's side is keeping the customer record in sync (BF4).

Customers are GST-registered distributors who need the invoice for input tax credit. A wrong or
missing invoice is not a billing inconvenience — it is a reason to churn.

---

# Part 6 — Impact on the AWS architecture

| Area | Change |
|---|---|
| `identity-svc` | Gains `plan`, `subscription`, `usage_counter`, `invoice_ref`, `billing_event`, the webhook endpoint and the Chargebee client. Still five services |
| CloudFront | **Third behavior** for `/public/webhooks/*` — uncached, `Authorization` not forwarded, its own WAF rate rule |
| Events | `SubscriptionActivated`, `SubscriptionChanged`, `SubscriptionPastDue`, `SubscriptionSuspended`, `TrialExpiring`, `SeatCountChanged` on the existing outbox → SNS → SQS pipeline |
| Scheduled jobs | Trial-expiry sweep, Chargebee reconciliation, seat-quantity push, dunning-state audit |
| JWT | Gains `plan` and `limits` claims — makes D11 (RS256/JWKS) a prerequisite, not an improvement (BF8) |
| Egress | Chargebee API calls need egress from the private subnets (NAT), unlike every other dependency which has a VPC endpoint |

---

# Part 7 — Sub-projects

Continuing the numbering in the AWS design's Part 5.

| # | Sub-project | Depends on | Notes |
|---|---|---|---|
| 9 | **User invitations + seat counting** | — | **Hard prerequisite, and it does not exist** (BF7). Per-seat billing is unbuildable while a tenant can only have one user. Useful on its own, with or without billing |
| 10 | **Entitlement layer** — `plan` table, JWT claims, `@RequiresEntitlement` + ArchUnit rule, 402 contract | 9 | Also useful without billing: it is how the Free tier is enforced |
| 11 | **Chargebee integration** — customer sync, webhook endpoint, projection, reconciliation | 10 | First point at which money moves |
| 12 | **Trial/dunning state machine** — PAST_DUE, read-only SUSPENDED, trial-expiry job | 11 | Includes the `AuthService` change (B10) |
| 13 | **Billing analytics** into the read model | 11 | Chargebee covers MRR/churn; this is product-usage analytics |

**Recommended order:** 9 → 10, then stop and reassess. Those two deliver multi-user tenants and an
enforced Free tier — genuinely useful product — without taking on a payment integration. Per the
parent spec's R1.2, 11–13 wait until there is someone to bill.

---

# Appendix A — Findings

| # | Finding |
|---|---|
| BF1 | A peak metric is not derivable retrospectively; it must be recorded as it happens |
| BF2 | RBI AFA limit is ₹15,000 per transaction; the ₹1,00,000 exemption excludes SaaS; 24-hour pre-debit notice is mandatory |
| BF3 | Default UPI mandate ceiling is ₹5,000. Pro plus nine seats is ₹5,090 — seat growth silently breaks auto-debit. Needs mandate headroom **and** a pre-flight warning |
| BF4 | Chargebee bills from the customer record it holds. A tenant's GSTIN edit must be pushed, or their invoices become unusable for input tax credit — silently |
| BF5 | `tenant.plan` is in the parent spec's data model but no migration ever added it. Spec and code have drifted |
| BF6 | Nothing in the codebase ever transitions a tenant out of TRIAL. Every tenant trials forever |
| BF7 | User invitations do not exist. Per-seat billing has an unbuilt hard prerequisite |
| BF8 | Entitlements in a JWT signed with a shared symmetric secret let any service mint itself Enterprise. Makes D11 a security requirement |
| BF9 | Webhook-only sync drifts silently; nightly reconciliation with a drift metric is not optional |
| BF10 | Chargebee's $250K free threshold is **cumulative and permanent** — crossing it charges 0.75% on all future billing, with no reset |
| BF11 | Merchant-of-record providers solve foreign tax nexus. Selling INR to Indian businesses has no nexus problem, so MoR is the wrong tool at roughly 5% |

# Appendix B — To verify before implementation

1. **Chargebee Starter terms** — the $250K cumulative threshold and 0.75% overage came from
   comparison sites, not Chargebee's pricing page. Confirm directly; free tiers move.
2. **Chargebee ↔ Razorpay e-mandate coverage** — specifically whether UPI Autopay mandates can be
   registered above the ₹5,000 default, and what the ceiling is.
3. **SAC code for SaaS** — 998314 vs 997331 vs 998434. Confirm with a chartered accountant; it
   affects every invoice.
4. **Whether e-invoicing (IRN) applies** — turnover-threshold dependent, and the threshold has
   moved repeatedly.
5. **Chargebee proration behaviour on quantity change** — confirm that settlement can be deferred
   to the next invoice rather than charged immediately (B3 depends on this).
6. **Razorpay/Chargebee pre-debit notification** — confirm it is automatic and cannot be disabled.
7. **GST treatment of the Free tier** — a zero-value supply generally needs no tax invoice, but
   confirm before assuming.
