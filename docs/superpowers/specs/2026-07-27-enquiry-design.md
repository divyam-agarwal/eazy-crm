# EasyCRM P1 — Enquiry Slice Design

**Status:** Design approved, pre-implementation
**Date:** 2026-07-27
**Parent spec:** `2026-07-22-easycrm-design.md` (§2 domain model — `enquiry`, §6 backend structure)
**Depends on:** P0 isolation + P0-auth + P1a master data + P1b quotation engine + order/accept
(all merged on `main`)

---

## 1. Context & purpose

The product wedge is **enquiry → quotation → order**. P1a/P1b/order-accept built the funnel from
the quotation onward. This slice builds the wedge's **head**: `enquiry`, the lead — where a
distributor first captures "someone wants something," before any quotation exists.

`quotation.enquiry_id` already exists as a nullable forward-compat column (P1b). This slice builds
the `Enquiry` aggregate and its lifecycle **only**; it does **not** wire enquiry→quotation
conversion (the existing quotation-create path already accepts `enquiryId`, so conversion stays a
thin later follow-up).

| Wedge stage | Slice | Status |
|-------------|-------|--------|
| **enquiry (lead capture)** | **this spec** | **this slice** |
| quotation (build → send → revise) | P1b | **merged** |
| order (accept a sent quote) | order/accept | **merged** |

## 2. Scope

**In scope:**
- `Enquiry` aggregate — single entity, tenant-scoped, RLS-covered, on table `enquiry`.
- Phone **normalization** (→ canonical 10-digit key) and **dedupe**: one *active* enquiry per
  normalized phone within a tenant, enforced by a **partial unique index**.
- A **5-stage guarded state machine**: `NEW → CONTACTED → QUALIFIED → CONVERTED / LOST`.
- REST: create, get-by-id, list (filtered), edit (active-only), `advance`, `lose`.

**Explicitly out of scope** (deferred, do not build here):
- The enquiry→quotation **convert** endpoint / any change to quotation-create. `Enquiry` exposes
  `markConverted()` for that later slice, but no controller wires `CONVERTED` in this slice — so
  in this slice a phone is freed for re-enquiry only via `LOST`.
- `activity` / `follow_up` entities (spec §2 "Activity") — their own later slice.
- **Record-level visibility filtering** on `assigned_to` — still open from P1a; enquiries inherit
  the same tenant-wide read behaviour (every user in a tenant reads every enquiry in it).
- **Cursor pagination** — enquiry list uses the same offset-based `Pageable` / `PageResponse` as
  the rest of the codebase.
- Any frontend.

## 3. Modules & conventions

Lives under `com.easycrm.sales` (+ `.web`, `.web.dto`) alongside the quotation/order aggregates —
the design spec §6 places `enquiry` in the `sales/` module. New shared plumbing: **none** — reuses
`BigDecimalStringModule`, `PageResponse`, `ValidationException`, `ConflictException`,
`NotFoundException`, `TenantScopedEntity`, the global `DataIntegrityViolationException`→409 handler
(challenge #15), and the P0 isolation stack unchanged.

Conventions carried forward, unchanged:
- **Tenant isolation is structural** — `Enquiry` extends `TenantScopedEntity` (`@TenantId` + RLS);
  ArchUnit enforces it. Never hand-write `WHERE tenant_id` — including in the list `Specification`,
  which adds only user filters on top of the tenant-scoped session.
- **Money is never a `double`** — `expected_value` is `NUMERIC(18,2)` in Postgres, `BigDecimal` in
  Java, JSON **string** on the wire via the global `BigDecimalStringModule` (no per-field
  annotation).
- **Cross-tenant reads return 404**, not 403/200 (P0 pattern); cross-tenant list returns empty.
- **`ddl-auto: validate`** — migration column types must match entity mappings exactly
  (`VARCHAR` for `String`, `NUMERIC(18,2)` for the money `BigDecimal`).

## 4. Domain model

### 4.1 `Enquiry` entity → table `enquiry`

`enquiry` is not a reserved SQL word, so class name and table name match (unlike `Order` →
`sales_order`, challenge #20).

| Column | Type | Null | Notes |
|--------|------|------|-------|
| `id` | UUID (v7) | — | from `BaseEntity` |
| `tenant_id` | UUID | — | `@TenantId`, from `TenantScopedEntity` |
| `customer_id` | UUID | **yes** | walk-ins / unknown-yet leads have none |
| `contact_name` | VARCHAR(200) | no | raw lead name |
| `contact_phone` | VARCHAR(20) | no | as entered, for display |
| `normalized_phone` | VARCHAR(10) | no | derived canonical key (see §5) |
| `contact_email` | VARCHAR(254) | yes | optional |
| `source` | VARCHAR(16) | no | `EnquirySource` enum |
| `requirement_text` | VARCHAR(2000) | yes | free text ("what they want") |
| `assigned_to` | UUID | yes | owning user; no FK (mirrors `customer.assigned_to`) |
| `stage` | VARCHAR(16) | no | `EnquiryStage` enum, default `NEW` |
| `expected_value` | NUMERIC(18,2) | yes | optional deal-size estimate; money |
| `lost_reason` | VARCHAR(500) | yes | required **only** on `→LOST` |
| `version` | — | — | `@Version` optimistic lock, from `BaseEntity` |
| auditing cols | — | — | created/updated, from `BaseEntity` |

`Enquiry` extends `TenantScopedEntity`, so ArchUnit's tenant-scoping test passes automatically.

### 4.2 Enums (both owned by `sales`, mirroring `QuotationStatus`/`OrderStatus`)

- **`EnquirySource`**: `INDIAMART, WHATSAPP, PHONE, REFERRAL, MANUAL, IMPORT`. A dedicated copy of
  the six values rather than reusing `crm.CustomerSource`, to keep `sales` decoupled from `crm`
  (each aggregate owns its enum, as `QuotationStatus`/`OrderStatus`/`VersionStatus` already do).
- **`EnquiryStage`**: `NEW, CONTACTED, QUALIFIED, CONVERTED, LOST`.

## 5. Phone normalization & dedupe

### 5.1 Normalization (deterministic, in the service)

`contact_phone` is **mandatory** — it is both the dedupe key and the WhatsApp/phone channel the
wedge is built on. Normalization:

1. Strip every non-digit character.
2. If the result is 12 digits and starts with `91`, drop the `91` (country code).
3. If the result is 11 digits and starts with `0`, drop the leading `0` (trunk prefix).
4. The result **must** be exactly 10 digits, else throw `ValidationException` (422, field
   `contactPhone`).

Store both `contact_phone` (raw, for display) and `normalized_phone` (the key). Editing the phone
re-runs normalization and re-checks dedupe.

### 5.2 Dedupe = one *active* enquiry per phone

**Invariant:** at most one enquiry per `(tenant_id, normalized_phone)` may be in a non-terminal
stage. A returning customer whose previous enquiry is `CONVERTED` or `LOST` can start a fresh one.

Enforced structurally by a **partial unique index** (Flyway `V20__enquiry.sql`):

```sql
CREATE UNIQUE INDEX uq_enquiry_tenant_active_phone
  ON enquiry (tenant_id, normalized_phone)
  WHERE stage NOT IN ('CONVERTED', 'LOST');
```

Service flow, mirroring P1a's duplicate-GSTIN/SKU pattern:
- **App-level pre-check:** find an active enquiry with the same normalized phone in-tenant; if one
  exists, throw `ConflictException` → **409** with a message naming the existing lead's id.
- **Backstop:** the pre-check is check-then-act (two concurrent creates can both pass it); the
  partial unique index plus the existing global `DataIntegrityViolationException`→409 handler
  (challenge #15) makes the invariant hold under concurrency.

Once the blocking enquiry moves to `CONVERTED`/`LOST` it leaves the index predicate, freeing the
phone. **Engineering-challenges #23** will document the partial-index-as-state-scoped-invariant
technique.

## 6. State machine

`EnquiryStage`: `NEW, CONTACTED, QUALIFIED` are **active**; `CONVERTED, LOST` are **terminal**.
Guards live in the entity (as `Quotation`'s `markSent`/`markAccepted` do); the service orchestrates.

- **Advance** to any *later active* stage (rank `NEW < CONTACTED < QUALIFIED`):
  `NEW→CONTACTED`, `NEW→QUALIFIED` (skip allowed), `CONTACTED→QUALIFIED`. No backward, no
  same-stage → **422**.
- **`→LOST`** from any active stage; **requires** a non-blank `lostReason` (else 422). Sets
  `lost_reason`.
- **`CONVERTED`** — `markConverted()` exists on the entity for the later conversion slice, but **no
  controller reaches it in this slice**.
- Any transition (or edit) attempted from a **terminal** stage → **422** (illegal-state, mapped
  like the quotation's illegal-transition 422).

```
NEW ──▶ CONTACTED ──▶ QUALIFIED ──▶ CONVERTED✶  (reserved; no endpoint this slice)
  │          │            │
  └──────────┴────────────┴──▶ LOST✶  (requires lost_reason)

✶ terminal — any further transition/edit → 422
```

## 7. REST API

Base path `/api/v1/enquiries`. All tenant-scoped; cross-tenant get → **404**, cross-tenant list →
empty. Request bodies validated (Jakarta Validation); money on the wire as JSON **string**.

| Method / path | Body | Result |
|---------------|------|--------|
| `POST /enquiries` | create fields | **201** + `EnquiryResponse`; dedupe → 409; bad phone/blank required → 422 |
| `GET /enquiries/{id}` | — | 200 / cross-tenant 404 |
| `GET /enquiries` | query `?stage=&assignedTo=&source=&page=&size=` | 200 `PageResponse<EnquiryResponse>` |
| `PATCH /enquiries/{id}` | editable header fields | 200; **active-stage only** (terminal → 422); phone change re-normalizes + re-dedupes |
| `POST /enquiries/{id}/advance` | `{ "stage": "CONTACTED" \| "QUALIFIED" }` | 200; illegal advance → 422 |
| `POST /enquiries/{id}/lose` | `{ "lostReason": "..." }` | 200; blank reason → 422 |

**Editable via `PATCH`** (active stages only): `customerId`, `contactName`, `contactPhone`,
`contactEmail`, `source`, `requirementText`, `assignedTo`, `expectedValue`. Not editable: `stage`
(transitions only), `lostReason` (set by `lose`).

### 7.1 List filtering — avoid the order-list bug

The deferred order/accept Minor is that `OrderService.list` **drops** `customerId` when `status` is
also supplied (a hand-rolled per-combination `if/else`). Enquiry has **three** optional filters, so
`list` is implemented with a JPA **`Specification`** that AND-composes whichever of `stage` /
`assignedTo` / `source` are present — any subset combines correctly by construction. Tenant scoping
still comes from `@TenantId` + RLS; the `Specification` adds only the user filters. A test supplies
two filters at once to prove the regression can't happen here.

## 8. Testing (TDD, Testcontainers + real Postgres/RLS)

- **Normalization:** `+91 98765 43210`, `098765 43210`, `9876543210` all → `9876543210`; a 9-digit
  input → 422 on `contactPhone`.
- **Dedupe:** 2nd active enquiry, same phone → 409; after the first is `LOST`, a fresh one succeeds
  (partial-index predicate leaves it out).
- **State machine:** valid advances incl. `NEW→QUALIFIED` skip; backward/same-stage → 422; `/lose`
  with blank reason → 422; edit or transition on a terminal enquiry → 422.
- **Isolation:** cross-tenant `GET` → 404, cross-tenant list → empty, using
  `TestTokens.provisionOwner(...)` (enquiry doesn't read `Tenant.state_code`, so a phantom owner
  would suffice, but `provisionOwner` is used for consistency).
- **List filters:** multi-filter query combines correctly (regression guard against the order-list
  bug).
- **Money:** `expectedValue` serializes as a JSON **string**.

## 9. Documentation obligations (same change, per CLAUDE.md)

- **`engineering-challenges.md` #23** — partial unique index as a state-scoped invariant ("one
  active enquiry per phone"): why a plain unique index is wrong (blocks legitimate repeat
  business), the partial predicate, and the check-then-act + `DataIntegrityViolationException`
  backstop.
- **`annotations-reference.md`** — add a row only if a genuinely new annotation appears
  (`@Column`/`Specification` usage is not new; likely no change).

## 10. Out-of-scope recap (do not build)

Convert endpoint / quotation-create changes · `activity` / `follow_up` · record-level visibility
filtering on `assigned_to` · cursor pagination · frontend.
