# Scheduled quotation auto-expiry — design

**Date:** 2026-08-31
**Status:** Approved, not yet implemented
**Backlog item:** §8 #2 in `docs/superpowers/HANDOFF.md`
**Baseline:** `main` at `f9e7afc` (432 tests, 0 failures, 0 errors)

---

## 1. What this builds, and why it is not just a status flip

A quotation carries a `validUntil` date. Today nothing acts on it: the only way a quote
becomes `EXPIRED` is a user calling `POST /api/v1/quotations/{id}/expire` by hand. A quote
whose validity lapsed last month still reads `SENT` in every list and on every PDF, so the
pipeline view overstates live business.

The behaviour change is small — flip `SENT` quotations past `validUntil` to `EXPIRED`. The
reason this gets a design spec is that it introduces **the codebase's first non-request
execution path**. Every request so far has arrived with a JWT, and everything downstream —
`TenantContext`, the RLS GUC, Hibernate's `@TenantId`, `VisibilityPolicy` — reads from that
JWT-derived principal. A scheduled job has none. Whatever seam this slice establishes for
"run work for every tenant with no user", every later job copies: dashboard rollups,
follow-up reminders, entitlement metering, billing.

So the deliverable is two things: the expiry behaviour, and a reusable tenant-iterating
job seam that makes the ordering rule structural instead of remembered.

## 2. Decisions taken

Recorded here so they read as decisions rather than accidents.

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | A **reusable `TenantJobRunner` in `platform/`**, not a one-off loop in `sales/` | The `runAs`-before-transaction ordering (challenge #9) is easy to get subtly wrong and impossible to notice when wrong. Owning it in one class means the next job inherits correctness instead of re-deriving it. |
| D2 | Auto-expiry writes **both an audit row and a `SYSTEM` activity** | Mirrors what `QuotationAcceptedEvent` already does. A status changing overnight with no record of when or why is the exact gap the activity timeline exists to close. |
| D3 | **No distributed lock.** Single-instance assumption, documented | The `@Version` optimistic lock already prevents duplicate audit/activity rows: only one writer wins the flip, and the event is published only after a successful flip. Same YAGNI call the rate-limiting slice made for its in-process store. |
| D4 | Sweep **`TRIAL` + `ACTIVE`, skip `SUSPENDED`** | A suspended tenant is not using the product. Because expiry is derived from `validUntil` and not from when the job ran, a reactivated tenant gets a delayed flip, not wrong data. |
| D5 | **One transaction per tenant**, not per quotation | Chosen by the user over the per-quotation alternative. See §6 for the trade-off this accepts and the retry that bounds it. |

## 3. Components

Five new classes, three small extensions, **zero migrations**.

### 3.1 `platform/job/TenantJobRunner` (new)

The seam. Injects `TenantRepository` and a `TransactionTemplate` built on the existing
`TenantAwareTransactionManager`.

```java
for (Tenant t : tenants.findByStatusIn(List.of(TenantStatus.TRIAL, TenantStatus.ACTIVE))) {
    try {
        TenantContext.runAs(systemPrincipal(t),
            () -> tx.execute(s -> { body.accept(t.getId()); return null; }));
    } catch (RuntimeException e) {
        log.warn("job {} failed for tenant {}", jobName, t.getId(), e);   // next tenant
    }
}
```

The snippet elides the optimistic-lock retry described in §6, which wraps the
`tx.execute` call; it is shown here as the bare shape.

Two choices carry weight:

- **Its own `TransactionTemplate`, built with `PROPAGATION_REQUIRES_NEW`.** Two distinct traps
  are closed here, and only the first is obvious. A `@Transactional` method invoked from this
  class's own loop is a self-invocation: Spring's proxy is bypassed and the "per-tenant
  transaction" silently joins whatever transaction the caller already had, collapsing D5's
  per-tenant boundary into a single sweep-wide transaction. But injecting Boot's autoconfigured
  `TransactionTemplate` would leave the second trap open — that bean is `PROPAGATION_REQUIRED`,
  so a caller already holding a transaction (a future job wrapped in `@Transactional`, an admin
  "run now" endpoint, a `@Transactional` test) makes `tx.execute` **join** it: `doBegin` never
  runs, the GUC stays unset, the session has already resolved `NO_TENANT`, and every scoped read
  returns zero rows — precisely the failure this class exists to prevent. The runner therefore
  constructs its own template from the `PlatformTransactionManager` and sets
  `PROPAGATION_REQUIRES_NEW`. It must not mutate the shared bean, which other code autowires.
- **`runAs` wraps `tx.execute`, never the reverse.** `TenantAwareTransactionManager.doBegin`
  reads `TenantContext` to set the `app.current_tenant` GUC, and Hibernate resolves a
  session's tenant once at session-open and never re-reads it (challenge #9). Context set
  after the transaction opens binds to nothing, and the failure is silent: queries return
  zero rows rather than erroring.

The **system principal** is `new TenantPrincipal(tenantId, null, "SYSTEM")` — the same shape
`AuthService` already uses for pre-authentication flows. `VisibilityPolicy.unrestricted()`
returns true for it (only `SALES_EXEC` is restricted), so a job sees the whole tenant, which
is what a job must do. `AuditLog.actorUserId` is nullable, so the null user id records
honestly that no human did this.

Reading the tenant list happens **outside** any tenant context. That is safe and deliberate:
`Tenant` is in `TenantScopingArchTest.GLOBAL_TABLES` — no `@TenantId`, no RLS policy.

### 3.2 `sales/QuotationExpirySweep` (new)

The body, one tenant's worth of work. Signature takes `asOf` as a parameter and never reads
a clock. Fetches candidates through `VisibleFinder`, flips each, publishes an event per flip.

### 3.3 `sales/QuotationExpiryJob` (new)

`@Scheduled(cron = "${easycrm.jobs.quotation-expiry.cron}", zone = "Asia/Kolkata")`. Resolves
`asOf` from the `Clock` bean and calls the runner. Deliberately thin — every test drives the
sweep or the runner directly, so no test waits on a cron.

### 3.4 `sales/QuotationExpiredEvent` + two listeners (new)

`record QuotationExpiredEvent(UUID quotationId, String quoteNo, UUID quotationVersionId, LocalDate validUntil)`,
with `QuotationExpiredAuditListener` and `QuotationExpiredActivityListener` copied
shape-for-shape from the accept path's pair. The record deliberately carries **no**
`actorUserId`, unlike `QuotationAcceptedEvent`: there is no actor, and an always-null
field would invite a caller to start populating it. The listeners write
`actorUserId = null` themselves. Both are synchronous `@EventListener`s, matching
the existing listeners, so their writes join the tenant's transaction and roll back with it.

### 3.5 Extensions

- **`QuotationSpecifications.expirableAsOf(LocalDate asOf)`** — `status = SENT` AND a
  correlated `EXISTS` subquery on `QuotationVersion` where `v.id = root.currentVersionId`
  and `v.validUntil < asOf`. A subquery rather than a join because `Quotation` holds a raw
  `currentVersionId` UUID, not a `@ManyToOne`; this is the idiom `VisibilityPolicy.viaCustomer`
  already uses. `QuotationVersion` is itself tenant-scoped, so the subquery is scoped too.
- **`VisibleFinder.listQuotations(Specification<Quotation>)`** — returns a `List`. Required,
  not stylistic: `QuotationRepository` is a **guarded repository**, and
  `VisibilityScopingArchTest` fails the build on any read of it outside
  `com.easycrm.platform.visibility`. The visibility filter is a no-op under a `SYSTEM`
  principal, but the guard forces the read into the one place that is allowed to do it.
- **`DueWindow.todayDate(Instant)`** — today's date in IST. `DueWindow` already computes this
  internally for its day boundaries; this exposes it rather than adding a second home for
  IST arithmetic.
- **`TenantRepository.findByStatusIn(...)`** — a derived query. `Tenant` is unguarded, so no
  allowlist edit is needed.
- **`Quotation.expire()` gains its own `SENT` precondition.** It is currently unguarded, with
  the check living only in `QuotationService`; the sweep makes it a second caller. The entity
  naming its own precondition is the pattern `Order`'s transitions use and that deferred-Minor
  #13 records as the one to copy. `QuotationService.expire` keeps its `ValidationException`
  for the API's 422 contract; the entity guard is a backstop, not a replacement.

## 4. The expiry predicate

`asOf` is today's date **in IST**. A quotation expires when:

```
status = SENT  AND  currentVersion.validUntil IS NOT NULL  AND  currentVersion.validUntil < asOf
```

Strictly-before is the point: a quote marked valid until 31 Aug is valid for all of the 31st
and expires once IST rolls into 1 Sep. A null `validUntil` means open-ended and never expires.
`DRAFT`, `ACCEPTED`, `REJECTED` and already-`EXPIRED` are untouched — `DRAFT` was never sent,
and the other three are terminal.

IST matters because the tenants are Indian and `validUntil` is a `LocalDate` a user typed in
IST. Evaluating it against a UTC date would get the direction of the error backwards from what
you might guess: a UTC calendar date is never *later* than the IST one and, between 18:30 and
24:00 UTC, is a full day earlier. The job fires at 00:30 IST — 19:00 UTC the previous day — so a
UTC `asOf` would still read yesterday's date. Since the predicate is `validUntil < asOf`, too
early an `asOf` matches *fewer* rows: a quotation that should expire the moment IST rolls over
is skipped and waits for the next night's run. The mistake **delays** expiry, it does not
hasten it.

## 5. Data flow, one run

1. 00:30 IST — Spring fires `QuotationExpiryJob`; `asOf = DueWindow.todayDate(clock.instant())`.
2. Runner loads `TRIAL` + `ACTIVE` tenants, no tenant context (global table).
3. Per tenant, `runAs(systemPrincipal)` **then** `tx.execute`:
   1. `TenantAwareTransactionManager.doBegin` sets the `app.current_tenant` GUC from the
      ThreadLocal → RLS live. Hibernate opens its session and resolves `@TenantId` from the
      same ThreadLocal. **Both depend on step order.**
   2. `VisibleFinder.listQuotations(expirableAsOf(asOf))` — policy unrestricted for `SYSTEM`,
      the specification does the real filtering.
   3. For each candidate: `q.expire()`, then publish `QuotationExpiredEvent`.
   4. Listeners write the `QUOTATION_EXPIRED` audit row (`actorUserId = null`) and the
      `SYSTEM` activity against the quotation.
   5. Commit. `set_config(..., is_local => true)` clears the GUC, so no value leaks back into
      the pooled connection.
4. Next tenant.
5. Log a summary: tenants swept, quotations expired, tenants failed.

## 6. Error handling, and the trade-off D5 accepts

Three layers:

- **Per tenant.** The runner catches `RuntimeException`, logs, continues. One tenant's bad
  data cannot abort the sweep.
- **Optimistic lock, with one retry.** This is where D5 costs something. With one transaction
  per tenant, a user accepting a quotation mid-sweep raises
  `ObjectOptimisticLockingFailureException` and rolls back **that tenant's entire batch** —
  not just the contended row. Unmitigated, one race costs a tenant a night of expiries. The
  runner therefore **retries a tenant's batch once** on that specific exception, then gives up
  and logs. The common case (one user, one quote, a sub-second window) resolves on the retry.
  The residual case — repeated contention within one sweep — is rare, self-correcting on the
  next run, and cheaper than the per-quotation transaction structure that would remove it.
- **The run.** Never propagates out of `@Scheduled`.

**Multi-instance (D3).** The store of truth is the database and the flip is `@Version`-guarded,
so two instances sweeping concurrently cannot double-write: the loser takes an optimistic-lock
failure and its retry finds nothing left to expire. What is *not* handled is wasted duplicate
work. Before a second app instance is deployed, revisit this alongside the rate limiter's
in-process store, which has the sharper version of the same problem.

## 7. Configuration

- `@EnableScheduling` on a new `SchedulingConfig`.
- `easycrm.jobs.quotation-expiry.cron`, default `0 30 0 * * *`, `zone = "Asia/Kolkata"` — 00:30
  IST regardless of the server's timezone. Just after IST midnight so a lapsed quote flips
  promptly rather than a half-day late.
- Tests set the cron to `-` (Spring's disabled-cron value) so no integration test races a live
  job. **The plan must verify this override actually takes effect**, not assume it: challenge
  #42 found test-property precedence on this project works the reverse of the obvious reading.

## 8. Testing

No test overrides the `Clock` bean — that would fork the Spring context every `IntegrationTest`
subclass shares (see `ClockConfig`'s note). Determinism comes from passing `asOf` explicitly
into the sweep, the same way the activity slice passes `now` into its aggregates.

**Unit:**
- `DueWindow.today` across the IST boundary: `2026-08-31T18:29:00Z` → `2026-08-31`;
  `2026-08-31T18:30:00Z` → `2026-09-01`. UTC+5:30 flips the day mid-evening UTC.
- `Quotation.expire()` rejects each non-`SENT` status **and leaves `status` unmutated**.
  Asserting the state, not only the exception type — deferred-Minor #11 records `OrderTest`
  skipping that second half; no reason to repeat it on new code.

**Integration:**
- **Boundary:** `validUntil` of `asOf - 1`, `asOf`, and `null` → only the first expires.
- **Status filter:** `DRAFT` / `ACCEPTED` / `REJECTED` / already-`EXPIRED` with long-past
  dates → none touched.
- **Cross-tenant isolation — load-bearing.** Tenant B holds an expirable quote; sweep tenant A
  only; B's quote is untouched. This proves the GUC and `@TenantId` actually bound.
- **Prove-it-can-fail — mandatory, not optional.** Invert the ordering in a throwaway variant
  (open the transaction before setting the context) and confirm the isolation test goes red.
  If it still passes, it is testing nothing. This step is the only reason challenge #33 was
  caught, and this slice's central risk is exactly the kind it catches.
- `SUSPENDED` tenant skipped.
- **Trace:** one `QUOTATION_EXPIRED` audit row with a null actor; one `SYSTEM` activity against
  the quotation.
- **Failure isolation:** a body throwing for one tenant → later tenants still swept.
- **Retry:** a body throwing `ObjectOptimisticLockingFailureException` once then succeeding →
  asserted called twice.
- **Idempotence:** sweeping twice expires zero the second time and writes no duplicate audit
  or activity row.

Expect roughly 15–18 new tests, taking the suite from 432 to ~450.

**No migration.** No new table and no new column — `valid_until`, `audit_log` and `activity`
all exist. So there is no RLS policy to write and no `RlsCoverageIntegrationTest` allowlist to
extend, which removes a whole category of risk this codebase usually carries per slice.

## 9. Deliberately not in scope

- **No notification of any kind** when a quote expires. Same standing reason the follow-up
  reminder scheduler was declined (challenge #51): there is no channel to push into — no
  WhatsApp Business API, no delivery-tracked email, no frontend for in-app. The audit row and
  the timeline activity are the record; delivering it is a separate, additive slice.
- **No "expiring soon" warning state.** `EXPIRED` is a status, not a gradient.
- **No auto-expiry of enquiries or orders.** Neither carries a validity date.
- **No distributed lock** (D3), and **no per-quotation transaction** (D5).
- **No backfill.** The first run expires whatever is already past `validUntil`, which may be a
  large batch on a mature tenant. That is correct, not a migration.

## 10. Challenge-log entries this slice owes

Per `CLAUDE.md`, written as part of the same change, not later:

- **The `runAs`-before-transaction ordering, made structural.** Why context set after the
  transaction opens binds to nothing and fails *silently* (zero rows, no error), and why
  `TenantJobRunner` uses `TransactionTemplate` rather than `@Transactional` — the
  self-invocation trap that would otherwise collapse the per-tenant boundary without any
  visible symptom.
- **IST date arithmetic against a `LocalDate` field.** Why `validUntil < todayInIst` and not a
  UTC date, and why the boundary lands mid-evening UTC.
