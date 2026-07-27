# EasyCRM P1 — Order + Accept Slice Design

**Status:** Design approved, pre-implementation
**Date:** 2026-07-27
**Parent spec:** `2026-07-22-easycrm-design.md` (§2 domain model, §6 backend structure, §7 core loop)
**Depends on:** P0 isolation + P0-auth + P1a master data + **P1b quotation engine** (all merged on `main`)

---

## 1. Context & purpose

The product wedge is **enquiry → quotation → order**. P1a built the master data, P1b built
the quotation engine and stopped at a `SENT` quote. This slice completes the wedge's **tail**:
accepting a sent quotation turns it into an **order** — the money-earning exit.

The wedge's **head** (`enquiry`) remains a separate, later slice. `quotation.enquiry_id`
already exists as a nullable forward-compat column (P1b); this slice leaves it as-is and does
not require enquiry to exist.

| Wedge stage | Slice | Status |
|-------------|-------|--------|
| enquiry (lead capture) | later slice | **deferred** |
| quotation (build → send → revise) | P1b | **merged** |
| **order (accept a sent quote)** | **this spec** | **this slice** |

## 2. Scope

**In scope:**
- `Order` aggregate — create-only, single status `CONFIRMED`.
- The `accept` transition on a `SENT` quotation: creates exactly one order, moves the
  quotation to `ACCEPTED`.
- **Natural (state-based) idempotency** — one order per quotation, no client key.
- `QuotationAcceptedEvent` (synchronous, same-transaction) + an audit-log subscriber.
- Read endpoints for orders (`GET` list + by id).

**Explicitly out of scope** (deferred, do not build here):
- `enquiry` entity (the wedge head — its own slice).
- Order status **transitions** — `DISPATCHED` / `CLOSED` / `CANCELLED` and their endpoints.
  The `OrderStatus` enum ships with `CONFIRMED` only.
- PDF generation and `wa.me` WhatsApp share.
- Scheduled auto-expiry.
- Record-level visibility filtering (`assigned_to`) — still open from P1a/P1b; orders inherit
  the same tenant-wide read behaviour.
- Cursor pagination — orders use the same offset-based `Pageable` / `PageResponse` as P1a/P1b.

## 3. Modules & conventions

Lives under `com.easycrm.sales` alongside the quotation aggregate (the design spec §6 places
`order` in the `sales/` module). New shared plumbing: none — this slice reuses
`DocumentNumberService`, `BigDecimalStringModule`, `PageResponse`, `ValidationException`,
`NotFoundException`, `TenantScopedEntity`, and the P0 isolation stack unchanged.

Conventions carried forward, unchanged:
- **Tenant isolation is structural** — `Order` extends `TenantScopedEntity` (`@TenantId` + RLS);
  ArchUnit enforces it. Never hand-write `WHERE tenant_id`.
- **Money is never a `double`** — `NUMERIC(18,2)` in Postgres, `BigDecimal` in Java, JSON
  **string** on the wire via the global `BigDecimalStringModule`. Totals are **snapshotted**
  from the accepted version, never recomputed (the version is frozen and authoritative).
- **Cross-tenant reads return 404**, not 403/200 (P0 pattern).
- **`ddl-auto: validate`** — migration column types must match entity mappings exactly.

## 4. Entities

### 4.1 `Order` — physical table `sales_order`

`order` is a reserved SQL keyword, so the **physical table is `sales_order`** (`@Table(name =
"sales_order")`) while the Java class stays `Order`. This avoids identifier-quoting across
Flyway migrations, RLS policies, and JPA/HQL. See challenge log (this slice).

Tenant-scoped (`extends TenantScopedEntity`). Fields:

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID (v7) | from `BaseEntity` |
| `tenant_id` | UUID | `@TenantId`, RLS |
| `order_no` | `VARCHAR(32)` | gapless per tenant/FY, e.g. `ORD/25-26/0001` |
| `quotation_id` | UUID, not null | the accepted quotation |
| `quotation_version_id` | UUID, not null | the accepted **frozen** version (canonical for line items) |
| `customer_id` | UUID, not null | copied from the quotation |
| `po_reference` | `VARCHAR`, nullable | customer PO number, captured at accept |
| `po_date` | `DATE`, nullable | customer PO date |
| `sub_total` | `NUMERIC(18,2)`, not null | **snapshot** from the accepted version |
| `total_tax` | `NUMERIC(18,2)`, not null | snapshot |
| `grand_total` | `NUMERIC(18,2)`, not null | snapshot |
| `status` | `VARCHAR(16)`, not null | `OrderStatus`, `CONFIRMED` only |
| `version` | int | `@Version` optimistic lock (from `BaseEntity`) |
| `created_at` / `updated_at` / actor | | auditing (from `BaseEntity`) |

**Constraints:**
- `UNIQUE (tenant_id, order_no)` — numbering uniqueness.
- `UNIQUE (tenant_id, quotation_id)` — **the natural idempotency guard: at most one order per
  quotation.**

The order **does not copy line items** — the accepted `quotation_version` is immutable and
remains the canonical source of item detail. `quotation_version_id` links to it.

### 4.2 `OrderStatus`

```java
public enum OrderStatus { CONFIRMED }
```

Single value now; `DISPATCHED` / `CLOSED` / `CANCELLED` arrive with the deferred
order-management slice. Modelled as an enum (not a boolean) so the transitions add without a
schema change to the status column.

### 4.3 Migrations

- `V18__sales_order.sql` — the `sales_order` table + both unique constraints. `order_no`
  `VARCHAR(32)`, amounts `NUMERIC(18,2)`, `status VARCHAR(16)`, `po_reference VARCHAR`,
  `po_date DATE`. Column types must match §4.1 exactly (`ddl-auto: validate`).
- `V19__rls_sales_order.sql` — enable RLS + the tenant policy, mirroring
  `V15`/quotation: `USING (tenant_id = NULLIF(current_setting('app.current_tenant', true),
  '')::uuid)` (the `USING` clause also acts as `WITH CHECK` for inserts — challenge #6). Grant
  to `easycrm_app`.

## 5. The accept transition

### 5.1 Endpoint

```
POST /api/v1/quotations/{id}/accept
Body (optional): { "poReference": "PO-4471", "poDate": "2026-07-27" }
→ 200 OK  OrderResponse           (first accept AND idempotent re-accept)
→ 404     quotation not visible / not found
→ 422     quotation not in an acceptable state
```

`accept` is an **idempotent action**, so it returns **`200`** (not `201`) on both the first
call and any retry — a re-accept cannot meaningfully return `201 Created`.

### 5.2 `QuotationService.accept(id, req)` — one `@Transactional` method

1. Load the quotation (`findQuotation` → 404 if not visible under RLS).
2. **If status is already `ACCEPTED`** → load and return its existing order (look up by
   `quotation_id`). Idempotent short-circuit — no second order, no re-publish.
3. Else require status `SENT`. `DRAFT` / `REJECTED` / `EXPIRED` →
   `ValidationException("status", "only a sent quotation can be accepted")` → 422.
4. Load the current (frozen `SENT`) `QuotationVersion`.
5. Create the `Order`: mint `order_no` via `documentNumbers.nextOrderNo(LocalDate.now())`;
   snapshot `sub_total` / `total_tax` / `grand_total` and `customer_id` from the version/
   quotation; set `quotation_version_id`; set `po_reference` / `po_date` from the request;
   `status = CONFIRMED`.
6. `quotation.markAccepted()` → `QuotationStatus.ACCEPTED`.
7. Publish `QuotationAcceptedEvent(quotationId, orderId, quotationVersionId, grandTotal,
   actorUserId)`.
8. Return `OrderResponse.of(order)`.

### 5.3 Idempotency & concurrency (challenge #3, natural-key variant)

"Exactly one order per quotation" is a true domain invariant, so the **quotation id is the
idempotency key** — no client-supplied key or extra table is needed. Two guards make it hold:

| Scenario | Mechanism | Outcome |
|----------|-----------|---------|
| Retry after commit (no ack reached client) | step 2: quotation already `ACCEPTED` → return existing order | one order |
| Simultaneous double-tap (neither committed) | `UNIQUE (tenant_id, quotation_id)` rejects the 2nd insert; `@Version` on the quotation guards the status update | one order; loser's retry hits step 2 |
| Pre-commit crash | transaction atomicity — nothing half-done | user retries cleanly, creates one order |

This is challenge #3's own lesson — *choose the weakest tool that's actually sufficient*. The
existing global `DataIntegrityViolationException → 409` handler backstops the unique constraint
if a raced insert surfaces to the caller.

## 6. Event + audit subscriber

```java
public record QuotationAcceptedEvent(
    UUID quotationId, UUID orderId, UUID quotationVersionId,
    BigDecimal grandTotal, UUID actorUserId) {}
```

Published via `ApplicationEventPublisher` — **synchronous, same transaction** (Spring's
default). Order creation is **inline in the accept command** (its result is the HTTP
response); the event fires *after* the order exists, carrying its id, for decoupled
side-effects. This is a deliberate, minor deviation from the parent spec §396 wording ("the
order handler subscribes") — creating the order inline avoids using the event as a return
channel, while preserving atomicity and the open/closed seam. Logged as a challenge.

**Subscriber (this slice):** one `@EventListener` calls
`AuditService.record("QUOTATION_ACCEPTED", actorUserId, { quotationId, orderId, orderNo,
grandTotal })`. `AuditService.record` is `@Transactional` (REQUIRED) so it joins the accept
transaction — audit and order commit or roll back together. `actorUserId` comes from
`TenantContext` (the JWT principal). The `sales → iam` dependency is permitted (the only
ArchUnit rule is tenant-scoping).

**Future subscribers** (activity timeline, WhatsApp confirmation) attach to the same event
without editing `accept` — the seam challenge #3 exists to preserve.

## 7. Read API surface

| Method | Path | Returns |
|--------|------|---------|
| `POST` | `/api/v1/quotations/{id}/accept` | `200` `OrderResponse` |
| `GET` | `/api/v1/orders` | `200` `PageResponse<OrderResponse>`, optional `?customerId=` / `?status=` |
| `GET` | `/api/v1/orders/{id}` | `200` `OrderResponse`; `404` if not visible |

**`OrderResponse`:** `id`, `orderNo`, `quotationId`, `quotationVersionId`, `customerId`,
`status`, `poReference`, `poDate`, `subTotal`, `totalTax`, `grandTotal`, `createdAt`. Money
fields serialize as JSON **strings** (global `BigDecimalStringModule`).

**`AcceptRequest`:** `poReference` (nullable), `poDate` (nullable) — both optional; an empty
body is valid.

Orders belong to the `OrderController` (new). `GET` list uses offset `Pageable` +
`PageResponse.of(...)`, matching P1a/P1b.

## 8. Error handling

- **404** — quotation or order missing / cross-tenant (RLS + P0 not-found mapping).
- **422** — accepting a quotation whose status is not `SENT` (and not the idempotent
  `ACCEPTED` case) → `ValidationException("status", …)`.
- **409** — the global `DataIntegrityViolationException` handler backstops the
  `UNIQUE (tenant_id, quotation_id)` constraint under a raced insert.

## 9. Testing (TDD, real Postgres + RLS, `easycrm_app` role)

Integration tests against Testcontainers Postgres, connecting as the non-owner `easycrm_app`
role so RLS is real. Tenants provisioned via **`TestTokens.provisionOwner(stateCode)`** — the
quotation setup path reads `Tenant.state_code`, so a phantom tenant is insufficient (P1b
lesson).

Cases:
1. Accept a `SENT` quotation → `Order` created, `CONFIRMED`; `order_no` shaped `ORD/FY/NNNN`;
   `sub_total`/`total_tax`/`grand_total` equal the accepted version's; quotation → `ACCEPTED`.
2. **Idempotent re-accept** of an already-`ACCEPTED` quotation → same order id, still exactly
   one `sales_order` row.
3. Accept a `DRAFT` / `REJECTED` / `EXPIRED` quotation → `422`, no order created, status
   unchanged.
4. `order_no` is gapless within a tenant/FY across multiple accepts.
5. `QUOTATION_ACCEPTED` audit row written with the expected action + detail, in the same
   transaction.
6. `GET /orders/{id}` and `GET /orders` return the order; **cross-tenant `GET` → 404** and RLS
   blocks the row.
7. `poReference` / `poDate` from the request are persisted; empty body is accepted.

## 10. Working-agreement checkpoints

- **Challenges to log** (`engineering-challenges.md`, in the same change):
  1. `order` is a reserved SQL word → physical table `sales_order`, class `Order`.
  2. Natural (state-based) idempotency — one order per quotation via `UNIQUE(tenant_id,
     quotation_id)` + `@Version`, instead of a client idempotency key (challenge #3's
     weakest-sufficient-tool lesson, applied).
  3. `QuotationAcceptedEvent` as a side-effect seam (order created inline), deviating from the
     parent spec's "listener creates the order" wording, and why.
- **Annotations reference** — add any new annotation that appears (e.g. `@EventListener` /
  `@TransactionalEventListener` if used) with origin/purpose/composition.
- **TDD** — failing test → confirm-fail → minimal code → pass → commit. One task per commit.
- **Commits** — author as `divyam`, plain `git commit`, no Claude/AI mention or `Co-Authored-By`.

## 11. Estimated shape

~7 entities/classes: `Order`, `OrderStatus`, `OrderRepository`, `QuotationAcceptedEvent`,
`OrderAcceptedAuditListener`, `OrderController`, `OrderResponse` + `AcceptRequest` DTOs — plus
`accept` on `QuotationService`, `nextOrderNo` on `DocumentNumberService`, the `ACCEPTED` enum
value, and two migrations. Small, self-contained, sits directly on P1b.
