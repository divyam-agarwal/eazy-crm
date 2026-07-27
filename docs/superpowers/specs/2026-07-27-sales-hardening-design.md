# EasyCRM P1 — Sales Hardening Design (optimistic-lock 409 + quote-uniqueness backstop)

**Status:** Design approved, pre-implementation
**Date:** 2026-07-27
**Parent spec:** `2026-07-22-easycrm-design.md` (§4 isolation/error model)
**Depends on:** P0 isolation + P0-auth + P1a + P1b + order/accept + enquiry + enquiry→quotation
conversion (all merged on `main`)

---

## 1. Context & purpose

Two Minor findings from the enquiry→quotation conversion whole-branch review were consciously
deferred to a dedicated hardening slice (both recorded in `HANDOFF.md` §4). Both are **pre-existing,
codebase-wide gaps**, not regressions from the conversion work. This slice closes them:

1. **Optimistic-lock race surfaces as HTTP 500.** A lost-update race — concurrent quotation `accept`
   (challenge #21) or concurrent convert-at-create (challenge #25) — is caught by the entity's
   `@Version` so **data integrity always holds** (exactly one writer commits). But the *losing*
   request surfaces as a raw **500**: JPA throws `ObjectOptimisticLockingFailureException`, which
   extends `ConcurrencyFailureException`, **not** `DataIntegrityViolationException` — so it misses the
   challenge #15 `DataIntegrityViolation`→409 backstop and falls through to the default 500.

2. **One-quotation-per-enquiry has no DB backstop.** The invariant "an enquiry maps to at most one
   quotation" rests only on the entity terminal guard + `@Version` (procedural). CLAUDE.md prefers
   structural invariants (cf. the enquiry dedupe partial index, challenge #23). There is no DB
   constraint enforcing it.

## 2. Scope

**In scope:**
- A global `@ExceptionHandler(OptimisticLockingFailureException.class)` → **409** in
  `ApiExceptionHandler` (applies app-wide: accept, convert, and any future `@Version` write).
- A `UNIQUE (tenant_id, enquiry_id)` constraint on `quotation` (migration + matching entity
  `@Table` declaration) as a structural backstop for one-quote-per-enquiry.
- Deterministic tests for both (no threaded/timing-sensitive race tests).

**Explicitly out of scope:**
- Client-driven retry logic / `Retry-After` headers — the 409 just tells the client the write lost a
  race; retry policy is the client's.
- Changing the convert-at-create trigger, the terminal-guard 422, or any existing flow behaviour.
- A threaded HTTP concurrency test (deliberately omitted — see §5).
- Anything else on the deferred backlog (activity/follow_up, record-level visibility, cursor
  pagination, order status transitions, etc.).

## 3. Modules & conventions

- Item 1 touches `com.easycrm.platform.error.ApiExceptionHandler` (+ its unit test) and adds one
  repo-level test under `com.easycrm.sales`.
- Item 2 touches `com.easycrm.sales.Quotation` (`@Table` constraint) + a new Flyway migration
  `V22__quotation_enquiry_unique.sql` + one repo-level test.
- Conventions unchanged: cross-tenant/missing → 404; `ConflictException`/`DataIntegrityViolation`/
  optimistic-lock → 409; `ValidationException` → 422; money-as-JSON-string; tenant isolation
  structural; `ddl-auto: validate` (migration column/type must match the entity).

## 4. Item 1 — Optimistic-lock → 409

### 4.1 The handler

Add to `ApiExceptionHandler`, next to the existing `dataIntegrity` handler:

```java
@ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
public ResponseEntity<Map<String, Object>> optimisticLock(OptimisticLockingFailureException ex) {
    // A concurrent @Version write lost the race. Data integrity is intact (exactly one writer
    // wins); the loser gets 409 instead of a raw 500. Sibling of the DataIntegrityViolation
    // backstop above — ObjectOptimisticLockingFailureException does NOT extend
    // DataIntegrityViolationException, so it needs its own handler.
    return body(HttpStatus.CONFLICT, "CONFLICT",
        "the request could not be completed due to a concurrent update; please retry", null);
}
```

- Catch Spring's **base** `org.springframework.dao.OptimisticLockingFailureException`, which covers
  the concrete `org.springframework.orm.ObjectOptimisticLockingFailureException` Spring Data
  translates JPA's `OptimisticLockException` into.
- No precedence conflict with `dataIntegrity` — the two are unrelated `DataAccessException`
  subtrees, so Spring's most-specific-match dispatch never has to choose between them for the same
  throwable.
- Body/shape identical to the other 409s (`error.code = "CONFLICT"`), generic message (no internal
  detail leaked).

### 4.2 Tests (deterministic, no threads)

- **Handler unit test** (`ApiExceptionHandlerTest`, mirrors the existing `validation` test — pure,
  no Spring context): call
  `handler.optimisticLock(new ObjectOptimisticLockingFailureException(Enquiry.class, someId))` and
  assert `409` + `error.code == "CONFLICT"`.
- **Repo stale-write test** (`com.easycrm.sales`, `@SpringBootTest` / `IntegrationTest`, single
  thread): create an enquiry (version 0); in a second transaction load + mutate + `saveAndFlush` it
  (→ DB version 1); then take the still-version-0 detached copy, mutate it, and `saveAndFlush` →
  assert `OptimisticLockingFailureException` is thrown. This proves `@Version` is actually live on
  the entity (so the handler's input really occurs in the real code paths), without any threaded
  timing.

## 5. Item 2 — Structural one-quote-per-enquiry backstop

### 5.1 Migration `V22__quotation_enquiry_unique.sql`

```sql
ALTER TABLE quotation
    ADD CONSTRAINT uq_quotation_tenant_enquiry UNIQUE (tenant_id, enquiry_id);
```

Postgres `UNIQUE` treats `NULL`s as distinct, so any number of **enquiry-less** quotations
(`enquiry_id IS NULL`) coexist — only quotations that name the *same* enquiry within a tenant
collide. No existing test creates two quotations for one enquiry (the second create is stopped at
422 by the terminal guard *before* any insert), so the constraint is inert on the current suite.

### 5.2 Entity

Add the matching constraint to `Quotation`'s `@Table` (house pattern — `quote_no`'s unique
constraint is already declared there):

```java
@Table(name = "quotation",
       uniqueConstraints = {
           @UniqueConstraint(name = "uq_quotation_tenant_no",
                             columnNames = {"tenant_id", "quote_no"}),
           @UniqueConstraint(name = "uq_quotation_tenant_enquiry",
                             columnNames = {"tenant_id", "enquiry_id"})
       })
```

`ddl-auto: validate` does not check unique constraints, so this declaration is documentary +
keeps entity and schema in sync; the migration is what enforces it.

### 5.3 How it surfaces

A guard bypass (only reachable if `markConverted`'s terminal guard were ever circumvented) that
attempts a second quotation for the same enquiry now hits the DB constraint → the existing
challenge #15 `DataIntegrityViolationException`→409 handler maps it to **409**, not a 500. No new
error mapping needed. Normal flow is unchanged: the terminal guard still returns 422 first, before
any insert is attempted.

### 5.4 Test

Repo-level (`QuotationRepositoryTest` or a focused new test, `IntegrationTest`, tenant context set):
- Saving two `Quotation`s with the same `(tenant_id, enquiry_id)` (same non-null `enquiryId`) →
  second `saveAndFlush` throws `DataIntegrityViolationException`.
- Two `Quotation`s with `enquiryId == null` both persist (NULLs distinct — enquiry-less quotes
  unaffected).

## 6. Testing summary

All deterministic, real Postgres + RLS via Testcontainers; `TestTokens.provisionOwner(...)` where a
tenant row is needed. Net new: **3 tests** (handler unit, stale-write repo, quote-uniqueness repo)
→ suite 162 → **165**.

## 7. Documentation obligations (same change, per CLAUDE.md)

- **Update challenge #25**: change its parenthetical from "the race-loser currently surfaces as HTTP
  500 … deferred" to "now closed by a global `OptimisticLockingFailureException`→409 handler (this
  slice)."
- **Evaluate a short new challenge** (#26 candidate): "optimistic-lock is a *sibling* of, not covered
  by, the data-integrity 409 backstop" — the non-obvious insight that two distinct
  `DataAccessException` subtrees both need mapping to 409, and why one handler doesn't cover the
  other. Log it only if it clears the CLAUDE.md bar (it plausibly does — a naive reader assumes the
  existing DataIntegrityViolation handler already covers "all DB conflicts").
- **`annotations-reference.md`**: no new annotation (`@ExceptionHandler`, `@UniqueConstraint`,
  `@Table` all already documented) — no change expected.
- **`HANDOFF.md`**: on merge, move both items out of the §4 deferred list into done; bump the test
  count.

## 8. Out-of-scope recap (do not build)

Client retry/`Retry-After` · threaded HTTP race test · changes to convert/accept/terminal-guard
behaviour · activity/follow_up · record-level visibility · cursor pagination · order status
transitions.
