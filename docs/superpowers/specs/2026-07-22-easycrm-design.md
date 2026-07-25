# EasyCRM — Design Specification

**Status:** Design approved, pre-implementation
**Date:** 2026-07-22
**Stack:** React + TypeScript (frontend), Spring Boot + PostgreSQL (backend)

---

## 1. Product Shape & Scope

**EasyCRM** is a multi-tenant SaaS CRM for Indian tier-2/3 **distributors, traders and small manufacturers**. It is intended as a real, sellable product.

### Positioning

Vertical-first (distributors/traders), not horizontal — Zoho already owns cheap-and-horizontal in India. We ship opinionated defaults for this one vertical (pipeline stages, fields, GST-correct quotations, reports) so onboarding needs near-zero configuration.

One-line pitch: *"Your IndiaMART enquiries turn into GST quotations on WhatsApp in 60 seconds, and you never lose a follow-up again."*

### The hard boundary

Every target customer already runs **Tally**. If we touch invoicing, stock, e-way bills, or ledgers, we become an ERP and never ship.

**EasyCRM stops at the Order.**

```
IndiaMART / WhatsApp / phone / Excel
        ↓
    ENQUIRY  →  QUOTATION (v1, v2, v3…)  →  ORDER
        ↓            ↓                        ↓
    follow-ups   PDF on WhatsApp        [ handoff to Tally ]
```

**In scope:** enquiries, customers/contacts, product catalog, price lists, quotations, follow-ups, orders, import, reporting on that funnel.
**Explicitly out of scope:** stock/inventory, invoices, e-way bills, payments/ledger, dispatch, accounting. (A Tally *export bridge* may come later — a bridge, not a replacement.)

### Decomposition & release order

Six independently shippable subsystems:

| ID | Subsystem | Purpose |
|----|-----------|---------|
| **P0** | Tenancy & Identity | Signup, tenant provisioning, users, roles, JWT, isolation. Foundation. |
| **P1** | Enquiry → Quotation → Order core + Import | The wedge. Catalog, price lists, quotation engine, PDF, follow-ups, funnel, import. |
| **P2** | Channel integrations | IndiaMART pull, WhatsApp Business API, email. |
| **P3** | Billing & plans | Razorpay subscriptions, seat/usage limits, entitlements. |
| **P4** | Accounts 360 + repeat orders + collections | Expansion; needs P1 order history + Tally data. |
| **P5** | Field-rep mobile | Beat plans, geo check-ins, offline order capture. Separate (offline-first) architecture. |

**Releases:**
- **R1 = P0 + P1** — first sellable product. Gets customers off Excel; professional quotes; follow-ups stop leaking. Ships a zero-cost `wa.me` WhatsApp deep link.
- **R1.1 = P2** — IndiaMART first (the demo "wow"), then WhatsApp Business API, then email.
- **R1.2 = P3** — hand-invoice first ~20 tenants; don't build billing before there's someone to bill.
- **R2 = P4** — account depth (Customer 360, reorder + collection nudges).
- **R3 = P5** — field force (offline-first mobile).

The **WhatsApp Business API is deferred to R1.1** behind a port. It is a multi-week external business dependency (Meta/BSP onboarding, template approval, per-message cost). R1 ships a `wa.me` deep link that delivers ~80% of the value for ~zero cost. Nothing is thrown away.

**This spec covers the full architecture** so P2–P5 have somewhere to land, but specs **P0 + P1 in implementable detail**. The first implementation plan covers **P0**.

---

## 2. Domain Model

Every tenant-scoped table carries `tenant_id`, `created_at`, `updated_at`, `created_by`, and `version` (optimistic lock). Primary keys are **UUIDv7** — sortable, non-enumerable across tenants, no sequence contention.

### P0 — Tenancy & Identity

| Entity | Notes |
|--------|-------|
| `tenant` | `slug` (unique, login lookup), `business_name`, `gstin`, `state_code`, `plan`, `status` (TRIAL/ACTIVE/SUSPENDED), `trial_ends_at` |
| `user` | Belongs to exactly one tenant. `email` + `phone`, `password_hash`, `status`. Unique on `(tenant_id, email)`. |
| `role` | Fixed enum in R1: `OWNER`, `SALES_MANAGER`, `SALES_EXEC`. Custom roles are a P3 concern. |
| `invitation` | Token, expiry, invited role. |
| `refresh_token` | Rotating, revocable, hashed at rest. |
| `audit_log` | Append-only: actor, action, entity type/id, before/after JSONB, IP. |

### P1 — Sales Core

**Master data**
- `customer` — business name, GSTIN, `state_code` (drives IGST vs CGST/SGST), billing/shipping address, credit days, assigned owner, `price_list_id`, tags, `source`.
- `contact` — many per customer: name, phone, WhatsApp number, email, designation, `is_primary`.
- `product` — SKU, name, `hsn_code`, `uom`, `gst_rate`, `base_rate`, `is_active`.
- `price_list` / `price_list_item` — named lists ("Dealer", "Retail"), per-product override rate or discount %. **Differential pricing per customer is universal in this trade — not optional.**

**Funnel**
- `enquiry` — the lead. `customer_id` (nullable — walk-ins), raw contact fields, `source` (INDIAMART/WHATSAPP/PHONE/REFERRAL/MANUAL/IMPORT), `requirement_text`, `assigned_to`, `stage`, `expected_value`, `lost_reason`. Dedupe on normalized phone within tenant.
- `quotation` — logical document. `quote_no` (per-tenant, FY-based: `QT/25-26/0042`), `enquiry_id`, `customer_id`, `current_version_id`, `status` (DRAFT/SENT/ACCEPTED/REJECTED/EXPIRED).
- `quotation_version` — **immutable snapshot**. Version no., valid-until, payment/delivery terms, notes, computed totals, generated PDF ref. Traders revise 3–4×; must see exactly what was sent when.
- `quotation_item` — belongs to a *version*. Product **snapshot** (name/HSN/UOM copied, not referenced), qty, rate, discount, taxable value, GST rate, CGST/SGST/IGST amounts, line total.
- `order` — created by accepting a quotation version. `order_no`, PO reference, snapshot of accepted totals, `status` (CONFIRMED/DISPATCHED/CLOSED/CANCELLED). Dispatch/invoice deliberately not modelled.

**Activity**
- `activity` — polymorphic against enquiry/quotation/customer: `type` (CALL/WHATSAPP/EMAIL/VISIT/NOTE), body, outcome, `occurred_at`.
- `follow_up` — `due_at`, `assigned_to`, `status`, linked entity. First-class, with its own reminder scheduler. "You never lose a follow-up" is the promise.
- `attachment` — S3/MinIO object key (`tenant/{tenantId}/…`), filename, content type, size.

### Three details that bite if wrong

1. **GST:** `IGST` if `customer.state_code != tenant.state_code`, else `CGST + SGST` at half each. Round at the **line level** to 2 decimals, then sum (matches Tally). Rates `NUMERIC(18,4)`, amounts `NUMERIC(18,2)`, never `double`.
2. **Document numbering:** per-tenant, per-financial-year (Apr–Mar), **gapless**. A `document_sequence` table with a row lock per `(tenant_id, doc_type, fy)` — not a DB sequence (format resets annually; tenants expect no gaps).
3. **Snapshotting:** quotation items copy product name/HSN/rate rather than joining. A quote sent last month must render identically after a SKU rename.

---

## 3. Multi-Tenancy & Security Architecture

Isolation model: **shared schema + `tenant_id` discriminator** (one DB, one schema). Chosen over schema-/database-per-tenant for per-tenant cost and single-migration ops at thousands of low-ARPU tenants. Tenant resolution sits behind an interface, preserving a per-DB escape hatch for future enterprise tenants.

Four independent layers — any one failing should not leak data.

### Layer 1 — Tenant resolution (from JWT only)

Tenant comes from the **JWT claim only** — never a header, query param, or client-settable subdomain.

```
Request → JwtAuthFilter → validate token → TenantContext.set(tenantId, userId, role)
        → controller/service …
        → finally { TenantContext.clear() }   // mandatory: ThreadLocal + pooled threads
```

Login is `slug + email + password`. `TenantContext` is a `ThreadLocal` behind an interface (so a tenant promoted to its own DB changes only the resolver).

### Layer 2 — Hibernate discriminator

`@TenantId` on every tenant-scoped entity + a `CurrentTenantIdentifierResolver` reading `TenantContext`. Hibernate auto-appends `tenant_id = ?` to every query and auto-populates it on insert. Developers cannot forget it — they never write it.

### Layer 3 — Postgres Row-Level Security (the net)

Covers native queries, JDBC reporting, and future mistakes that bypass Hibernate.

```sql
ALTER TABLE quotation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quotation
  USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
```

The app connects as a **non-superuser role without `BYPASSRLS`**; Flyway migrations run under a separate owner role.

Pooled-connection detail: a custom `JpaTransactionManager` issues `SET LOCAL app.current_tenant = ?` in `doBegin`. `SET LOCAL` is transaction-scoped, so it auto-clears on commit/rollback — no leakage back into the pool. Non-transactional reads are configured transactional.

### Layer 4 — Build-time tests

- **ArchUnit:** every `@Entity` under `domain..` must declare a `@TenantId` field, unless in a `GLOBAL_TABLES` allowlist. New unscoped entity = red build.
- **Cross-tenant integration test:** authenticate as Tenant A, request Tenant B's resource → assert **404, not 403** (403 confirms existence).
- **RLS test:** raw JDBC query with no tenant setting returns zero rows.

### Async & scheduled work

`TenantContext` does not cross threads.
- `@Async` uses a `TenantAwareTaskDecorator` that copies then clears context.
- Scheduled jobs iterate tenants explicitly, each inside `TenantContext.runAs(tenantId, …)`. Never a global cross-tenant query.

### Security posture

| Concern | Approach |
|---------|----------|
| Auth | Argon2id passwords; 15-min access JWT; rotating refresh tokens (hashed at rest, revocable); rate-limited login (Bucket4j + Redis) |
| Authz | Method-level `@PreAuthorize` for role gating, **plus** a record-level visibility layer (see §6) |
| Files | MinIO/S3, keys `tenant/{tenantId}/…`, access via short-lived pre-signed URLs |
| DPDP Act | Per-tenant data export (JSON+CSV), hard-delete with 30-day grace, WhatsApp consent record, full audit log |
| Secrets | Per-tenant integration credentials (IndiaMART key, WABA token) column-encrypted with AES-GCM via a KMS-held key |

---

## 4. Data Import Module (first-class in P1)

Import is a **core module**, not an onboarding utility. It is the thing standing between assisted and self-serve onboarding (i.e. between ~₹30k and ~₹3k CAC), and bulk price-list updates are a recurring workflow.

### Pipeline (staged, resumable, reversible)

```
SOURCE → PARSE → MAP → VALIDATE+ENRICH → PREVIEW → COMMIT
                  ↑         ↓                          ↓
            saved template  error report           ROLLBACK
```

Raw rows land in `import_row` as JSONB first; nothing touches domain tables until commit. This enables preview, partial correction, and rollback.

### Entities

| Entity | Notes |
|--------|-------|
| `import_batch` | tenant, source type, entity type, file ref, status (PARSING/MAPPING/VALIDATED/COMMITTED/ROLLED_BACK/FAILED), counts, timings |
| `import_row` | batch, row number, `raw` JSONB, `normalized` JSONB, status, `matched_entity_id`, `action` (CREATE/UPDATE/SKIP) |
| `import_error` | row, column, code, message, severity (ERROR blocks / WARNING doesn't) |
| `import_mapping_template` | per tenant + source + entity: saved column mappings, auto-applied next time |

Every domain record created by import carries `import_batch_id` → rollback deletes/reverts everything tagged with that batch in one transaction.

### The port (makes Tally cheap later)

```java
interface MasterDataSource {
    SourceMetadata describe();
    Stream<RawRecord> read(ImportRequest request);
}
```

R1: `CsvSource`, `ExcelSource` (streaming POI — a 5,000-row catalog must not sit in heap).
Later, unchanged downstream: `TallyOdbcSource`, `BusyExportSource`. They inherit mapping, validation, preview, commit, rollback for free.

### Mapping, enrich, validate

- **Mapping:** header auto-detection via a fuzzy synonym dictionary; user confirms/corrects; corrections save as a template.
- **Enrichers (run before validators):** `GstinEnricher` (GSTIN → `state_code`, critical path), `HsnGstRateEnricher` (HSN → GST rate when blank, WARNING), `PhoneNormalizer` (E.164), `UomNormalizer` (`nos`/`pcs`/… → `PCS`).
- **Validators:** GSTIN checksum, HSN 4/6/8 digits, GST rate ∈ {0, 0.25, 3, 5, 12, 18, 28}, required fields, numeric ranges.

### Matching & modes

- Items match on `item_code`, else normalized name. Parties on GSTIN, else normalized phone, else fuzzy name (surfaced for confirmation, never auto-merged).
- Modes: `DRY_RUN`, `INSERT_ONLY`, `UPSERT`.

### Execution

Async (`@Async` + `TenantAwareTaskDecorator`), progress polled. Chunked `saveAll` + flush intervals, one transaction per chunk; batch marked `COMMITTED` only when all chunks succeed.

### Storage sizing & retention

Each `import_row` holds **one source row** as JSONB (a few hundred bytes to ~2–3 KB), not the whole file — so single-row size is never a concern, and PostgreSQL's TOAST would transparently compress/out-of-line any large value anyway (rows don't have to fit in the 8 KB page; a TOASTed field can reach ~1 GB). The real cost is **volume**: large imports across many tenants accumulate millions of disposable staging rows, and JSONB repeats its keys per row (mitigated by TOAST compression).

**Staging rows are transient. Retention rule:** once a batch reaches a terminal state (`COMMITTED` / `ROLLED_BACK` / `FAILED`), its `import_row`s and `import_error`s are purged — immediately after a successful commit, and via a scheduled sweep for older terminal batches (e.g. > 7 days). The `import_batch` summary row is kept for audit. This bounds the staging tables regardless of import throughput. (Note: this purge is distinct from rollback — rollback reverts *domain* records tagged by `import_batch_id`; retention cleans up the *staging* rows after the fact.)

### API

```
POST /api/v1/imports                 # upload → batchId
GET  /api/v1/imports/{id}            # status + progress
PUT  /api/v1/imports/{id}/mapping    # apply/confirm mapping
POST /api/v1/imports/{id}/validate   # run chain → error report
GET  /api/v1/imports/{id}/preview    # paginated, filter by status
POST /api/v1/imports/{id}/commit
POST /api/v1/imports/{id}/rollback
GET  /api/v1/imports/templates/{entity}   # downloadable CSV template
```

### Frontend

4-step wizard: **Upload → Map columns → Review errors → Confirm**. Review step: virtualized table, inline-editable cells, "errors only" filter, blunt summary bar (*"2,847 create · 12 update · 41 errors"*). Fix inline → revalidate → commit.

The internal onboarding SOP runs through this same wizard — no ops-only scripts or manual DB writes — so the importer is hardened by our own usage before self-serve launch.

---

## 5. Frontend Architecture

### Stack

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Build | Vite + React + TypeScript | Fast, boring, correct |
| Routing | React Router, route-level code splitting | Bundle size matters on tier-2 connections |
| Server state | TanStack Query | ~90% of state is server state; caching, refetch, optimistic updates |
| Client state | Zustand (small) | Auth session + UI prefs only |
| Forms | React Hook Form + Zod | Quotation builder is a nested field array (`useFieldArray`); Zod shared with API types |
| UI | shadcn/ui + Tailwind | Own the source, no runtime dep, Radix a11y, themeable per tenant |
| Tables | TanStack Table + virtualization | Import preview must render 3,000 rows |
| i18n | react-i18next, English + Hindi from day one | Retrofitting i18n is miserable |

### API contract — generated

springdoc → OpenAPI → `openapi-typescript` → typed fetch client. **CI fails if generated types drift.** Kills the frontend/backend DTO-disagreement bug class.

### Structure

Feature folders mirror backend modules:

```
src/
  app/            router, providers, error boundary, query client
  features/
    auth/ enquiries/ quotations/ customers/
    catalog/ imports/ orders/ settings/
      api/         # typed hooks
      components/
      pages/
      schema.ts    # zod
  components/ui/  # shadcn primitives
  lib/            # money, gst, date, phone formatting
```

### P1 screens

Login (`slug + email + password`) · Dashboard (role-aware) · Enquiries list + kanban · Enquiry detail · Quotation builder · Quotation preview · Customers list + 360 · Catalog · Price lists · Import wizard · Orders · Settings.

Dashboard is role-aware: `OWNER` sees funnel value/conversion/leaderboard; `SALES_EXEC` sees only today's follow-ups and my open quotes.

### Quotation builder (the screen that decides the product)

- **Keyboard-first** — Tab through line items, `Enter` adds a row, product field is type-ahead.
- Rate auto-fills from the customer's price list, stays overridable.
- Live totals for responsiveness, but **tax computation is authoritative on the server** — client preview is overwritten by the server response on save. Same Zod/Java rules; server always wins.
- Autosave draft every few seconds.
- "Revise" creates v2 prefilled from v1; v1 stays immutable.
- **PDF generation is server-side** — shown, emailed, WhatsApped output must be byte-identical.

### Auth in the browser

Access token **in memory** (never localStorage); refresh token in an **httpOnly, Secure, SameSite cookie**. A 401 triggers one refresh attempt, queues in-flight requests, then retries or hard-logs-out. Tenancy always derived server-side from the JWT.

### Performance budget (₹8k Android, patchy 4G)

- Initial JS < 200KB gzipped; route-split the rest.
- Skeletons not spinners; optimistic follow-up completion.
- `staleTime` tuned per resource (catalog/price lists cache hard; enquiries don't).
- CI tests a throttled "Slow 4G" profile.

### Mobile scope (no native app; no mobile experience required)

**V1 is responsive web** — not a PWA, not offline. Native app is unnecessary because **WhatsApp is the notification channel**. Three screens are mobile-optimized:

| Screen | Mobile? |
|--------|---------|
| My follow-ups / dashboard | Yes |
| Enquiry detail + log activity | Yes |
| Quotation view (read-only + share) | Yes |
| Quotation builder | No (desktop task) |
| Catalog, price lists, import, settings | No (admin, done seated) |

Offline order capture is **P5** (a distinct offline-first architecture — PWA/Capacitor), deliberately not half-built now.

### Errors & testing

Typed API errors → RHF `setError` per field; global error boundary; toasts for transient failures. **Vitest + Testing Library + MSW**; **Playwright** for four critical paths — login, enquiry→quote→send, import wizard on the dirty CSV, cross-tenant 404. The 404 runs in CI as an E2E regression test.

---

## 6. Backend Module Structure

### Modular monolith

One deployable Spring Boot app, internally split into well-bounded modules — not microservices (one team, thousands of small tenants), not a big ball of mud. Boundaries are ArchUnit-enforced so future extraction seams exist.

```
com.easycrm
  ├─ platform/                 # cross-cutting, tenant-agnostic
  │   ├─ tenancy/              # TenantContext, resolver, TenantAwareTransactionManager, TaskDecorator
  │   ├─ security/             # JWT filter, Argon2, @PreAuthorize config, rate limiting
  │   ├─ audit/                # audit-log aspect + store
  │   ├─ persistence/          # base entity, UUIDv7, auditing, money converters
  │   ├─ error/                # exception hierarchy + @RestControllerAdvice
  │   ├─ web/                  # ApiResponse envelope, pagination, filters
  │   └─ numbering/            # DocumentNumberingService (row-locked, per-FY)
  │
  ├─ iam/        # P0
  ├─ catalog/    # P1: product, price list
  ├─ crm/        # P1: customer, contact
  ├─ sales/      # P1: enquiry, quotation, version, order, follow_up, activity
  ├─ imports/    # P1: pipeline, MasterDataSource port, enrichers, validators
  ├─ channels/   # P2 (later): indiamart/, whatsapp/ — behind ports
  └─ billing/    # P3 (later)
```

`platform` is depended on by everyone. Feature modules must not depend on each other's internals — only on published service interfaces. ArchUnit: `sales` may call a `catalog` *service interface*, never its *repository or entity*.

### Layering inside a module

```
<module>/
  api/            # REST controllers + request/response DTOs (never expose entities)
  domain/         # entities, value objects, domain services, business rules
  application/    # use-case services orchestrating domain + persistence + events
  infrastructure/ # repositories, PDF generator, external adapters
```

Entities never leave `domain`; controllers speak DTOs via a MapStruct mapper. Lets the schema change without breaking the API contract the generated TS depends on.

### Record-level visibility layer

Distinct from tenant isolation. Within a tenant: `SALES_EXEC` sees records assigned to them, `SALES_MANAGER` their team, `OWNER` everything. A `VisibilitySpecification` is built from `TenantContext`'s role and composed into every list/read query via JPA Specifications.

```java
Specification<Enquiry> visible = visibilityService.forCurrentUser(Enquiry.class);
return repo.findAll(visible.and(userFilters), pageable);
```

RLS enforces the **tenant wall** (a security boundary); this layer enforces **intra-tenant visibility** (a product rule). Different jobs, different places. `@PreAuthorize` gates *whether a role may call an operation*; the visibility layer decides *which rows they see*.

### Error handling — one contract

Single exception hierarchy → one `@RestControllerAdvice` → consistent JSON envelope:

```
DomainException (400) · NotFoundException (404) · ConflictException (409, optimistic lock / duplicate)
ValidationException (422, field-level) · ForbiddenException (403)
```

```json
{ "error": { "code": "GSTIN_INVALID", "message": "…", "fields": { "gstin": "checksum failed" } } }
```

- **Cross-tenant access → 404, never 403** (403 confirms existence). "Not visible to you" maps to `NotFoundException`.
- Field errors carry a `fields` map → React drops each message on the right input.

### Domain events (in-process)

On quote acceptance, an order is created and an activity logged. The quotation service publishes `QuotationAcceptedEvent` via Spring `ApplicationEventPublisher` (in-memory, synchronous, same transaction); the order handler and audit logger subscribe. Adds new behaviour (e.g. P2 WhatsApp confirmation) as new subscribers, not edits to existing code. In-process now; the seam to a real broker exists if scale ever demands it.

### Reliability: retries & duplicates

- Quote-accept + order-create run in **one transaction** (atomic). Crash before commit → full rollback → user retry cleanly creates one order.
- Crash *after* commit but before the response reaches the user → order exists but no ack → user resend risks a **duplicate**. Mitigated by an **idempotency key** on quotation-accept and order-create: a retry with the same key returns the existing order instead of creating a twin.

### Async & scheduled jobs (each per-tenant, scoped)

| Job | Cadence | Notes |
|-----|---------|-------|
| Follow-up reminders | every few min | due follow-ups → notification / WhatsApp nudge |
| Quotation expiry | daily | `SENT` past valid-until → `EXPIRED` |
| IndiaMART poll (P2) | ~15 min | per-tenant creds, dedupe |
| Import execution | on demand | `@Async`, chunked commit, progress polled |
| Trial expiry (P3) | daily | `TRIAL` past `trial_ends_at` → `SUSPENDED` |

### Persistence & migrations

- **PostgreSQL + Flyway.** Migrations run under a separate owner role (has `BYPASSRLS`); runtime app role does not — this makes Layer 3 real.
- Money as `NUMERIC` via an `AttributeConverter`/`Money` type; never `double` (binary floats accumulate rounding errors across GST line items and mismatch Tally).
- `@Version` optimistic locking on everything editable.
- **Testcontainers** (real Postgres) so RLS and the transaction-manager behaviour are actually exercised, not mocked.

### API conventions

- `/api/v1`, resource-oriented, cursor pagination on lists.
- springdoc → OpenAPI → generated TS; CI fails on drift.
- Idempotency key on quotation-accept and order-create (double-tap safety on flaky 4G).

---

## 7. Portfolio / Demonstration Assets

Build order (each cheaper than the next; each stands alone): **diagram → four code files → two-tenant 404 → dirty-CSV wizard → core-loop recording.** Present order: **diagram → 404 → dirty CSV → four files**, with screen recordings as the primary delivery (live app is the encore — conference wifi kills live demos). All demo data is **synthetic and labelled as such**; never demo against real tenant data.

1. **Two-tenant seeded demo** — synthetic firms, checksum-valid-but-fake GSTINs. Log in as A, paste B's quote UUID → **404**; then raw `psql` query with no tenant setting → **zero rows** (proves the *database* enforces it, not just app code).
2. **Core-loop recording (3–5 min)** — enquiry → quote → revise → v2 snapshot → `wa.me` send.
3. **Import wizard on a deliberately dirty CSV** — invalid GSTIN checksums, five phone formats, mixed UOM casing, blank HSNs, a duplicate party — stopping on the preview screen showing fixed-vs-rejected, then two inline fixes and commit.
4. **Four code files** (not a repo tour): `TenantAwareTransactionManager.doBegin`, the ArchUnit rules, the cross-tenant negative tests, `DocumentNumberingService`.
5. **One architecture diagram** of the four isolation layers, each annotated with the failure mode it independently contains.

---

## 8. Open Items for the P0 Implementation Plan

- Concrete choice of Argon2id parameters (memory/iterations/parallelism) and JWT signing (RS256 vs HS256 + key rotation).
- `TenantAwareTransactionManager` implementation details and non-transactional-read configuration.
- Signup/provisioning flow: tenant seeding with distributor-vertical defaults.
- `document_sequence` locking strategy under concurrency (verified with a Testcontainers concurrency test).
- ArchUnit rule set + `GLOBAL_TABLES` allowlist.
- Cross-tenant 404 integration test + RLS zero-rows test as the first tests written.
