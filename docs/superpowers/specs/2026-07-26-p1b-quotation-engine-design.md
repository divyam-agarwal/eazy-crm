# EasyCRM P1b — Quotation Engine Design

**Status:** Design approved, pre-implementation
**Date:** 2026-07-26
**Parent spec:** `2026-07-22-easycrm-design.md` (§2 domain model, §6 backend structure, §7 core loop)
**Depends on:** P0 isolation + P0-auth + **P1a master data** (all merged on `main`)

---

## 1. Context & purpose

P1 (Sales Core) is decomposed into three independently shippable slices (from the P1a
spec §1):

| Slice | Contents | Status |
|-------|----------|--------|
| **P1a — Master data** | `product`, `customer` + `contact`, `price_list` / `price_list_item` | **merged** |
| **P1b — Quotation engine** | `quotation` / `version` / `item`, price resolution, GST computation, gapless numbering, money wire format | **this spec** |
| **P1c — Import module** | staged pipeline, enrichers/validators, wizard backend | later |

**This slice is scoped to the quotation engine only** (confirmed with the user), not the
full enquiry→quote→order loop. It reads the P1a master data (customer, product, price
list) and produces GST-correct, versioned quotations that can be built, edited, sent, and
revised — **stopping at a SENT quote**. The two ends of the loop are deferred to their own
follow-on slice:

- **`enquiry`** (the lead-capture front door) — deferred. `quotation.enquiry_id` is added
  now as a **nullable forward-compat column**; quotes are created directly against a
  customer for now.
- **`order` + accept flow** (the money-earning exit) — deferred as one coherent unit:
  `order` entity, the accept transition, `ACCEPTED` status, `QuotationAcceptedEvent`
  (spec §ops), and the idempotency key. Built alongside each other next.

**Why the quotation engine is the right unit.** It is the hard, coherent core: immutable
versioning, price resolution, the GST split, gapless per-tenant numbering, and the
money-as-string wire format. Each is a self-contained problem with a well-defined
interface. Bolting on enquiry (trivial CRUD) or order-accept (its own transactional /
idempotency story) would enlarge the plan without making this core more complete. The
demoable loop finishes one slice later; this slice makes the quote itself real and correct.

---

## 2. Scope

**In scope**
- Entities: `quotation`, `quotation_version`, `quotation_item`, and a `document_counter`
  support table.
- **Price resolution** (`PriceResolver`): customer + product → effective default rate,
  applying the price list's override/discount; the P1a-deferred computation, built here
  next to its only consumer.
- **GST computation** (`GstCalculator`): IGST vs CGST/SGST split, round-at-line then sum.
- **Gapless per-tenant/FY document numbering** (`DocumentNumberService`) for `quote_no`,
  assigned on first SEND.
- **Money-as-JSON-string wire format** (global Jackson-3/Boot-4 serializer) — the
  P1a-deferred wire contract (challenge #2), retrofitting existing P1a money responses.
- Quotation lifecycle: create DRAFT → edit DRAFT → send (freeze + number) → revise
  (new DRAFT version) → reject / expire (manual).
- REST API + per-table migrations with RLS, mirroring the P1a pattern.

**Out of scope (deferred to their natural home)**
- **`enquiry` entity** — later slice. `enquiry_id` stored as a nullable column now.
- **`order`, the accept flow, `ACCEPTED` status, `QuotationAcceptedEvent`, idempotency
  key** — the order slice (next). This slice's status set stops at `SENT` (+ terminal
  `REJECTED`/`EXPIRED`).
- **PDF generation** and the **`wa.me` WhatsApp deep link** — P2.
- **Scheduled auto-expiry** (spec §ops daily job: `SENT` past `valid_until` → `EXPIRED`).
  Only **manual** expire is built here; the scheduled job comes with the ops/scheduling work.
- **Record-level visibility filtering** (`customer.assigned_to`) — P0-auth follow-up.
- **Cursor pagination** — offset `PageResponse` (P1a pattern) is used; revisit for
  high-volume feeds.

---

## 3. Modules & conventions

Follow the **shipped `catalog`/`crm` convention** (P1a): a flat module package with a
`web` subpackage for controllers and `web.dto` for DTO records; **DTOs hand-mapped**
(small private `toResponse(...)` methods) — no MapStruct.

```
com.easycrm.sales                Quotation, QuotationVersion, QuotationItem,
                                 QuotationStatus, VersionStatus,
                                 *Repository, *Service (QuotationService),
                                 PriceResolver, GstCalculator, DocumentNumberService,
                                 DocumentCounter, web/QuotationController, web/dto/*
com.easycrm.platform.money       BigDecimalStringModule (global Jackson serializer)
```

All tenant-scoped entities extend `TenantScopedEntity` (covered automatically by
`TenantScopingArchTest` — no `GLOBAL_TABLES` entry). Money/amount columns `NUMERIC(18,2)`,
rate/percent columns `NUMERIC(18,4)`, qty `NUMERIC(18,3)`; never `double`. `ddl-auto:
validate` is on — migration column types must match entity mappings exactly.

**FK columns are bare `UUID`s with no DB foreign-key constraints**, matching all shipped
tables. Same-tenant referential integrity is structural (RLS means another tenant's ids
are unreadable, hence unreferenceable; UUIDv7 ids are non-enumerable).

---

## 4. Entities

### 4.1 `quotation` — the logical document (mutable pointer + status)

| Column | Type | Notes |
|--------|------|-------|
| `quote_no` | `VARCHAR` | **nullable** until first SEND; unique `(tenant_id, quote_no)` when present |
| `customer_id` | `UUID` | required |
| `enquiry_id` | `UUID` | nullable (forward-compat; enquiry is a later slice) |
| `current_version_id` | `UUID` | points at the latest version |
| `status` | `VARCHAR(16)` | `QuotationStatus`: `DRAFT` / `SENT` / `REJECTED` / `EXPIRED` |

`ACCEPTED` is intentionally **absent** from the enum in this slice — it arrives with the
order/accept flow (adding it early would imply a transition with no implementation).

### 4.2 `quotation_version` — the snapshot (mutable while parent DRAFT, frozen on SEND)

| Column | Type | Notes |
|--------|------|-------|
| `quotation_id` | `UUID` | required |
| `version_no` | `INT` | 1, 2, 3… per quotation; unique `(tenant_id, quotation_id, version_no)` |
| `status` | `VARCHAR(16)` | `VersionStatus`: `DRAFT` / `SENT` |
| `valid_until` | `DATE` | nullable |
| `payment_terms` | `TEXT` | nullable |
| `delivery_terms` | `TEXT` | nullable |
| `notes` | `TEXT` | nullable |
| `place_of_supply` | `VARCHAR(2)` | **snapshot** of the *customer* `state_code` (the destination state) at version creation; compared against the tenant's (supplier's) `state_code` to drive IGST vs CGST/SGST, so a later customer-profile edit cannot retro-change a sent split |
| `sub_total` | `NUMERIC(18,2)` | computed, stored |
| `total_tax` | `NUMERIC(18,2)` | computed, stored |
| `grand_total` | `NUMERIC(18,2)` | computed, stored |
| `sent_at` | `TIMESTAMPTZ` | nullable; stamped on SEND |

**Freeze rule:** a version is mutable only while its parent `quotation.status == DRAFT`
and its own `status == DRAFT`. On SEND both flip and the version is immutable forever.
Any write path targeting a non-DRAFT version → **422**.

### 4.3 `quotation_item` — belongs to a *version*, carries a product snapshot

Snapshotting (spec §7.1): name/HSN/UOM/gst_rate are **copied** at add-time, not
FK-joined, so a later SKU rename or price change never alters a sent quote.

| Column | Type | Notes |
|--------|------|-------|
| `version_id` | `UUID` | required |
| `product_id` | `UUID` | reference only (for reorder/analytics); display uses snapshots |
| `name_snapshot` | `VARCHAR` | copied |
| `hsn_snapshot` | `VARCHAR` | copied |
| `uom_snapshot` | `VARCHAR(16)` | copied |
| `qty` | `NUMERIC(18,3)` | > 0 |
| `rate` | `NUMERIC(18,2)` | price-resolved default, overridable per line |
| `discount_pct` | `NUMERIC(18,4)` | per-line, nullable (0 if absent) |
| `gst_rate` | `NUMERIC(18,4)` | snapshot from product; ∈ {0, 0.25, 3, 5, 12, 18, 28} |
| `taxable_value` | `NUMERIC(18,2)` | computed per line |
| `cgst` | `NUMERIC(18,2)` | computed per line |
| `sgst` | `NUMERIC(18,2)` | computed per line |
| `igst` | `NUMERIC(18,2)` | computed per line |
| `line_total` | `NUMERIC(18,2)` | computed per line |

### 4.4 `document_counter` — gapless numbering support (tenant-scoped)

| Column | Type | Notes |
|--------|------|-------|
| `doc_type` | `VARCHAR(16)` | `QUOTE` (extensible: `ORDER` later) |
| `fy` | `VARCHAR(7)` | Indian FY label, e.g. `25-26` |
| `next_val` | `BIGINT` | next number to assign |

Unique `(tenant_id, doc_type, fy)`. Extends `TenantScopedEntity` (has `tenant_id`, RLS).
Read/incremented under a pessimistic row lock — see §5C.

---

## 5. Algorithms

### A. Price resolution — `PriceResolver`

Input `(customerId, productId)` → effective default rate + product snapshot fields.

1. Load the customer; read its `price_list_id` (nullable).
2. If a price list exists **and** has a `PriceListItem` for the product:
   - `override_rate` set → effective = `override_rate` (absolute).
   - `discount_pct` set → effective = `base_rate × (1 − discount_pct/100)`, `HALF_UP` 2 dp.
3. Otherwise → effective = `product.base_rate`.

The result is a **default**; the line item's `rate` in the request overrides it. This is
the "price resolution" the P1a spec deferred to be built next to its only consumer.

### B. GST computation — `GstCalculator` (pure function, no DB)

Per line, in order (all rounding `HALF_UP`):

1. `taxable_value = round(qty × rate × (1 − line_discount_pct/100), 2)` — **round at the line**.
2. Intra-state (`version.place_of_supply == tenant.state_code`):
   `cgst = sgst = round(taxable_value × gst_rate / 2 / 100, 2)`, `igst = 0`.
   Inter-state: `igst = round(taxable_value × gst_rate / 100, 2)`, `cgst = sgst = 0`.
3. `line_total = taxable_value + cgst + sgst + igst`.
4. Totals = **sum of the already-rounded line values** (`sub_total`, `total_tax`,
   `grand_total`). Round-at-line-then-sum, never sum-then-round — matches Tally
   (challenge #2). The **customer** `state_code` is snapshotted onto the version as
   `place_of_supply` at creation; the tenant's (supplier's) `state_code` is read from the
   `Tenant` at compute time. They are equal → intra-state (CGST/SGST); different →
   inter-state (IGST). Sent versions never recompute, so their stored split is frozen.

### C. Gapless numbering — `DocumentNumberService`

On **first SEND**, inside the send transaction:

1. Derive FY from the send date (Indian FY: Apr 1 – Mar 31 → label `YY-YY`).
2. `SELECT … FOR UPDATE` the `document_counter` row for `(tenant, 'QUOTE', fy)`,
   inserting it (`next_val = 1`) if absent.
3. Format `QT/{fy}/{next_val:04d}` (e.g. `QT/25-26/0042`), assign to `quotation.quote_no`,
   increment `next_val`.

The pessimistic row lock serializes concurrent sends within a tenant/FY → **gapless**. A
rolled-back send releases the lock without having consumed a number (the increment rolls
back with it). Implemented via a native `SELECT … FOR UPDATE` finder in
`DocumentCounterRepository` (`@Lock(PESSIMISTIC_WRITE)`), run in the send transaction so
the tenant GUC is set (challenge #8). `version_no` is assigned separately as
`max(version_no)+1` within the quotation at version creation — naturally gapless per doc.

### D. Money-as-JSON-string wire format — `BigDecimalStringModule` (global)

A Jackson-3/Boot-4 module registered app-wide that serializes every `BigDecimal` as a
JSON **string** via `toPlainString()` (no scientific notation, preserves scale).
Deserialization accepts a JSON string (or number, tolerantly). This closes the
challenge-#2 wire gap the whole system has carried since P1a — and **retrofits P1a's
existing `Product`/`PriceListItem` responses**, which currently emit raw JSON numbers.
Safe to change now: no frontend consumes them yet. Boot 4 ships Jackson under
`tools.jackson` (challenge #10) — the module targets that API.

---

## 6. API surface

Base path `/api/v1`. Cross-tenant access → **404** (existing `NotFoundException` mapping).
List endpoints use offset `PageResponse` (P1a pattern).

- `POST /quotations` — create a DRAFT: `{ customerId, enquiryId?, header fields, items[] }`.
  Resolves rates (unspecified line rates default via `PriceResolver`), computes totals,
  creates v1 as DRAFT. Returns the quotation + current version + items.
- `GET /quotations/{id}` — quotation + current version + items.
- `GET /quotations` — paginated; filters `status`, `customerId`.
- `GET /quotations/{id}/versions` — version history (list).
- `GET /quotations/{id}/versions/{versionNo}` — a specific version; sent versions render
  exactly as sent.
- `PATCH /quotations/{id}` — edit DRAFT header fields (`valid_until`, terms, notes).
  **422 if parent not DRAFT.**
- `PUT /quotations/{id}/items` — replace the DRAFT current-version line items; recomputes
  totals. **422 if parent not DRAFT.**
- `POST /quotations/{id}/send` — freeze current version, assign gapless `quote_no`,
  status → `SENT`, stamp `sent_at`. Guard: sending a non-DRAFT quotation is an illegal
  transition → **422** (no double-number).
- `POST /quotations/{id}/revise` — from a SENT quotation, create a new DRAFT version
  (`version_no + 1`) **copied from** the last version's items (traders tweak, not
  restart), point `current_version_id` at it, status → `DRAFT`. `quote_no` is retained.
- `POST /quotations/{id}/reject` — `SENT` → `REJECTED` (terminal).
- `POST /quotations/{id}/expire` — `SENT` → `EXPIRED` (terminal; manual — scheduled
  auto-expiry deferred).

Validation: structurally malformed body → **400** (`@Valid`); semantically invalid
(qty ≤ 0, gst_rate off the allowed set, illegal status transition, editing a frozen
version) → **422** (`ValidationException`, P1a pattern). Duplicate `quote_no` under the
unique constraint → **409** (existing global `DataIntegrityViolationException` handler,
challenge #15) — a backstop; the locked counter is the primary guarantee.

---

## 7. Testing (TDD, real Postgres + RLS)

Every task follows the project TDD loop: failing test → confirm-fail → minimal code →
pass → commit. Use the `IntegrationTest` base (singleton Testcontainers Postgres, app
connects as the non-`BYPASSRLS` `easycrm_app` role). JSON asserted via jayway `JsonPath`
(challenge #10).

Unit (no DB):
- **`GstCalculatorTest`** — intra vs inter-state split; the round-at-line-then-sum vs
  sum-then-round divergence case (the money-correctness proof); 0% and 28% rates; per-line
  discount; multi-line totals.
- **`PriceResolverTest`** — override wins; discount math + rounding; fallback to
  `base_rate`; customer with no price list; price list missing the product.
- **Money-wire serializer test** — a `BigDecimal` field serializes as a quoted string in
  plain notation.

Integration (real Postgres + RLS):
- **`DocumentNumberServiceTest`** — sequential numbering; FY rollover resets to `0001`;
  **gaplessness across a rolled-back send** (no consumed number); a **concurrency test**
  (two simultaneous sends race → two distinct consecutive numbers, no duplicate — proves
  the `FOR UPDATE` lock).
- Repository + **RLS zero-rows** tests per entity (raw query, no tenant set → nothing).
- **Immutability test** — editing items / header of a SENT version → 422; the sent
  snapshot is unchanged.
- **Lifecycle test** — create → edit → send (number assigned, frozen) → revise (v2 DRAFT,
  copied items, same quote_no) → reject/expire; illegal transitions → 422.
- **Cross-tenant 404** — `GET /quotations/{id}` for another tenant's quotation → 404.

---

## 8. Working-agreement checkpoints

- **Commits:** author `divyam <divyam.0444@gmail.com>`, no Claude/AI mention; one task
  per commit; TDD.
- **Engineering-challenges log** (same change): expected candidates — the **gapless
  counter under concurrency + rollback** (pessimistic lock, insert-if-absent, tenant GUC);
  **round-at-line-then-sum GST** (if not already fully captured by challenge #2, capture
  the line-level split specifics); the **mutable-DRAFT / frozen-SENT version invariant**;
  the **global BigDecimal-as-string serializer** on Jackson 3 / Boot 4.
- **Annotations reference:** add rows for any new annotation — likely `@Lock`
  (`LockModeType.PESSIMISTIC_WRITE`), `@Enumerated(EnumType.STRING)`, and whatever the
  Jackson module registration uses.
- **Tenant isolation stays structural:** new entities extend `TenantScopedEntity`; never
  hand-write `WHERE tenant_id`; RLS reads (incl. the counter `SELECT … FOR UPDATE`) run in
  a tenant-bound transaction.

---

## 9. Estimated shape

~18–22 tasks. Suggested order (no-entity-dependency primitives first):

1. Money-wire serializer (`BigDecimalStringModule`) + its test — global, unblocks every
   later money assertion and retrofits P1a.
2. `GstCalculator` (pure) + tests.
3. `DocumentCounter` migration + entity + repo + `DocumentNumberService` + concurrency /
   rollback tests.
4. `PriceResolver` + tests.
5. Aggregate: migrations (V14–V17) → entities → repositories (+ RLS/repo tests).
6. `QuotationService` create/edit → controller (+ controller tests).
7. Send (freeze + number), revise, reject/expire (+ lifecycle, immutability, concurrency
   integration tests).
8. Cross-tenant 404 integration test.

Exact breakdown is the job of the implementation plan (writing-plans).
