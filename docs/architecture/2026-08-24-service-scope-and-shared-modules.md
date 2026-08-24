# EasyCRM — Per-Service Scope and Shared Modules (design spec)

**Date:** 2026-08-24
**Status:** Design only. Zero code changed.
**Code baseline:** `80e74a3` — the last commit that touched `backend/`. 231 tests.
**Parent:** [`2026-08-19-aws-target-architecture-design.md`](2026-08-19-aws-target-architecture-design.md)
(the five-service split, D1–D15, F1–F17)
**Siblings:** [`2026-08-19-outbox-lld.md`](2026-08-19-outbox-lld.md) ·
[`../superpowers/specs/2026-08-19-billing-and-entitlements-design.md`](../superpowers/specs/2026-08-19-billing-and-entitlements-design.md)

---

## Why this document exists

The parent doc scoped service **ownership** — which packages and which schema each service owns.
It did not scope service **surface**. Nowhere lists, per service, the routes it exposes, the events
it emits and consumes, the platform modules it imports, and its remaining synchronous dependencies.

That list is the missing input to **sub-project 8 (service extraction, `document` first)**, and
writing it out surfaced five things the parent doc's tables miss — see Part 5.

## How to read this

Every row carries provenance. Nothing aspirational is labelled built.

| Mark | Meaning |
|---|---|
| **[built]** | Exists in code at `80e74a3` |
| **[spec]** | Decided in an existing design doc; the doc is cited |
| **[new]** | Introduced by *this* document and not yet reviewed by anyone |
| **[moves]** | Exists today, changes owner or path under the split |

---

# Part 1 — The five services

## 1.1 identity-svc

**Purpose.** Mints and rotates credentials, owns the tenant record, and — after sub-projects 9–13 —
owns seats, plans and the billing vendor relationship. It is the only service that holds the JWT
**signing** key.

**Owns.** Packages `iam`, `tenant`. Schema `identity`. Writes `shared.tenant` and `shared.plan`
(every other service reads them, none writes them).

### HTTP routes

| Route | State | Notes |
|---|---|---|
| `POST /api/v1/auth/signup` | **[built]** | Atomic tenant + owner creation |
| `POST /api/v1/auth/login` | **[built]** | bcrypt |
| `POST /api/v1/auth/refresh` | **[built]** | Rotating, SHA-256-hashed tokens |
| `POST /api/v1/auth/logout` | **[built]** | |
| `GET  /api/v1/auth/me` | **[built]** | |
| `GET  /api/v1/tenant` | **[built]** | **Unrouted in the parent's ALB table — see S1** |
| `PATCH /api/v1/tenant` | **[built]** | Seller profile: business name, GSTIN, address. Feeds the PDF letterhead |
| `/api/v1/users/*` | **[spec]** | Invitations, seat lifecycle. Sub-project 9. **No controller exists — see S4** |
| `GET  /api/v1/plans` | **[spec]** | Billing spec Part 7 |
| `/api/v1/subscription` | **[spec]** | Billing spec Part 7 |
| `POST /public/webhooks/chargebee` | **[spec]** | The product's second unauthenticated route. Billing spec §222 |

### Events

| Direction | Event | State |
|---|---|---|
| Emits | `SubscriptionActivated`, `SubscriptionChanged`, `SubscriptionPastDue`, `SubscriptionSuspended`, `TrialExpiring`, `SeatCountChanged` | **[spec]** billing spec Part 7 |
| Consumes | none | **[new]** — no design doc gives identity a consumer |

### Synchronous dependencies

**None inbound from other EasyCRM services.** Outbound: the Chargebee API, via NAT. Per B11, a
vendor outage must never block signup or a product write.

### Scheduled jobs
Refresh-token cleanup, daily **[spec]**. Trial-expiry sweep **[spec]**, billing spec.

### Platform modules
`platform-tenancy`, `platform-persistence`, `platform-security`, `platform-web`, `platform-gst`
(tenant GSTIN validation — `Gstin`/`StateCode` are used by `tenant` today), `platform-outbox`,
`platform-db`.

**Plus, uniquely: the JWT signing key.** Under D11 (RS256 + JWKS), identity holds the private key
and publishes JWKS; the other four verify only. That asymmetry is a security control, not an
implementation detail — see S7.

### Explicitly out of scope
Entitlement *enforcement*. Identity mints the claims; every service enforces them locally (B7).

---

## 1.2 master-data-svc

**Purpose.** The system of record for who you sell to and what you sell. Read-heavy, low write
volume, and on the critical path of every quotation write.

**Owns.** Packages `catalog`, `crm`. Schema `master_data`.

### HTTP routes — all **[built]**

| Prefix | Routes |
|---|---|
| `/api/v1/products` | `POST` · `GET /{id}` · `GET` (filtered list) · `PUT /{id}` · `POST /{id}/deactivate` · `POST /{id}/activate` |
| `/api/v1/customers` | `POST` · `GET /{id}` · `GET` (filtered list) · `PUT /{id}` · `POST /{id}/deactivate` · `POST /{id}/activate` |
| `/api/v1/customers/{customerId}/contacts` | `POST` · `GET` · `PUT /{contactId}` · `DELETE /{contactId}` |
| `/api/v1/price-lists` | `POST` · `GET /{id}` · `GET` · `PUT /{id}` · `POST /{id}/deactivate` · `POST /{id}/activate` |
| `/api/v1/price-lists/{priceListId}/items` | `POST` · `GET` · `DELETE /{itemId}` |

Note the parent's ALB table routes `/api/v1/contacts/*`. **No such prefix exists** — contacts are
nested under `customers`, which already routes correctly. See S3.

### Internal API (Service Connect, not ALB) — all **[new]**

The parent doc names three call sites but no endpoints. These are the minimum:

| Endpoint | Caller | Returns |
|---|---|---|
| `GET /internal/customers/{id}` | sales | `stateCode`, `businessName`, `gstin`, `billingAddress`, `priceListId`, `active` |
| `GET /internal/price-lists/{id}/items?productId=` | sales | resolved rate, or 404 |
| `GET /internal/products/{id}` | sales | name, HSN, UOM, base rate, `active` |
| `GET /internal/customers/{id}/contacts/primary` | document | name, phone, whatsappNumber — **see S2** |

These must not be ALB-routed. An `/internal/*` prefix reachable only over Service Connect keeps the
public surface honest, and a WAF rule denying `/internal/` at the edge makes the mistake loud.

### Events

| Direction | Event | State |
|---|---|---|
| Emits | `CustomerUpdated`, `CustomerArchived` | **[spec]** parent §2.1 |
| Emits | `ProductUpdated`, `PriceListItemChanged` | **[new]** — needed only if sales ever caches; it does not today. **Do not build yet** |
| Consumes | none | |

**Deletion becomes archival.** With no foreign keys and no cross-schema reads, a hard delete of a
customer leaves orphaned `quotation.customer_id` values. `POST /{id}/deactivate` already exists and
is the deletion story; the nightly `orphaned_references` sweep is detection, not prevention.

### Scheduled jobs
None. **[new]** — no doc assigns master-data a job, and none is needed.

### Platform modules
`platform-tenancy`, `platform-persistence`, `platform-security`, `platform-web`, `platform-money`
(price lists carry `NUMERIC` rates that must serialise as JSON strings), `platform-gst`
(`Gstin` checksum + `StateCode` derivation on customer create), `platform-outbox`,
`platform-entitlement`, `platform-db`.

---

## 1.3 sales-svc

**Purpose.** The wedge. Enquiry → versioned GST quotation → order. The main write path, and the one
service the parent doc deliberately refuses to split further — `accept` creating an order stays one
local transaction.

**Owns.** Package `sales` minus the PDF and share subtrees. Schema `sales`.

### HTTP routes

| Route | State |
|---|---|
| `POST /api/v1/enquiries` · `GET /{id}` · `PATCH /{id}` · `POST /{id}/advance` · `POST /{id}/lose` · `GET` (filtered) | **[built]** |
| `POST /api/v1/quotations` · `GET /{id}` · `GET` (filtered) | **[built]** |
| `GET /api/v1/quotations/{id}/versions` · `GET /{id}/versions/{versionNo}` | **[built]** |
| `PATCH /api/v1/quotations/{id}` · `PUT /{id}/items` | **[built]** |
| `POST /api/v1/quotations/{id}/send` · `/accept` · `/revise` · `/reject` · `/expire` | **[built]** |
| `GET /api/v1/orders/{id}` · `POST /{id}/dispatch` · `/close` · `/cancel` · `GET` (filtered) | **[built]** |
| ~~`GET /api/v1/quotations/{id}/pdf`~~ | **[moves]** → `document` as `/api/v1/documents/quotations/{id}/pdf` |
| ~~`POST /api/v1/quotations/{id}/share`~~ | **[moves]** → `document` as `/api/v1/documents/quotations/{id}/share` |

### Events

| Direction | Event | State |
|---|---|---|
| Emits | `QuotationSent` — **carries the full render payload** (F13) | **[spec]** |
| Emits | `QuotationAccepted` | **[built]** as an in-process `@EventListener` seam; becomes an outbox row |
| Emits | `OrderStatusChanged` | **[built]** as above |
| Emits | `QuotationExpired` | **[spec]** parent §3.3 |
| Consumes | `CustomerUpdated`, `CustomerArchived` (cache-invalidation queue) | **[spec]** |

`QuotationSent` is the heaviest event in the system by an order of magnitude — it carries every line
item, the tax split, the letterhead and the buyer snapshot, because F13 makes it `document-svc`'s
only source of truth. Budget for the SNS 256 KB limit and decide the S3-claim-check fallback before
building. **[new]** — see S8.

### Synchronous dependencies

| Needs | From | Failure mode |
|---|---|---|
| `customer.stateCode`, existence | master-data | **Fail fast, 503.** A quotation with the wrong tax split is worse than no quotation |
| Price list item, product | master-data | Same |

### Scheduled jobs
Quotation auto-expiry (daily 02:00 IST, `lockAtMostFor` 10 m) · follow-up reminder sweep (15 min,
5 m) · orphaned-reference reconciliation (nightly, 10 m) · outbox reaper (hourly). All **[spec]**.

### Platform modules
`platform-tenancy`, `platform-persistence`, `platform-security`, `platform-web`, `platform-money`
(`GstCalculator`, `BigDecimalStringModule` — challenges #2 and #17), `platform-gst`,
`platform-outbox`, `platform-entitlement`, `platform-db`.

### Deleted, not moved
`OrderAcceptedAuditListener` and `OrderStatusChangedAuditListener` write to `iam`'s `audit_log`
in-process today. D9 removes `audit_log` entirely, so **both classes are deleted** and their
content becomes structured CloudWatch log lines. This is the only place in the split where a
cross-schema write disappears by deletion rather than by design — worth stating so nobody
"helpfully" reimplements it as a Service Connect call. **[new]**, following **[spec]** D9.

---

## 1.4 document-svc

**Purpose.** Turn a frozen quotation version into bytes, and serve those bytes to a customer who has
no account. Bursty and CPU-bound — the one workload whose scaling profile genuinely differs from
everything else, and therefore the reason the split is defensible at all.

**Owns.** `sales.pdf`, `share_link`, `render_payload`, `/public/q/*`. Schema `document`.
**Extract this one first** (sub-project 8).

### HTTP routes

| Route | State | Notes |
|---|---|---|
| `GET /api/v1/documents/quotations/{id}/pdf` | **[moves]** | Authenticated. Defaults to the latest `SENT` version |
| `POST /api/v1/documents/quotations/{id}/share` | **[moves]** | Mints/returns the token and the `wa.me` link |
| `GET /public/q/{token}` | **[built]** | The only unauthenticated read path today. No JWT, no tenant — `share_link` is global and resolves it, then `runAs` before the render transaction opens |

### Events

| Direction | Event | State |
|---|---|---|
| Consumes | `QuotationSent` → persists an immutable `document.render_payload` keyed by `quotation_version_id` | **[spec]** F13 |
| Emits | `ShareLinkCreated` | **[new]** — only if notification needs it; today `share` returns the link synchronously and nothing else needs to know |

### Synchronous dependencies

**By design, zero on the render path.** F13's frozen payload is what buys that, and it is what makes
the CloudFront cache *correct* rather than merely convenient.

**But one remains on the share path.** `ShareLinkService.whatsappLink()` reads the customer's primary
`Contact` for the phone number and greeting name. That call site is absent from the parent doc's
table of four. See **S2** — it is the single most likely thing to block sub-project 8.

### Scheduled jobs
Share-link expiry + CloudFront invalidation, daily **[spec]**. Outbox reaper, hourly.

### Platform modules
`platform-tenancy`, `platform-persistence`, `platform-security`, `platform-web`, `platform-money`,
`platform-outbox`, `platform-db`.

**Plus, uniquely: `PdfEngine` and `IndianFormats`.** Both live in `platform` today and both are used
by exactly one package (`sales.pdf`). They **leave platform** and become internal to `document-svc`.
A mechanism used by one service is not a platform — see S6.

### Explicitly out of scope
Draft preview. There is no frozen payload for a draft, and the current endpoint already defaults to
the latest `SENT` version, so this is a formalisation of existing behaviour rather than a
regression.

---

## 1.5 notification-svc

**Purpose.** The only genuinely greenfield service, and the only one that is not an HTTP service at
all. It turns events into messages a human reads.

**Owns.** Nothing today. Schema: none — the parent's §1.3 gives it no schema, which **cannot be
right**; see S5.

### HTTP routes
**None.** It is not an ALB target. It is an SQS consumer.

### Events

| Direction | Event | Action |
|---|---|---|
| Consumes | `QuotationSent` | WhatsApp message to the buyer |
| Consumes | `QuotationExpired` | Alert the salesperson |
| Consumes | `QuotationAccepted` | Confirm to the owner |
| Consumes | `OrderStatusChanged` | Status update to the buyer |
| Consumes | `TrialExpiring`, `SubscriptionPastDue` | Billing nudges |
| Emits | `NotificationFailed` | **[new]** — needed for the dunning state machine and for "the quote never reached them" support questions |

All consumption is **[spec]** in shape (parent §3.1 step 8) and **[new]** in every detail.

### What is undesigned

This is the largest scoping gap in the split, and it is not small:

- **No provider abstraction.** The only sender that exists is `iam.email.LoggingEmailSender` — a stub behind an `EmailSender` interface, sitting in identity’s package, which must move here. Beyond it there is a `wa.me`
  *string* — there is no WhatsApp Business API client anywhere, and the Meta API has template
  pre-approval, a 24-hour session window, and per-number rate limits that shape the design.
- **No template store.** Where do message bodies live, who edits them, and are they per-tenant?
- **No suppression, quiet hours, or dedupe-across-events.** Three events on one quotation in ten
  seconds is three WhatsApp messages today.
- **No delivery-status ingestion.** Meta posts delivery receipts to a webhook — which would make
  notification an HTTP service after all, and change its ALB and CloudFront story.

Until these are answered, `notification-svc` is a name, not a scope. **Do not schedule its
extraction against a date.**

### Scheduled jobs
Owner digest, daily 08:00 IST **[spec]**. Outbox reaper if it gains a schema.

### Platform modules
`platform-tenancy`, `platform-persistence`, `platform-money`, `platform-outbox`, `platform-db`.
**Not** `platform-security` or `platform-web` — it serves no HTTP and must not carry a filter chain
it never uses. It still needs `TenantContext`, because every consumer restores tenant context with
`runAs` before opening its transaction (F4).

---

## 1.6 The `demo` module

`DemoRecord` / `GET /api/v1/demo-records/{id}` exists as a deliberate isolation *test subject* — a
tenant-scoped entity with no business meaning, used to prove cross-tenant reads 404. It belongs to
no service. **[new] recommendation:** move it to a test fixture under `platform-tenancy`'s test
source set and delete the controller and its two migrations (`V3__demo_record.sql`, `V4__rls_demo_record.sql`) from production. Shipping a demo endpoint to
five services is worse than shipping it to one.

---

# Part 2 — Shared modules

## 2.1 The rule, and the test that enforces it

**`platform` may contain mechanisms, never meanings** (D12). Tenancy is a mechanism; a customer is a
meaning. A shared `Quotation` type is precisely how a split becomes a distributed monolith, where
adding a field forces a five-service release.

One ArchUnit rule enforces it: `platform` may not reference any service package.

This document adds a second test, because the first one passes for things that still should not be
in platform: **a mechanism used by exactly one service is not a platform mechanism.** `PdfEngine`
satisfies "no service imports" perfectly and still belongs inside `document-svc`.

## 2.2 The proposed decomposition

`platform` is one lump today — `tenancy`, `security`, `persistence`, `error`, `web`, `money`, `gst`,
`format`, `pdf`. Every service would import all of it, including the PDF engine and the servlet
filter chain that `notification-svc` has no use for.

**[new] — this table is the recommendation; nothing below D12 decided it.**

| Module | Contains | Imported by | Why separate |
|---|---|---|---|
| `platform-tenancy` | `TenantContext`, `TenantIdentifierResolver`, `TenantAwareTransactionManager`, `HibernateTenancyConfig`, `AsyncConfig`, `TenantAwareTaskDecorator`, `BaseEntity`, `TenantScopedEntity`, `UuidV7` | **all 5** | The load-bearing one. Tenancy and persistence are inseparable — `TenantScopedEntity` *is* the `@TenantId` carrier |
| `platform-security` | `JwtAuthenticationFilter`, `SecurityConfig`, JWKS verification, `PasswordConfig` | **4** (not notification) | notification has no HTTP surface and must not carry a filter chain |
| `platform-web` | `ApiExceptionHandler`, the exception hierarchy, `PageResponse` | **4** (not notification) | Same reason. Also keeps the 404-not-403 mapping in exactly one place |
| `platform-money` | `BigDecimalStringModule`, `MoneyJacksonConfig` | **all 5**, *and `platform-outbox` depends on it* | TB3: a fresh `ObjectMapper` in the outbox writer serialises money as a JSON **number**, silently undoing challenges #2 and #17. Making this a declared dependency is the fix |
| `platform-gst` | `Gstin` (checksum), `StateCode` (derivation) | identity, master-data, sales | Used by `crm`, `sales` and `tenant` today. `document` needs neither — the render payload carries rendered strings |
| `platform-outbox` | 12 classes + `V900`/`V901` DDL shipped in the jar | **all 5** | Fully specified already — see the outbox LLD |
| `platform-entitlement` | `@RequiresEntitlement(Metric)`, the guard, the ArchUnit rule that fails an unguarded metered endpoint | **4** (write paths) | B7: the check is purely local, so no service calls billing on a write path |
| `platform-db` | Flyway baseline: `CREATE EXTENSION`, `shared` schema, grants, RLS helper functions | **all 5** | Runs first, always. Owns what belongs to no single service |
| — | `PdfEngine`, `IndianFormats` | — | **Leave platform.** Used by one service; move into `document-svc` |

**Signing vs. verifying.** `JwtService` mints tokens today. Under RS256 (D11), minting stays in
`identity-svc` with the private key; `platform-security` carries verification only. If the minting
code ships in a module all five services import, the private key becomes reachable from five
task roles instead of one, and BF8 — any service minting itself Enterprise — stops being a
hypothetical. See S7.

## 2.3 `contracts/` — the module that is currently a directory name

The parent doc's repo layout has `contracts/` and its §2.2 decides the *policy* — versioned,
additive-only, consumers ignore unknown fields, a breaking change is a new `event_type` running in
parallel. Nothing populates it.

**[new] — proposed contents:**

```
contracts/
├── events/
│   ├── src/main/java/com/easycrm/contracts/events/    event POJOs, one per event_type
│   └── src/main/resources/schema/                     JSON Schema, one file per (event_type, version)
└── openapi/                                           generated from controllers at build time
```

`contracts:events` is the **one** module that carries meanings rather than mechanisms, which is why
it sits outside `platform` and outside every service. It is also the only module a
consumer-driven-contract test can meaningfully assert against.

The rule that keeps it from becoming the distributed monolith: **an event POJO may contain only
what a consumer needs, never the emitter's domain object.** `QuotationSentEvent` carries a flat,
frozen render payload — not a `Quotation`.

---

# Part 3 — Event catalogue

The consolidated view. Ordering is per `MessageGroupId = aggregate_id`, never per tenant — keying by
tenant makes one large distributor a permanent bottleneck. Duplicates are guaranteed rather than
exceptional; every consumer dedupes on `outbox.id`.

| Event | Emitter | Consumers | State |
|---|---|---|---|
| `QuotationSent` | sales | document (render payload), notification (buyer WhatsApp) | **[spec]** |
| `QuotationAccepted` | sales | notification | **[built]** in-process seam |
| `QuotationExpired` | sales | notification | **[spec]** |
| `OrderStatusChanged` | sales | notification | **[built]** in-process seam |
| `CustomerUpdated` | master-data | sales (cache-invalidation) | **[spec]** |
| `CustomerArchived` | master-data | sales | **[spec]** |
| `SubscriptionActivated` | identity | notification | **[spec]** billing |
| `SubscriptionChanged` | identity | notification | **[spec]** billing |
| `SubscriptionPastDue` | identity | notification | **[spec]** billing |
| `SubscriptionSuspended` | identity | notification | **[spec]** billing |
| `TrialExpiring` | identity | notification | **[spec]** billing |
| `SeatCountChanged` | identity | notification | **[spec]** billing |
| `NotificationFailed` | notification | identity (dunning) | **[new]** |

Twelve of thirteen have exactly one or two consumers. That is worth noticing: **the event pipeline
is carrying notification traffic almost exclusively.** If `notification-svc` were folded back into
the monolith, the outbox would still be needed — for the dual-write guarantee — but SNS fan-out
would have almost nothing to fan out to. This is not an argument against the design; it is the
honest shape of it, and it belongs next to the parent doc's Part 4.

---

# Part 4 — Complete route ownership

Every route in the codebase today, plus every specified one, with its owner. This is the table the
ALB config is generated from, and the one to diff against `find . -name '*Controller.java'` before
each extraction.

| Path | Owner | State |
|---|---|---|
| `/api/v1/auth/*` | identity | built |
| `/api/v1/tenant` | identity | built — **unrouted, S1** |
| `/api/v1/users/*` | identity | spec, no code |
| `/api/v1/plans`, `/api/v1/subscription` | identity | spec, no code |
| `/public/webhooks/*` | identity | spec, no code |
| `/api/v1/customers/*` (incl. nested `contacts`) | master-data | built |
| `/api/v1/products/*` | master-data | built |
| `/api/v1/price-lists/*` (incl. nested `items`) | master-data | built |
| `/internal/*` | master-data | **new, S2** — Service Connect only, denied at the edge |
| `/api/v1/enquiries/*` | sales | built |
| `/api/v1/quotations/*` | sales | built, minus two moved routes |
| `/api/v1/orders/*` | sales | built |
| `/api/v1/documents/*` | document | moves |
| `/public/q/*` | document | built |
| `/api/v1/demo-records/*` | none | built — **delete, S9** |

---

# Part 5 — Findings

| # | Finding | Severity |
|---|---|---|
| **S1** | **`/api/v1/tenant` is absent from the ALB routing table.** Both routes exist and are built. Under the split they 404 at the edge — the seller cannot read or edit the business name, GSTIN and address that appear on every PDF letterhead. Fix: add `/api/v1/tenant` → identity | **Blocking** for sub-project 2 |
| **S2** | **A fifth cross-service call site the parent doc's table of four misses.** `ShareLinkService.whatsappLink()` reads the customer's primary `Contact` (whatsapp number, phone, name) from `crm`. Under the split, `document_app` has no privileges on `master_data`. F13 solves the *render* path and leaves the *share* path with a live synchronous dependency. Three options: fold the primary contact into the `QuotationSent` render payload (consistent with F13, and the phone number at send time is arguably the right one anyway); add `GET /internal/customers/{id}/contacts/primary`; or move share-link creation to `sales`. **Recommend the first** | **Blocking** for sub-project 8 |
| **S3** | The ALB table routes `/api/v1/contacts/*` to master-data. No such prefix exists — contacts are nested under `/api/v1/customers/{id}/contacts`, already covered. A rule matching nothing is harmless until someone builds a real `/api/v1/contacts` elsewhere. Delete the row | Cosmetic |
| **S4** | `/api/v1/users/*` is routed to identity, which has no such controller. It arrives with sub-project 9 (invitations). Routing it before it exists means a 502 rather than a 404. Add the rule *with* the code | Minor |
| **S5** | **`notification-svc` is given no schema**, yet it needs `processed_event` for consumer-side dedupe (outbox LLD §2.6) and would need a template store, a suppression list and a delivery-status table. A service that consumes at-least-once **must** have somewhere to write its dedupe key. Give it a `notification` schema and role | **Blocking** for sub-project 6 |
| **S6** | `PdfEngine` and `IndianFormats` sit in `platform` and are used by exactly one package. Left there, all five services inherit an openhtmltopdf dependency. Move both into `document-svc` | Minor, cheap now |
| **S7** | `JwtService` mints tokens and lives in shared `platform`. Under RS256 that would put the signing key within reach of five task roles. Split verification (shared) from minting (identity only) as part of sub-project 7 | **Security**, do with D11 |
| **S8** | `QuotationSent` carries the entire render payload (F13) and is bounded by SNS's 256 KB message limit. A 200-line quotation with long product names may exceed it. Decide the claim-check fallback — payload to S3, pointer in the message — before the first extraction, not after | Design gap |
| **S9** | `DemoRecord` and `/api/v1/demo-records/{id}` belong to no service. Convert to a test fixture; delete the controller and both migrations | Minor |
| **S10** | The two `sales` audit listeners write to `iam.audit_log` in-process. D9 deletes `audit_log`, so they are deleted too, not moved. State it, or someone will reimplement them as a cross-service call | Documentation |

S1, S2 and S5 are each a hard blocker for a sub-project already on the plan. None was visible from
the ownership tables alone — all three surfaced only from writing the surface down.

---

# Appendix A — What this changes about extraction order

The parent doc's recommendation stands: **#1 buyer snapshot → #2 AWS foundation → #3 observability
→ #4 scaling, then reassess.** Nothing here argues with that.

Two amendments, both small:

1. **S1 folds into sub-project 2.** It is a one-line ALB rule and it must be right the first time.
2. **S2 folds into sub-project 1.** The buyer snapshot is already a `QuotationVersion` change; the
   primary contact belongs in the same frozen payload, for the same reason, in the same migration.
   Doing it later means a second migration over the same table.

`notification-svc` should not be scheduled until Part 1.5's four open questions are answered. It is
the only service whose scope is a name.

# Appendix B — To verify before implementation

1. **SNS message size** for a realistic worst-case `QuotationSent` (S8). Measure against a 200-line
   quotation with 80-character product names, not a synthetic one.
2. **Envoy/Service Connect timeout and retry defaults** — a retried `GET /internal/customers/{id}`
   is safe; confirm nothing on the internal API is non-idempotent before relying on that.
3. **Whether a WAF rule can deny `/internal/*`** at the CloudFront behaviour level given the ALB is
   a VPC origin with no public listener. If there is genuinely no public path, the rule is
   belt-and-braces rather than a control — confirm which, and say so in the Terraform comment.
4. **WhatsApp Business API constraints** before scoping notification: template pre-approval, the
   24-hour session window, per-number rate limits, and whether delivery receipts force an HTTP
   surface (which would change its ALB and CloudFront story).
5. **`processed_event` growth and reaping** for notification — the dedupe table grows with every
   message, forever, unless something prunes it.
