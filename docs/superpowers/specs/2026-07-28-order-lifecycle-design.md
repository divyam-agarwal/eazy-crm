# Order lifecycle — design spec

**Date:** 2026-07-28
**Status:** implemented, merged to `main` as `8247579`
**Slice:** order status transitions (`DISPATCHED` / `CLOSED` / `CANCELLED`) + the deferred
order-list filter fix (challenge #24)
**Builds on:** the order/accept slice (`specs/2026-07-27-order-accept-design.md`, merged `ea11d3f`)

---

## 1. Why this slice

`Order` today is a one-state aggregate: `OrderStatus` has the single member `CONFIRMED`, set by
the constructor and never changed. The parent design spec
(`specs/2026-07-22-easycrm-design.md`, "order" bullet) always called for
`CONFIRMED/DISPATCHED/CLOSED/CANCELLED`; this slice delivers the remaining three and the guarded
transitions between them, completing the wedge's tail.

It is also the agreed home for a deferred bug: `OrderService.list` composes its two filters with
`if/else if`, so supplying `status` **and** `customerId` silently drops the customer filter. The
enquiry slice avoided this with a JPA `Specification` and logged the general lesson as
challenge #24; orders never got the fix.

Scope is deliberately domain-local. No new infrastructure, no external I/O.

## 2. State machine

```
CONFIRMED ──dispatch──> DISPATCHED ──close──> CLOSED (terminal)
    │                        │
    └────────cancel──────────┘
                 │
                 v
           CANCELLED (terminal)
```

- **Linear progression, no skips.** `CONFIRMED → CLOSED` directly is *not* legal. A counter sale
  still passes through `DISPATCHED`.
- **Cancel from either active state.** Cancelling after dispatch is legal (goods returned, deal
  fell through).
- **`CLOSED` and `CANCELLED` are terminal.** Nothing leaves them; there is no un-cancel and no
  reopen.

`OrderStatus` gains the two predicates that `EnquiryStage` already uses:

```java
public boolean isTerminal() { return this == CLOSED || this == CANCELLED; }
public boolean isActive()   { return !isTerminal(); }
```

**Dispatch is a status flag, not a sub-aggregate.** The parent spec puts dispatch, invoicing and
e-way bills explicitly out of scope ("that's Tally's job"). This slice models *that the order was
dispatched*, not transporter, LR number, or partial dispatch. No `dispatchedAt` column either —
`BaseEntity.updatedAt` covers "when did this last change", and the audit trail (§5) records the
moment precisely.

## 3. Guard placement: in the entity

The codebase carries two patterns. `Quotation` guards in the service (`QuotationService.requireSent`)
with dumb entity setters. `Enquiry` guards in the entity (`requireActive`, `advanceTo`) with a thin
service. **`Order` follows `Enquiry`.**

Rationale:

- The invariant becomes structural rather than procedural — the same instinct as the tenant-isolation
  rule in `CLAUDE.md`. No future caller can flip a status past a guard, because there is no
  unguarded setter to call.
- The whole state machine is testable as a plain JUnit test with no Spring context and no
  Testcontainers, which is where most of this slice's coverage lives.
- `Enquiry` is the more recent expression of the house style; this is where `Quotation` would land
  if rewritten today. Rewriting `Quotation` is explicitly **not** in scope — the two patterns
  coexist until something else justifies touching it.

`Order` gains three transition methods. Each names its own precondition explicitly rather than
comparing enum positions, so — unlike `Enquiry.advanceTo`, whose ordinal coupling is an open
deferred Minor — reordering the enum constants cannot change behaviour here.

| Method | Precondition | Effect | On violation |
|---|---|---|---|
| `dispatch()` | status is `CONFIRMED` | → `DISPATCHED` | `ValidationException("status", "only a confirmed order can be dispatched")` |
| `close()` | status is `DISPATCHED` | → `CLOSED` | `ValidationException("status", "only a dispatched order can be closed")` |
| `cancel(String reason)` | `status.isActive()` | → `CANCELLED`, stores reason | terminal → `ValidationException("status", …)` with the current status name lowercased into the message, e.g. `"a closed order cannot be cancelled"` (same construction as `Enquiry.requireActive`); blank/null reason → `ValidationException("cancelReason", "a reason is required to cancel an order")` |

`ValidationException` maps to **422** via the existing `ApiExceptionHandler`.

The constructor still sets `CONFIRMED`. `QuotationService.accept` is untouched except for §6.

## 4. Persistence

One new field on `Order`:

```java
@Column(name = "cancel_reason", length = 500)
private String cancelReason;
```

mirroring `Enquiry.lostReason`. Nullable — it is null on every order that was never cancelled.

**Migration `V23__order_cancel_reason.sql`:**

```sql
ALTER TABLE sales_order ADD COLUMN cancel_reason VARCHAR(500);
```

`ddl-auto: validate` is on, so the column type must match the mapping exactly: `VARCHAR(500)`,
nullable.

Three things this slice deliberately does **not** do:

- **No change to the `status` column.** It is already `VARCHAR(16)` and values persist as
  `@Enumerated(EnumType.STRING)`; the longest new name (`DISPATCHED`) is 10 characters. Widening
  the enum is a no-op at the database level.
- **No RLS change.** `V19__rls_sales_order.sql` enables RLS with a `tenant_id` policy over the whole
  table; a new column inherits it.
- **No `CHECK` constraint on `status`.** House convention is enum-in-Java, `VARCHAR` in Postgres
  (see `quotation`, `enquiry`).

## 5. Events and audit

Every transition leaves an audit row. One generic event carries all three, so the number of event
types does not grow as statuses do:

```java
public record OrderStatusChangedEvent(UUID orderId, String orderNo, OrderStatus from,
                                      OrderStatus to, String cancelReason, UUID actorUserId) {}
```

`OrderService` publishes it after each successful transition, reading the actor from
`TenantContext` exactly as `QuotationService.accept` does.

`OrderStatusChangedAuditListener` is a synchronous `@EventListener` running in the publisher's
transaction — the audit row commits or rolls back together with the status change (challenge #3
atomicity), matching `OrderAcceptedAuditListener`. It writes action `"ORDER_" + to.name()`:

| Transition | Audit action | Detail keys |
|---|---|---|
| → `DISPATCHED` | `ORDER_DISPATCHED` | `orderId`, `orderNo`, `from` |
| → `CLOSED` | `ORDER_CLOSED` | `orderId`, `orderNo`, `from` |
| → `CANCELLED` | `ORDER_CANCELLED` | `orderId`, `orderNo`, `from`, `cancelReason` |

All detail values are strings (`orderId` as `UUID.toString()`, `from` as the enum's `name()`),
matching `OrderAcceptedAuditListener`'s existing detail map. `cancelReason` is present only on the
cancel row.

**Known accepted cost:** this adds a second same-transaction listener to the seam that challenge
\#22 flagged for eventual after-commit + outbox conversion (planned for the PDF/WhatsApp slice, the
first external-I/O work). That slice will convert two listeners instead of one. The generic event
shape bounds that cost — adding statuses later adds no new listeners.

## 6. HTTP surface

### New endpoints

```
POST /api/v1/orders/{id}/dispatch  -> 200 OrderResponse
POST /api/v1/orders/{id}/close     -> 200 OrderResponse
POST /api/v1/orders/{id}/cancel    -> 200 OrderResponse
     body: { "cancelReason": "customer withdrew PO" }
```

Verb-per-endpoint, matching `QuotationController`'s `send`/`reject`/`expire`. A target-taking
endpoint (`Enquiry`'s `/advance` shape) would carry no information here: the graph is strictly
linear, so each active state has exactly one legal advance.

New DTO, mirroring `LoseRequest`:

```java
public record CancelRequest(@NotBlank String cancelReason) {}
```

The `@NotBlank` and the entity's own blank check are **both** intended. Bean validation rejects a
blank reason at the edge with 400 (`MethodArgumentNotValidException`); the entity guard still
protects any non-HTTP caller with a 422. Defence in depth, exactly as `LoseRequest` /
`Enquiry.lose`.

`OrderResponse` gains a `cancelReason` field — null except on cancelled orders.

### Status codes

| Case | Code |
|---|---|
| Successful transition | 200 with the updated `OrderResponse` |
| Illegal transition for the current status | 422 `VALIDATION_FAILED` |
| Blank or missing `cancelReason` on `/cancel` | 400 `VALIDATION_FAILED` |
| Unknown id, or an order in another tenant | 404 `NOT_FOUND` |
| Concurrent conflicting transition (lost update) | 409 — via the existing global `OptimisticLockingFailureException` handler (challenge #26); no new code |

Cross-tenant 404 needs no new code: RLS hides the row, and the existing `find`-then-
`NotFoundException` path in `OrderService` already produces 404.

### Changed behaviour on existing endpoints

**`GET /api/v1/orders` — filter composition fix.** New `OrderSpecifications.filter(status,
customerId)` AND-composing whichever filters are non-null, a direct analogue of
`EnquirySpecifications`. `OrderRepository` extends `JpaSpecificationExecutor<Order>`;
`findByStatus` and `findByCustomerId` are deleted (nothing else calls them). `findByQuotationId`
stays — the accept path uses it. The wire contract is unchanged; the endpoint simply now honours
both filters at once instead of dropping `customerId`.

**`POST /api/v1/quotations/{id}/accept` — cancelled-order case.** The idempotent branch gains one
check: if the order that already exists for this quotation is `CANCELLED`, throw
`ValidationException("status", "the order for this quotation was cancelled; raise a new
quotation")` → 422, rather than returning a dead order with a 200. Non-cancelled orders still
return idempotently as today.

This keeps cancellation order-local: the quotation stays `ACCEPTED`, the cancelled row stays put,
and `UNIQUE(tenant_id, quotation_id)` on `sales_order` is untouched. Reopening a cancelled sale
means raising a new quotation — explicitly chosen over reverting the quotation to `SENT`, which
would collide with that constraint.

## 7. Testing

TDD per task: failing test → confirm it fails → minimal implementation → confirm it passes →
commit. Coverage splits by what needs Spring and what does not.

- **`OrderTest`** (plain JUnit, no Spring, no Testcontainers) — the state machine in isolation:
  every legal transition; every illegal one (dispatch an already-dispatched / closed / cancelled
  order, close a confirmed one, cancel a closed or cancelled one); blank and null cancel reason;
  and that `cancel` stores the reason. The bulk of the coverage, and it runs in milliseconds
  because the guards live in the entity.
- **`OrderTransitionTest`** (`@IntegrationTest` + MockMvc) — the three endpoints end to end: 200
  response shape including `cancelReason`, 422 on an illegal transition, 400 on a blank reason,
  404 cross-tenant.
- **`OrderStatusAuditTest`** — one audit row per transition with the correct action name and detail
  keys, mirroring `QuotationAcceptAuditTest`.
- **`OrderListTest`** — the regression that matters: `status` and `customerId` supplied together
  must AND rather than drop one. Plus each filter alone, and neither.
- **`QuotationAcceptTest`** — one added case: accepting a quotation whose order was cancelled → 422.

**Testing note:** any test that reaches through quotation creation needs
`TestTokens.provisionOwner(stateCode)`, not `TestTokens.owner(randomUUID())` — the GST split reads
a real `Tenant.state_code` row.

Expected total: roughly **+18–22 tests** on the 166-test baseline.

## 8. Explicitly out of scope

- **Dispatch details** — transporter, LR number, partial or multi-leg dispatch. Out per the parent
  spec.
- **Un-cancel / reopen** — both terminal states are final.
- **Reverting the quotation on cancellation** — see §6.
- **Rewriting `Quotation` to entity-guarded transitions** — the two patterns coexist for now.
- **Wider order-list filters** — multi-valued `status`, `orderNo` lookup, date ranges. Deferred
  until a frontend drives the requirement.
- **Cursor pagination** — the order list stays offset-based, like every other list endpoint.
- **After-commit / outbox event delivery** — deferred to the PDF/WhatsApp slice (challenge #22).
