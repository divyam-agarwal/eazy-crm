# EasyCRM P1a — Master Data (Catalog + CRM) Design

**Status:** Design approved, pre-implementation
**Date:** 2026-07-25
**Parent spec:** `2026-07-22-easycrm-design.md` (§2 domain model, §6 backend structure)
**Depends on:** P0 tenant-isolation foundation + P0-auth core (both merged)

---

## 1. Context & purpose

P1 (Sales Core) is too large for one implementation plan, so it is decomposed into
three independently shippable slices, built in order:

| Slice | Contents | Status |
|-------|----------|--------|
| **P1a — Master data** | `product` (catalog), `customer` + `contact` (crm), `price_list` / `price_list_item` | **this spec** |
| **P1b — Quotation engine** | `enquiry`, `quotation` / `version` / `item`, GST computation, gapless document numbering, `order` + accept flow | later |
| **P1c — Import module** | staged pipeline, `MasterDataSource` port, enrichers/validators, wizard backend | later |

**Why master data first.** The demoable core loop (enquiry → quote → revise → order,
spec §7.2) reads all of this master data — the quotation builder auto-fills line-item
rates from a customer's price list, and the GST split depends on `customer.state_code`.
None of the loop is demoable without the data underneath it. P1a is also the first set
of **real tenant-scoped entities** on the isolation foundation, finally taking over the
role currently played by the throwaway `DemoRecord`.

**Goal driving this slice:** move toward a demoable product loop (confirmed with the
user). P1a itself is not the demo — it is the unavoidable first leg; P1b delivers the
visible loop.

---

## 2. Scope

**In scope**
- Entities: `product`, `customer`, `contact`, `price_list`, `price_list_item`.
- REST CRUD for each (`create` / `get` / `list` / `update`; soft-deactivate where
  `is_active` applies; hard-delete for sub-records — contacts, price-list items).
- GSTIN checksum validation + `state_code` derivation (a `Gstin` value type in
  `platform.gst`, reused later by the P1c import validators).
- Field-level domain validation with a `ValidationException` (422 + `fields` map).
- Per-entity migrations with RLS policies, mirroring `V6__app_user.sql`.

**Out of scope (deferred to their natural home)**
- **Price resolution** (customer + product → effective rate applying override/discount):
  P1b, built alongside its only consumer, the quote builder. P1a stores the price-list
  data but computes nothing from it.
- **Enquiries, quotations, orders, document numbering:** P1b.
- **Bulk import** (CSV/Excel, mapping, enrichers, preview/commit/rollback): P1c. GSTIN
  validation is built here and reused there.
- **Record-level visibility filtering** (`SALES_EXEC` / `SALES_MANAGER`): P0-auth
  follow-up. `customer.assigned_to` is stored as a column now but is **not** yet used to
  filter reads — P1a is tenant-scoped only (everyone in a tenant sees all master data).
- **Tags on customers:** YAGNI for P1a; add when a consumer needs them.
- **Retiring `DemoRecord`:** it still backs the isolation and portfolio (two-tenant 404)
  tests. A later cleanup can re-point those tests at real entities and delete it. P1a
  leaves it untouched to avoid destabilizing the passing isolation suite.

---

## 3. Modules & conventions

Follow the **actual `iam` module convention**, not the aspirational spec §6 structure:
a flat module package with a `web` subpackage for controllers and `web.dto` for DTO
records; **DTOs are hand-mapped** (small private `toResponse(...)` methods / constructor
calls) — **no MapStruct**. Rationale: consistency with shipped code, ~5 small entities
don't justify a code generator, and an annotation processor is exactly the kind of tool
that has silently lagged this bleeding-edge toolchain (challenges #4/#7/#10). Adding
MapStruct later, if entity count balloons in P1b/P1c, is a clean isolated change.

```
com.easycrm.catalog          Product, PriceList, PriceListItem, Uom,
                             *Repository, *Service, web/*Controller, web/dto/*
com.easycrm.crm              Customer, Contact, CustomerSource,
                             *Repository, *Service, web/*Controller, web/dto/*
com.easycrm.platform.gst     Gstin (value type: checksum validation + stateCode()),
                             StateCode (valid GST state-code set)
com.easycrm.platform.error   ValidationException (new) + a handler method
```

All entities extend `TenantScopedEntity`, so the existing `TenantScopingArchTest`
covers them automatically — no `GLOBAL_TABLES` allowlist entries. Money and rates are
`BigDecimal` / `NUMERIC`, never `double` (challenge #2). `ddl-auto: validate` is on, so
migration column types must match entity mappings exactly.

**FK columns are bare `UUID`s with no DB foreign-key constraints**, matching
`app_user` / `refresh_token`. Same-tenant referential integrity is structural: RLS means
you can only read your own tenant's rows, so you cannot obtain (hence cannot reference)
another tenant's id, and UUIDv7 ids are non-enumerable.

---

## 4. Entities

### 4.1 `product` (catalog)

| Column | Type | Notes |
|--------|------|-------|
| `sku` | `VARCHAR` | unique `(tenant_id, sku)` |
| `name` | `VARCHAR` | required |
| `hsn_code` | `VARCHAR` | 4, 6, or 8 digits |
| `uom` | `VARCHAR(16)` | `Uom` enum (STRING) |
| `gst_rate` | `NUMERIC(18,4)` | ∈ {0, 0.25, 3, 5, 12, 18, 28} |
| `base_rate` | `NUMERIC(18,2)` | ≥ 0 |
| `is_active` | `BOOLEAN` | default true |

`Uom` enum: canonical set (e.g. `PCS`, `KG`, `NOS`, `MTR`, `LTR`, `BOX`, `SET`, `DOZEN`,
`PACK`). Direct entry accepts a canonical value; fuzzy normalization (`nos`→`PCS`, etc.)
is the P1c importer's job, not P1a's.

### 4.2 `customer` (crm)

| Column | Type | Notes |
|--------|------|-------|
| `business_name` | `VARCHAR` | required |
| `gstin` | `VARCHAR(15)` | nullable (unregistered buyers exist); unique `(tenant_id, gstin)` when present; **checksum-validated** |
| `state_code` | `VARCHAR(2)` | validated against the GST state-code set; derived from GSTIN prefix when present, else entered; must equal GSTIN prefix when both given |
| `billing_address` | `TEXT` | nullable |
| `shipping_address` | `TEXT` | nullable |
| `credit_days` | `INT` | default 0 |
| `assigned_to` | `UUID` | user id, nullable (not yet used for visibility) |
| `price_list_id` | `UUID` | nullable |
| `source` | `VARCHAR(16)` | `CustomerSource` enum: INDIAMART/WHATSAPP/PHONE/REFERRAL/MANUAL/IMPORT |
| `is_active` | `BOOLEAN` | default true |

### 4.3 `contact` (crm)

Many per customer. Columns: `customer_id` (UUID), `name`, `phone`, `whatsapp_number`,
`email`, `designation`, `is_primary` (BOOLEAN). Hard-deletable (sub-record of customer).

### 4.4 `price_list` (catalog)

Columns: `name` (unique `(tenant_id, name)`), `is_active`.

### 4.5 `price_list_item` (catalog)

Columns: `price_list_id` (UUID), `product_id` (UUID), `override_rate NUMERIC(18,2)`
**XOR** `discount_pct NUMERIC(18,4)`. Unique `(tenant_id, price_list_id, product_id)`.
Exactly one of override/discount is set — enforced at **two layers**: a DB
`CHECK (num_nonnulls(override_rate, discount_pct) = 1)` and app-level `ValidationException`.

---

## 5. Validation & the `Gstin` value type

- **`platform.gst.Gstin`** — a small value type that parses a 15-char GSTIN, validates
  its **mod-36 checksum** (the last character), and exposes `stateCode()` (first two
  chars). Invalid input throws `ValidationException`. Reused by the P1c import validators.
- **`platform.gst.StateCode`** — the set of valid Indian GST state codes; validates a
  standalone `state_code` and cross-checks it against a GSTIN prefix.
- **`platform.error.ValidationException`** — new domain exception carrying a
  `Map<field, message>`. Mapped by `ApiExceptionHandler` to **HTTP 422** with a `fields`
  map. This is distinct from bean-validation `@Valid` failures, which the handler already
  maps to **400** (`VALIDATION_FAILED`): structurally malformed request → 400; semantically
  invalid values (bad GSTIN checksum, `gst_rate` off the allowed set, override/discount
  both set) → 422. Honors spec §6's field-level-validation contract.

Product validators: HSN digit-length ∈ {4,6,8}, `gst_rate` in the allowed set,
`base_rate ≥ 0`. Customer validators: GSTIN checksum + state_code consistency.
Conflicts (duplicate `sku`, duplicate `gstin`, duplicate price-list `name`) →
`ConflictException` (409, existing).

---

## 6. API surface

Base path `/api/v1`. One migration + RLS policy per table.

- **products** — `POST /products`, `GET /products/{id}`, `GET /products` (paginated,
  optional `active` filter), `PUT /products/{id}`, `POST /products/{id}/deactivate`,
  `POST /products/{id}/activate`.
- **customers** — same CRUD shape + activate/deactivate. Contacts nested:
  `POST /customers/{id}/contacts`, `GET /customers/{id}/contacts`,
  `PUT /customers/{id}/contacts/{cid}`, `DELETE /customers/{id}/contacts/{cid}`.
- **price-lists** — CRUD + activate/deactivate. Items nested:
  `POST /price-lists/{id}/items`, `GET /price-lists/{id}/items`,
  `DELETE /price-lists/{id}/items/{iid}`.

**Pagination:** offset-based Spring Data `Pageable` for P1a master-data lists — a
deliberate, flagged deviation from the parent spec's cursor pagination. Cursor pagination
matters for high-volume append-only feeds (enquiries, P1b), not for bounded master data;
revisit it where it earns its cost.

Cross-tenant access to any resource → **404, not 403** (existing `NotFoundException`
mapping; a "not visible to you" record is indistinguishable from a missing one).

---

## 7. Testing (TDD, real Postgres)

Every task follows the project's TDD loop: failing test → confirm-fail → minimal code →
pass → commit. Use the shared `IntegrationTest` base (singleton Testcontainers Postgres,
app connects as the non-`BYPASSRLS` `easycrm_app` role).

Per entity:
- **Repository test** — save + finder in a **tenant-bound transaction** (challenge #8:
  RLS-scoped derived finders need `@Transactional(readOnly = true)` or they silently
  return zero rows).
- **RLS zero-rows test** — a raw query with no tenant setting returns nothing.
- **Controller test** — MockMvc, JSON assertions via jayway `JsonPath` (challenge #10:
  avoid the Jackson-3 mapper API in tests).

Plus:
- **Cross-tenant 404** integration test against the real `Customer` through its
  controller (a stronger version of the existing `DemoRecord` one).
- **`GstinTest`** — unit test the checksum against known-valid and known-invalid GSTINs
  (including a valid-format-but-bad-checksum case), and `stateCode()` extraction.
- **`ValidationException` handler test** — asserts 422 + `fields` shape.

---

## 8. Working-agreement checkpoints

- **Commits:** author `divyam <divyam.0444@gmail.com>`, no Claude/AI mention; one task
  per commit.
- **Engineering-challenges log:** append (same change) for anything non-obvious.
  Expected candidates: the **GSTIN mod-36 checksum** algorithm; the
  `override_rate` XOR `discount_pct` invariant enforced at two layers; possibly the
  state_code/GSTIN-prefix consistency rule.
- **Annotations reference:** add a row for any new Spring/JPA/Hibernate annotation P1a
  introduces (e.g. if a `@Check` constraint annotation or new validation annotation
  appears).
- **Tenant isolation stays structural:** new entities extend `TenantScopedEntity`; never
  hand-write `WHERE tenant_id`; RLS reads run in a tenant-bound transaction.

---

## 9. Estimated shape

~12–15 tasks: the `Gstin`/`StateCode` value types + `ValidationException` first (no
entity deps), then each entity as migration → entity → repository (+ RLS/repo tests) →
service → controller (+ controller test), then the cross-tenant 404 integration test.
The exact task breakdown is the job of the implementation plan (writing-plans).
