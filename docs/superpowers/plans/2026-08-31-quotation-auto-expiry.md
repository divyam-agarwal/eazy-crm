# Scheduled Quotation Auto-Expiry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A nightly job flips `SENT` quotations whose `validUntil` has passed to `EXPIRED`, leaving an audit row and a `SYSTEM` activity behind, for every `TRIAL` and `ACTIVE` tenant.

**Architecture:** A reusable `TenantJobRunner` in `platform/job/` iterates tenants, wraps each in `TenantContext.runAs(...)` with a synthetic `SYSTEM` principal, and opens **one transaction per tenant** via a programmatic `TransactionTemplate`. A thin `@Scheduled` job resolves today's IST date from the `Clock` bean and hands it to a `QuotationExpirySweep` body. Each flip publishes a `QuotationExpiredEvent` that two synchronous listeners turn into an audit row and a timeline activity.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA / Hibernate 7 (`@TenantId`), PostgreSQL 16 with RLS, JUnit 5, Testcontainers, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-08-31-quotation-auto-expiry-design.md`

## Global Constraints

- **Commits author as `divyam`.** Plain `git commit`, no `-c user.name=...`. Never add a `Co-Authored-By: Claude` trailer and never mention Claude or AI in a commit message.
- **Money is never a `double`.** Not touched by this slice, but do not introduce one.
- **Tenant isolation is structural.** Never hand-write `WHERE tenant_id = ?`. Rely on Hibernate `@TenantId` + Postgres RLS; the tenant comes from `TenantContext` only.
- **No new entity, table, column, or Flyway migration in this slice.** If you find yourself writing one, stop — the design says this slice adds none, so something has gone wrong.
- **Filtered test runs must be project-qualified.** Use `./gradlew :test --tests '<filter>'` for a root-project test. Bare `./gradlew test --tests '...'` applies the filter to *every* project and then fails on whichever has no match.
- **Baseline is 432 tests, 0 failures, 0 errors.** Count with:
  ```bash
  find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
    | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
  ```
  The `'*/build/...'` glob spans both projects. A root-only variant reports a phantom 409.
- **Docker must be running** before any integration test: `open -a Docker`, wait for `docker info`.
- All paths below are relative to `backend/`.

---

## File Structure

**Create:**

| File | Responsibility |
|---|---|
| `src/main/java/com/easycrm/platform/job/TenantJobRunner.java` | The reusable seam: iterate tenants, `runAs` → transaction, isolate failures, retry once on optimistic lock |
| `src/main/java/com/easycrm/platform/job/SchedulingConfig.java` | `@EnableScheduling`, nothing else |
| `src/main/java/com/easycrm/sales/QuotationExpirySweep.java` | One tenant's worth of expiry work, given `asOf` |
| `src/main/java/com/easycrm/sales/QuotationExpiryJob.java` | Thin `@Scheduled` wrapper; the only class that reads the `Clock` |
| `src/main/java/com/easycrm/sales/QuotationExpiredEvent.java` | The event record |
| `src/main/java/com/easycrm/sales/QuotationExpiredAuditListener.java` | `QUOTATION_EXPIRED` audit row |
| `src/main/java/com/easycrm/sales/QuotationExpiredActivityListener.java` | `SYSTEM` activity on the quotation's timeline |
| `src/test/java/com/easycrm/sales/QuotationTest.java` | Unit tests for `Quotation.expire()`'s precondition |
| `src/test/java/com/easycrm/sales/QuotationExpirySpecificationTest.java` | Integration tests for the candidate query |
| `src/test/java/com/easycrm/platform/job/TenantJobRunnerTest.java` | Integration tests for the seam, incl. the isolation proof |
| `src/test/java/com/easycrm/sales/QuotationExpirySweepTest.java` | Integration tests for the sweep, trace, and idempotence |
| `src/test/java/com/easycrm/sales/QuotationExpiryJobSchedulingTest.java` | Proves the cron really is disabled under test |

**Modify:**

| File | Change |
|---|---|
| `src/main/java/com/easycrm/platform/time/DueWindow.java` | Add `todayDate(Instant)` |
| `src/main/java/com/easycrm/sales/Quotation.java` | `expire()` gains a `SENT` precondition |
| `src/main/java/com/easycrm/sales/QuotationSpecifications.java` | Add `expirableAsOf(LocalDate)` |
| `src/main/java/com/easycrm/platform/visibility/VisibleFinder.java` | Add `listQuotations(Specification)` |
| `src/main/java/com/easycrm/tenant/TenantRepository.java` | Add `findByStatusIn(Collection)` |
| `src/main/java/com/easycrm/sales/ActivityService.java` | Update `logSystem`'s Javadoc — it gains a second call site |
| `src/main/resources/application.yml` | Add `easycrm.jobs.quotation-expiry.cron` |
| `src/test/java/com/easycrm/support/IntegrationTest.java` | Disable the cron for the whole suite |
| `src/test/java/com/easycrm/platform/time/DueWindowTest.java` | Add IST-boundary tests for `todayDate` |
| `docs/superpowers/engineering-challenges.md` | Two new entries |
| `docs/superpowers/annotations-reference.md` | `@EnableScheduling`, `@Scheduled` |
| `docs/superpowers/HANDOFF.md` | Record the slice |
| `docs/superpowers/specs/2026-08-31-quotation-auto-expiry-design.md` | Correct one method name (Task 1) |

---

### Task 1: `DueWindow.todayDate` — today's date in IST

**Files:**
- Modify: `src/main/java/com/easycrm/platform/time/DueWindow.java`
- Modify: `docs/superpowers/specs/2026-08-31-quotation-auto-expiry-design.md`
- Test: `src/test/java/com/easycrm/platform/time/DueWindowTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `public static LocalDate DueWindow.todayDate(Instant now)`.

**Why `todayDate` and not `today`:** `DueWindow.today(Instant)` **already exists** and returns a `Window` record. The design spec §3.5 calls this new method `today(Instant)`, which would be an overload differing only in return type — not legal Java. The spec is wrong on this one name; Step 6 corrects it.

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/com/easycrm/platform/time/DueWindowTest.java` (inside the existing class):

```java
    @Test
    void todayDateIsStillYesterdayJustBeforeIstMidnight() {
        // IST is UTC+5:30, so IST midnight falls at 18:30 UTC the previous day.
        assertThat(DueWindow.todayDate(Instant.parse("2026-08-31T18:29:00Z")))
            .isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    void todayDateRollsOverExactlyAtIstMidnight() {
        assertThat(DueWindow.todayDate(Instant.parse("2026-08-31T18:30:00Z")))
            .isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void todayDateUsesIstNotUtcForAMiddayInstant() {
        // 2026-08-31T20:00Z is already 2026-09-01 01:30 IST.
        assertThat(DueWindow.todayDate(Instant.parse("2026-08-31T20:00:00Z")))
            .isEqualTo(LocalDate.of(2026, 9, 1));
    }
```

Add whatever imports the file is missing (`java.time.LocalDate`, `java.time.Instant`, `org.junit.jupiter.api.Test`, `static org.assertj.core.api.Assertions.assertThat`). Read the file's existing imports first rather than assuming.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :test --tests 'com.easycrm.platform.time.DueWindowTest'`
Expected: FAIL — compilation error, `cannot find symbol: method todayDate(Instant)`.

- [ ] **Step 3: Implement**

In `DueWindow`, add below the existing `today(Instant)` method:

```java
    /**
     * Today's date in IST. Distinct from {@link #today(Instant)}, which returns the day's
     * instant boundaries; this returns the calendar date itself, for comparison against a
     * {@code LocalDate} column such as {@code quotation_version.valid_until} that a user
     * entered in IST. Comparing such a column against a UTC date would expire quotations
     * 5½ hours early every day.
     */
    public static LocalDate todayDate(Instant now) {
        return now.atZone(IST).toLocalDate();
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :test --tests 'com.easycrm.platform.time.DueWindowTest'`
Expected: PASS.

- [ ] **Step 5: Refactor `today(Instant)` to use it**

`today(Instant)` computes `now.atZone(IST).toLocalDate()` on its first line. Replace that line with `LocalDate todayInIst = todayDate(now);` so there is one definition of "today in IST", not two. Re-run the test class; still PASS.

- [ ] **Step 6: Correct the spec's method name**

In `docs/superpowers/specs/2026-08-31-quotation-auto-expiry-design.md`, §3.5 reads:

```
- **`DueWindow.today(Instant)`** — today's date in IST.
```

Change `DueWindow.today(Instant)` to `DueWindow.todayDate(Instant)`, and in §5 step 1 change `DueWindow.today(clock.instant())` to `DueWindow.todayDate(clock.instant())`. Leave everything else alone.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/easycrm/platform/time/DueWindow.java \
        src/test/java/com/easycrm/platform/time/DueWindowTest.java \
        docs/superpowers/specs/2026-08-31-quotation-auto-expiry-design.md
git commit -m "feat: add DueWindow.todayDate for IST calendar-date comparisons"
```

---

### Task 2: `Quotation.expire()` names its own precondition

**Files:**
- Modify: `src/main/java/com/easycrm/sales/Quotation.java:53`
- Test: `src/test/java/com/easycrm/sales/QuotationTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `Quotation.expire()` now throws `com.easycrm.platform.error.ValidationException` unless `status == SENT`.

**Why:** `expire()` is currently unguarded — the `SENT` check lives only in `QuotationService.requireSent`. The sweep becomes a second caller. `Order.dispatch()`/`close()`/`cancel()` each name their own precondition and throw `ValidationException` from the entity; deferred-Minor #13 records that as the pattern to copy. The message string is **identical** to `QuotationService.requireSent`'s so the API's 422 body is unchanged whichever guard fires first (the service's still fires first, so no existing test changes).

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/easycrm/sales/QuotationTest.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.error.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for Quotation's own transition preconditions. No Spring, no database. */
class QuotationTest {

    private Quotation sentQuotation() {
        Quotation q = new Quotation(UUID.randomUUID(), null);
        q.markSent();
        return q;
    }

    @Test
    void expiresASentQuotation() {
        Quotation q = sentQuotation();
        q.expire();
        assertThat(q.getStatus()).isEqualTo(QuotationStatus.EXPIRED);
    }

    @Test
    void refusesToExpireADraftAndLeavesTheStatusUnmutated() {
        Quotation q = new Quotation(UUID.randomUUID(), null); // starts DRAFT
        assertThatThrownBy(q::expire)
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("only a sent quotation can be expired");
        assertThat(q.getStatus()).isEqualTo(QuotationStatus.DRAFT);
    }

    @Test
    void refusesToExpireAnAcceptedQuotationAndLeavesTheStatusUnmutated() {
        Quotation q = sentQuotation();
        q.markAccepted();
        assertThatThrownBy(q::expire).isInstanceOf(ValidationException.class);
        assertThat(q.getStatus()).isEqualTo(QuotationStatus.ACCEPTED);
    }

    @Test
    void refusesToExpireARejectedQuotationAndLeavesTheStatusUnmutated() {
        Quotation q = sentQuotation();
        q.reject();
        assertThatThrownBy(q::expire).isInstanceOf(ValidationException.class);
        assertThat(q.getStatus()).isEqualTo(QuotationStatus.REJECTED);
    }

    @Test
    void refusesToExpireAnAlreadyExpiredQuotationAndLeavesTheStatusUnmutated() {
        Quotation q = sentQuotation();
        q.expire();
        assertThatThrownBy(q::expire).isInstanceOf(ValidationException.class);
        assertThat(q.getStatus()).isEqualTo(QuotationStatus.EXPIRED);
    }
}
```

Asserting the *unmutated status*, not merely the exception type, is deliberate: deferred-Minor #11 records that `OrderTest` asserts only the type, so a future guard reorder would go uncaught there. Do not repeat that on new code.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :test --tests 'com.easycrm.sales.QuotationTest'`
Expected: FAIL — the four refusal tests fail because `expire()` currently mutates unconditionally and throws nothing.

- [ ] **Step 3: Implement**

In `src/main/java/com/easycrm/sales/Quotation.java`, replace:

```java
    public void expire() { this.status = QuotationStatus.EXPIRED; }
```

with:

```java
    /**
     * SENT -> EXPIRED. The check runs before any assignment, so a rejected expire leaves
     * the quotation untouched. The message matches QuotationService.requireSent's exactly:
     * the service still checks first for a user-initiated expire, so the API's 422 body is
     * unchanged, and the scheduled sweep gets the same contract from the entity.
     */
    public void expire() {
        if (status != QuotationStatus.SENT) {
            throw new ValidationException("status", "only a sent quotation can be expired");
        }
        this.status = QuotationStatus.EXPIRED;
    }
```

Add the import `com.easycrm.platform.error.ValidationException`.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :test --tests 'com.easycrm.sales.QuotationTest'`
Expected: PASS.

- [ ] **Step 5: Run the quotation web tests for regressions**

Run: `./gradlew :test --tests 'com.easycrm.sales.web.*'`
Expected: PASS. The service's `requireSent` still runs first, so nothing about the 422 contract moved.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/easycrm/sales/Quotation.java \
        src/test/java/com/easycrm/sales/QuotationTest.java
git commit -m "feat: guard Quotation.expire with its own SENT precondition"
```

---

### Task 3: The candidate query — `expirableAsOf` + `VisibleFinder.listQuotations`

**Files:**
- Modify: `src/main/java/com/easycrm/sales/QuotationSpecifications.java`
- Modify: `src/main/java/com/easycrm/platform/visibility/VisibleFinder.java`
- Test: `src/test/java/com/easycrm/sales/QuotationExpirySpecificationTest.java` (create)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `public static Specification<Quotation> QuotationSpecifications.expirableAsOf(LocalDate asOf)`
  - `public List<Quotation> VisibleFinder.listQuotations(Specification<Quotation> filter)`

**Two constraints that decide the shape:**

1. `QuotationRepository` is in `VisibilityScopingArchTest.GUARDED_REPOSITORIES`. Any read of it outside `com.easycrm.platform.visibility..` fails the build. So the sweep **must** read through `VisibleFinder`, not a new derived finder. Under the `SYSTEM` principal the visibility filter is a provable no-op, but the guard forces the read into the one legal place.
2. `Quotation` holds a raw `currentVersionId` UUID, **not** a `@ManyToOne` to `QuotationVersion`. A Criteria `join` is therefore impossible; a correlated `EXISTS` subquery is the only option. `VisibilityPolicy.viaCustomer` already uses exactly this idiom — copy its shape.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/easycrm/sales/QuotationExpirySpecificationTest.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.visibility.VisibleFinder;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the candidate query selects exactly the quotations that should auto-expire.
 * Integration rather than unit because the EXISTS subquery against QuotationVersion only
 * exists once Hibernate builds SQL for it. See spec 2026-08-31 §4.
 */
@SpringBootTest
class QuotationExpirySpecificationTest extends IntegrationTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Autowired QuotationRepository quotations;
    @Autowired QuotationVersionRepository versions;
    @Autowired VisibleFinder finder;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private int seq = 0;

    private UUID lapsedSent, todaySent, openEndedSent, lapsedDraft, lapsedAccepted,
                 lapsedRejected, lapsedAlreadyExpired;

    @BeforeEach
    void seed() {
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"));
        tx.execute(s -> {
            lapsedSent           = seed(AS_OF.minusDays(1), QuotationStatus.SENT);
            todaySent            = seed(AS_OF,              QuotationStatus.SENT);
            openEndedSent        = seed(null,               QuotationStatus.SENT);
            lapsedDraft          = seed(AS_OF.minusDays(1), QuotationStatus.DRAFT);
            lapsedAccepted       = seed(AS_OF.minusDays(1), QuotationStatus.ACCEPTED);
            lapsedRejected       = seed(AS_OF.minusDays(1), QuotationStatus.REJECTED);
            lapsedAlreadyExpired = seed(AS_OF.minusDays(1), QuotationStatus.EXPIRED);
            return null;
        });
        TenantContext.clear();
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void selectsOnlyTheSentQuotationWhoseValidUntilHasLapsed() {
        assertThat(idsOfCandidates()).containsExactly(lapsedSent);
    }

    @Test
    void doesNotSelectAQuotationValidThroughToday() {
        assertThat(idsOfCandidates()).doesNotContain(todaySent);
    }

    @Test
    void doesNotSelectAnOpenEndedQuotation() {
        assertThat(idsOfCandidates()).doesNotContain(openEndedSent);
    }

    @Test
    void doesNotSelectNonSentStatusesHoweverStaleTheirDate() {
        assertThat(idsOfCandidates())
            .doesNotContain(lapsedDraft, lapsedAccepted, lapsedRejected, lapsedAlreadyExpired);
    }

    private List<UUID> idsOfCandidates() {
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"));
        try {
            return tx.execute(s -> finder
                .listQuotations(QuotationSpecifications.expirableAsOf(AS_OF))
                .stream().map(Quotation::getId).toList());
        } finally {
            TenantContext.clear();
        }
    }

    /** A quotation with one current version carrying the given validUntil, in the given status. */
    private UUID seed(LocalDate validUntil, QuotationStatus status) {
        Quotation q = quotations.saveAndFlush(new Quotation(UUID.randomUUID(), null));
        QuotationVersion v = versions.saveAndFlush(new QuotationVersion(q.getId(), 1, "27"));
        v.setHeader(validUntil, null, null, null);
        v.setTotals(BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("11"));
        versions.saveAndFlush(v);
        q.setCurrentVersionId(v.getId());
        q.assignQuoteNo("Q-EXP-" + (++seq));
        // Walk the real lifecycle rather than setting the field: markSent() is the only
        // door into SENT, and the terminal statuses are only reachable through it.
        q.markSent();
        switch (status) {
            case DRAFT    -> q.reviseToDraft();
            case ACCEPTED -> q.markAccepted();
            case REJECTED -> q.reject();
            case EXPIRED  -> q.expire();
            case SENT     -> { }
        }
        return quotations.saveAndFlush(q).getId();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :test --tests 'com.easycrm.sales.QuotationExpirySpecificationTest'`
Expected: FAIL — compilation error, neither `expirableAsOf` nor `listQuotations` exists.

- [ ] **Step 3: Implement the specification**

In `src/main/java/com/easycrm/sales/QuotationSpecifications.java`, add:

```java
    /**
     * The scheduled auto-expiry candidate set: SENT quotations whose CURRENT version's
     * validUntil is strictly before {@code asOf}. Strictly-before is the contract — a quote
     * valid until the 31st is valid for all of the 31st and expires once IST reaches the 1st.
     *
     * <p>A correlated EXISTS subquery rather than a join because Quotation holds a raw
     * currentVersionId UUID, not a @ManyToOne — the same idiom VisibilityPolicy.viaCustomer
     * uses. QuotationVersion is itself @TenantId-scoped and runs under RLS, so the subquery
     * cannot reach another tenant's versions.
     *
     * <p>The isNotNull is redundant against SQL's null semantics (NULL &lt; x is never true)
     * and is present to state the intent: an open-ended quotation never expires.
     */
    public static Specification<Quotation> expirableAsOf(LocalDate asOf) {
        return (root, query, cb) -> {
            Subquery<UUID> sub = query.subquery(UUID.class);
            Root<QuotationVersion> v = sub.from(QuotationVersion.class);
            sub.select(v.get("id"));
            sub.where(cb.and(
                cb.equal(v.get("id"), root.get("currentVersionId")),
                cb.isNotNull(v.get("validUntil")),
                cb.lessThan(v.<LocalDate>get("validUntil"), asOf)));
            return cb.and(
                cb.equal(root.get("status"), QuotationStatus.SENT),
                cb.exists(sub));
        };
    }
```

Add imports: `jakarta.persistence.criteria.Root`, `jakarta.persistence.criteria.Subquery`, `java.time.LocalDate`.

The explicit `v.<LocalDate>get("validUntil")` type witness is required — `cb.lessThan` needs a `Expression<? extends Comparable>` and an untyped `Path` will not compile.

- [ ] **Step 4: Implement the finder method**

In `src/main/java/com/easycrm/platform/visibility/VisibleFinder.java`, add beside `pageQuotations`:

```java
    /**
     * Unpaged list read, for internal sweeps that must see every matching row rather than a
     * page of them. Exists here and not on the caller because QuotationRepository is a
     * guarded repository — VisibilityScopingArchTest fails the build on any read of it
     * outside this package. Under a synthetic SYSTEM principal the policy is unrestricted,
     * so the filter argument does the real work; the routing is what the guard requires.
     */
    public List<Quotation> listQuotations(Specification<Quotation> filter) {
        return quotations.findAll(and(policy.quotations(), filter));
    }
```

Add the import `java.util.List`.

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :test --tests 'com.easycrm.sales.QuotationExpirySpecificationTest'`
Expected: PASS, 4 tests.

- [ ] **Step 6: Run the ArchUnit guards**

Run: `./gradlew :test --tests 'com.easycrm.arch.*'`
Expected: PASS. `VisibilityScopingArchTest` in particular — if it fails, something is reading `QuotationRepository` from outside the visibility package.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/easycrm/sales/QuotationSpecifications.java \
        src/main/java/com/easycrm/platform/visibility/VisibleFinder.java \
        src/test/java/com/easycrm/sales/QuotationExpirySpecificationTest.java
git commit -m "feat: add the auto-expiry candidate query behind VisibleFinder"
```

---

### Task 4: `TenantJobRunner` — the reusable seam

**Files:**
- Create: `src/main/java/com/easycrm/platform/job/TenantJobRunner.java`
- Modify: `src/main/java/com/easycrm/tenant/TenantRepository.java`
- Test: `src/test/java/com/easycrm/platform/job/TenantJobRunnerTest.java` (create)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `public TenantJobRunner.JobSummary forEachTenant(String jobName, ToIntFunction<UUID> body)`
  - `public record TenantJobRunner.JobSummary(int tenantsSwept, int tenantsFailed, int itemsProcessed)`
  - `List<Tenant> TenantRepository.findByStatusIn(Collection<TenantStatus> statuses)`

**This is the load-bearing task.** Read all of it before writing anything.

Three traps, each of which fails *silently* if you get it wrong:

1. **`runAs` must wrap `tx.execute`, never the reverse.** `TenantAwareTransactionManager.doBegin` reads `TenantContext` to set the `app.current_tenant` GUC, and Hibernate resolves a session's tenant once at session-open and never re-reads it (challenge #9). Context set *after* the transaction opens binds to nothing — and `doBegin` returns early with the GUC unset, so scoped tables return **zero rows** rather than raising. Step 6 proves this is actually tested.
2. **Use `TransactionTemplate`, not `@Transactional`.** A `@Transactional` method invoked from this class's own loop is a self-invocation: Spring's proxy is bypassed entirely and the "per-tenant transaction" silently joins whatever transaction the caller already had, collapsing the per-tenant boundary. Programmatic transaction management removes the trap rather than documenting it.
3. **`TenantContext.runAs` is overloaded** on `Runnable` and `Supplier<T>`. An expression lambda whose body is a method call — `() -> tx.execute(...)` — is compatible with both and will not compile ("reference to runAs is ambiguous"). Bind it to a typed `Supplier<Integer>` local first, as the implementation below does.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/easycrm/platform/job/TenantJobRunnerTest.java`:

```java
package com.easycrm.platform.job;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import com.easycrm.tenant.TenantStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seam every future scheduled job inherits. The isolation test below is the one that
 * matters: it is the only thing proving runAs ran BEFORE the transaction opened, which is
 * the failure mode that returns zero rows instead of raising. See spec 2026-08-31 §3.1.
 */
@SpringBootTest
class TenantJobRunnerTest extends IntegrationTest {

    @Autowired TenantJobRunner runner;
    @Autowired TenantRepository tenants;
    @Autowired CustomerRepository customers;

    private UUID trialA, activeB, suspendedC;
    private String slugSeed;

    @BeforeEach
    void seed() {
        slugSeed = UUID.randomUUID().toString().substring(0, 8);
        trialA     = newTenant("trial-a",     TenantStatus.TRIAL);
        activeB    = newTenant("active-b",    TenantStatus.ACTIVE);
        suspendedC = newTenant("suspended-c", TenantStatus.SUSPENDED);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void sweepsTrialAndActiveTenantsAndSkipsSuspendedOnes() {
        List<UUID> visited = new ArrayList<>();
        runner.forEachTenant("test-job", t -> { visited.add(t); return 0; });

        assertThat(visited).contains(trialA, activeB);
        assertThat(visited).doesNotContain(suspendedC);
    }

    @Test
    void bindsTheTenantContextInsideTheBody() {
        List<UUID> seenInContext = new ArrayList<>();
        runner.forEachTenant("test-job", t -> {
            seenInContext.add(TenantContext.tenantId());
            return 0;
        });

        assertThat(seenInContext).contains(trialA, activeB);
    }

    /**
     * THE load-bearing test. Each tenant gets a customer; the body counts the customers it
     * can see. If runAs and tx.execute were ordered the other way round the GUC would be
     * unset, RLS would return zero rows, and every count would be 0 rather than 1.
     */
    @Test
    void eachTenantsBodySeesOnlyItsOwnRows() {
        seedOneCustomerFor(trialA);
        seedOneCustomerFor(activeB);

        Map<UUID, Long> counts = new HashMap<>();
        runner.forEachTenant("test-job", t -> {
            counts.put(t, customers.count());
            return 0;
        });

        // Each of MY tenants sees exactly its own single customer -- never zero (context
        // not bound) and never the cross-tenant total (isolation broken). Keyed by tenant
        // rather than asserted over every count: the sweep also visits tenants left behind
        // by other test classes, which have no customers and would contribute 0.
        assertThat(counts).containsEntry(trialA, 1L);
        assertThat(counts).containsEntry(activeB, 1L);
    }

    @Test
    void oneFailingTenantDoesNotAbortTheSweep() {
        List<UUID> visited = new ArrayList<>();
        TenantJobRunner.JobSummary summary = runner.forEachTenant("test-job", t -> {
            visited.add(t);
            if (t.equals(trialA)) throw new IllegalStateException("boom");
            return 1;
        });

        assertThat(visited).contains(trialA, activeB);   // reached the later tenant anyway
        assertThat(summary.tenantsFailed()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void retriesATenantOnceAfterAnOptimisticLockFailure() {
        AtomicInteger attemptsForA = new AtomicInteger();
        TenantJobRunner.JobSummary summary = runner.forEachTenant("test-job", t -> {
            if (!t.equals(trialA)) return 0;
            if (attemptsForA.getAndIncrement() == 0) {
                throw new ObjectOptimisticLockingFailureException(Customer.class, UUID.randomUUID());
            }
            return 7;
        });

        assertThat(attemptsForA.get()).isEqualTo(2);              // tried, failed, retried
        assertThat(summary.itemsProcessed()).isGreaterThanOrEqualTo(7);
    }

    @Test
    void sumsTheItemCountsTheBodyReports() {
        TenantJobRunner.JobSummary summary =
            runner.forEachTenant("test-job", t -> t.equals(trialA) ? 3 : 0);

        assertThat(summary.itemsProcessed()).isGreaterThanOrEqualTo(3);
        assertThat(summary.tenantsSwept()).isGreaterThanOrEqualTo(2);
    }

    private UUID newTenant(String name, TenantStatus status) {
        Tenant t = new Tenant(name + "-" + slugSeed, "Test " + name, "27", null, status, null);
        return tenants.saveAndFlush(t).getId();
    }

    private void seedOneCustomerFor(UUID tenantId) {
        // BLOCK lambda, not an expression lambda: runAs is overloaded on Runnable and
        // Supplier, and `() -> customers.saveAndFlush(...)` matches both ("reference to
        // runAs is ambiguous"). The braces make it unambiguously a Runnable.
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"),
            () -> {
                customers.saveAndFlush(new Customer(
                    "Customer of " + tenantId, null, "27", null, null, 0, null, null,
                    CustomerSource.MANUAL));
            });
    }
}
```

Note on assertions: they use `contains` / `isGreaterThanOrEqualTo` rather than exact equality because this suite shares one Postgres container and other test classes create tenants too. Asserting "at least mine, and never the suspended one" is the assertion that stays true regardless of execution order. **Do not tighten these to `containsExactly`** — it will pass alone and fail in the full suite.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :test --tests 'com.easycrm.platform.job.TenantJobRunnerTest'`
Expected: FAIL — compilation error, `TenantJobRunner` does not exist.

- [ ] **Step 3: Add the tenant query**

In `src/main/java/com/easycrm/tenant/TenantRepository.java`, add:

```java
    /**
     * Tenants a scheduled job should act on. Callers pass TRIAL + ACTIVE; SUSPENDED is
     * deliberately excluded (spec 2026-08-31 D4). Tenant is a GLOBAL table -- no @TenantId,
     * no RLS -- so this is legitimately callable with no tenant context set, which is
     * exactly the situation a job starts in.
     */
    List<Tenant> findByStatusIn(Collection<TenantStatus> statuses);
```

Add imports `java.util.Collection` and `java.util.List`.

- [ ] **Step 4: Implement the runner**

Create `src/main/java/com/easycrm/platform/job/TenantJobRunner.java`:

```java
package com.easycrm.platform.job;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import com.easycrm.tenant.TenantStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Runs a unit of work once per tenant, with no HTTP request and no JWT behind it. The
 * codebase's first non-request execution path; every later scheduled job should come
 * through here rather than growing its own tenant loop.
 *
 * <p>Three things it exists to get right, all of which fail SILENTLY when hand-rolled:
 *
 * <ol>
 *   <li><b>Ordering.</b> {@code runAs} wraps {@code tx.execute}, never the reverse.
 *       TenantAwareTransactionManager.doBegin reads TenantContext to set the
 *       app.current_tenant GUC, and Hibernate resolves a session's tenant once at
 *       session-open and never re-reads it (challenge #9). Context set after the
 *       transaction opens binds to nothing, and doBegin returns early leaving the GUC
 *       unset -- so scoped tables return ZERO ROWS instead of raising.</li>
 *   <li><b>TransactionTemplate, not @Transactional.</b> A @Transactional method called from
 *       this class's own loop is a self-invocation: the proxy is bypassed and the per-tenant
 *       transaction silently joins the caller's, collapsing the boundary this class exists
 *       to draw.</li>
 *   <li><b>Failure isolation.</b> One tenant's bad data must not abort the sweep.</li>
 * </ol>
 *
 * <p>The principal is synthetic: {@code (tenantId, null, "SYSTEM")}, the same shape
 * AuthService uses pre-authentication. VisibilityPolicy treats it as unrestricted (only
 * SALES_EXEC is restricted), which is correct -- a job must see the whole tenant -- and
 * AuditLog.actorUserId is nullable, so the null user id records honestly that no human
 * did this. See spec 2026-08-31-quotation-auto-expiry-design.md §3.1.
 */
@Component
public class TenantJobRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantJobRunner.class);

    private static final List<TenantStatus> JOB_ELIGIBLE =
        List.of(TenantStatus.TRIAL, TenantStatus.ACTIVE);

    private final TenantRepository tenants;
    private final TransactionTemplate tx;

    public TenantJobRunner(TenantRepository tenants, TransactionTemplate tx) {
        this.tenants = tenants;
        this.tx = tx;
    }

    /** What one sweep did. Counts are for the log line, not for control flow. */
    public record JobSummary(int tenantsSwept, int tenantsFailed, int itemsProcessed) {}

    /**
     * Runs {@code body} once per job-eligible tenant, each in its own transaction with the
     * tenant context bound. The body returns how many items it processed, purely so the
     * summary can say something useful.
     *
     * <p>Reading the tenant list happens with NO tenant context, which is safe because
     * Tenant is a global table (TenantScopingArchTest.GLOBAL_TABLES).
     */
    public JobSummary forEachTenant(String jobName, ToIntFunction<UUID> body) {
        int swept = 0;
        int failed = 0;
        int items = 0;

        for (Tenant tenant : tenants.findByStatusIn(JOB_ELIGIBLE)) {
            try {
                items += runWithRetry(jobName, tenant.getId(), body);
                swept++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("job {} failed for tenant {}", jobName, tenant.getId(), e);
            }
        }

        log.info("job {} finished: {} tenants swept, {} failed, {} items processed",
                 jobName, swept, failed, items);
        return new JobSummary(swept, failed, items);
    }

    /**
     * One retry on optimistic-lock failure only. With one transaction per tenant, a user
     * updating a row mid-sweep rolls back that tenant's WHOLE batch, not just the contended
     * row -- so the common case (one user, one record, a sub-second window) would otherwise
     * cost a tenant an entire run. The retry is bounded at one: repeated contention within a
     * single sweep is rare and self-corrects on the next run. See spec §6.
     */
    private int runWithRetry(String jobName, UUID tenantId, ToIntFunction<UUID> body) {
        try {
            return runInTenant(tenantId, body);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.info("job {} hit a concurrent update for tenant {}; retrying once",
                     jobName, tenantId);
            return runInTenant(tenantId, body);
        }
    }

    /**
     * The ordering that matters. Note the typed Supplier local: TenantContext.runAs is
     * overloaded on Runnable and Supplier, and an expression lambda whose body is a method
     * call matches both -- inlining this would not compile ("reference to runAs is
     * ambiguous").
     */
    private int runInTenant(UUID tenantId, ToIntFunction<UUID> body) {
        Supplier<Integer> work = () -> tx.execute(status -> body.applyAsInt(tenantId));
        Integer processed = TenantContext.runAs(
            new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"), work);
        return processed == null ? 0 : processed;
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :test --tests 'com.easycrm.platform.job.TenantJobRunnerTest'`
Expected: PASS, 6 tests.

- [ ] **Step 6: Prove the isolation test can fail — MANDATORY, do not skip**

A test that cannot fail is not a test. The whole point of `eachTenantsBodySeesOnlyItsOwnRows` is to catch the ordering trap, so confirm it actually does.

Temporarily invert the ordering in `runInTenant`:

```java
    private int runInTenant(UUID tenantId, ToIntFunction<UUID> body) {
        // DELIBERATELY WRONG -- transaction opens BEFORE the context is set.
        Integer processed = tx.execute(status -> {
            Supplier<Integer> work = () -> body.applyAsInt(tenantId);
            return TenantContext.runAs(
                new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"), work);
        });
        return processed == null ? 0 : processed;
    }
```

Run: `./gradlew :test --tests 'com.easycrm.platform.job.TenantJobRunnerTest'`
Expected: **FAIL** on `eachTenantsBodySeesOnlyItsOwnRows` — the counts come back `0` because the GUC was never set, so RLS returned nothing.

If it still PASSES, the test is worthless as written and you must fix the test before going further — most likely `customers.count()` is not running under RLS in the way assumed. Do not proceed on a green that means nothing.

Then **revert to the correct implementation from Step 4** and re-run to confirm PASS. This step is the reason challenge #33 was ever caught; it is not optional.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/easycrm/platform/job/TenantJobRunner.java \
        src/main/java/com/easycrm/tenant/TenantRepository.java \
        src/test/java/com/easycrm/platform/job/TenantJobRunnerTest.java
git commit -m "feat: add TenantJobRunner, the tenant-iterating seam for scheduled work"
```

---

### Task 5: The sweep, the event, and the two listeners

**Files:**
- Create: `src/main/java/com/easycrm/sales/QuotationExpiredEvent.java`
- Create: `src/main/java/com/easycrm/sales/QuotationExpirySweep.java`
- Create: `src/main/java/com/easycrm/sales/QuotationExpiredAuditListener.java`
- Create: `src/main/java/com/easycrm/sales/QuotationExpiredActivityListener.java`
- Modify: `src/main/java/com/easycrm/sales/ActivityService.java` (Javadoc only)
- Test: `src/test/java/com/easycrm/sales/QuotationExpirySweepTest.java` (create)

**Interfaces:**
- Consumes: `QuotationSpecifications.expirableAsOf(LocalDate)` and `VisibleFinder.listQuotations(Specification)` from Task 3; `Quotation.expire()`'s guard from Task 2; `TenantJobRunner.forEachTenant` from Task 4.
- Produces:
  - `public record QuotationExpiredEvent(UUID quotationId, String quoteNo, UUID quotationVersionId, LocalDate validUntil)`
  - `public int QuotationExpirySweep.run(LocalDate asOf)` — returns how many it expired.

**Note on the sweep's dependencies:** it injects `VisibleFinder`, `QuotationVersionRepository` and `ApplicationEventPublisher` — and deliberately **not** `QuotationRepository`, which is guarded. It never calls `save()`: the quotations are loaded inside the runner's transaction, so they are managed and Hibernate's dirty checking flushes the status change at commit.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/easycrm/sales/QuotationExpirySweepTest.java`:

```java
package com.easycrm.sales;

import com.easycrm.iam.AuditLog;
import com.easycrm.iam.AuditLogRepository;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sweep's behaviour for one tenant: what it flips, what it leaves alone, what trace it
 * leaves, and that running it twice changes nothing the second time. See spec 2026-08-31 §8.
 */
@SpringBootTest
class QuotationExpirySweepTest extends IntegrationTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Autowired QuotationExpirySweep sweep;
    @Autowired QuotationRepository quotations;
    @Autowired QuotationVersionRepository versions;
    @Autowired ActivityRepository activities;
    @Autowired AuditLogRepository auditLogs;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private int seq = 0;

    private UUID lapsed, stillValid;

    @BeforeEach
    void seed() {
        inTenant(() -> {
            lapsed = seedSent(AS_OF.minusDays(1));
            stillValid = seedSent(AS_OF.plusDays(7));
            return null;
        });
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void expiresTheLapsedQuotationAndReportsTheCount() {
        int expired = inTenant(() -> sweep.run(AS_OF));

        assertThat(expired).isEqualTo(1);
        assertThat(statusOf(lapsed)).isEqualTo(QuotationStatus.EXPIRED);
    }

    @Test
    void leavesAStillValidQuotationAlone() {
        inTenant(() -> sweep.run(AS_OF));
        assertThat(statusOf(stillValid)).isEqualTo(QuotationStatus.SENT);
    }

    @Test
    void writesAnAuditRowWithNoHumanActor() {
        inTenant(() -> sweep.run(AS_OF));

        Optional<AuditLog> row = inTenant(() -> auditLogs.findFirstByAction("QUOTATION_EXPIRED"));
        assertThat(row).isPresent();
        assertThat(row.get().getActorUserId()).isNull();
        assertThat(row.get().getDetail()).containsEntry("quotationId", lapsed.toString());
    }

    @Test
    void writesASystemActivityOnTheQuotationTimeline() {
        inTenant(() -> sweep.run(AS_OF));

        var page = inTenant(() -> activities.findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(
            SubjectType.QUOTATION, lapsed, PageRequest.of(0, 10)));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getSource()).isEqualTo(ActivitySource.SYSTEM);
        assertThat(page.getContent().get(0).getBody()).contains("expired");
    }

    @Test
    void isIdempotentAcrossTwoRuns() {
        inTenant(() -> sweep.run(AS_OF));
        int secondRun = inTenant(() -> sweep.run(AS_OF));

        assertThat(secondRun).isZero();
        assertThat(inTenant(() -> auditLogs.countByAction("QUOTATION_EXPIRED"))).isEqualTo(1L);

        var page = inTenant(() -> activities.findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(
            SubjectType.QUOTATION, lapsed, PageRequest.of(0, 10)));
        assertThat(page.getContent()).hasSize(1);
    }

    // --- helpers ---------------------------------------------------------------------

    /** Runs body with this test's tenant bound and a transaction open, in that order. */
    private <T> T inTenant(java.util.function.Supplier<T> body) {
        java.util.function.Supplier<T> work = () -> tx.execute(s -> body.get());
        try {
            return TenantContext.runAs(
                new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"), work);
        } finally {
            TenantContext.clear();
        }
    }

    private QuotationStatus statusOf(UUID id) {
        return inTenant(() -> quotations.findById(id).orElseThrow().getStatus());
    }

    private UUID seedSent(LocalDate validUntil) {
        Quotation q = quotations.saveAndFlush(new Quotation(UUID.randomUUID(), null));
        QuotationVersion v = versions.saveAndFlush(new QuotationVersion(q.getId(), 1, "27"));
        v.setHeader(validUntil, null, null, null);
        v.setTotals(BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("11"));
        versions.saveAndFlush(v);
        q.setCurrentVersionId(v.getId());
        q.assignQuoteNo("Q-SWEEP-" + (++seq));
        q.markSent();
        return quotations.saveAndFlush(q).getId();
    }
}
```

This test calls `quotations.findById` and `activities.find...` directly. That is legal: both ArchUnit guards import with `ImportOption.Predefined.DO_NOT_INCLUDE_TESTS`, so test classes are outside their scope.

`Activity` exposes `getSource()` (returning `ActivitySource`) and `getBody()` — both verified against `src/main/java/com/easycrm/sales/Activity.java:124,128`, so the assertions above compile as written.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :test --tests 'com.easycrm.sales.QuotationExpirySweepTest'`
Expected: FAIL — compilation error, `QuotationExpirySweep` does not exist.

- [ ] **Step 3: Create the event**

Create `src/main/java/com/easycrm/sales/QuotationExpiredEvent.java`:

```java
package com.easycrm.sales;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published when the scheduled sweep expires a quotation. Deliberately carries no
 * actorUserId, unlike QuotationAcceptedEvent: no human did this, and an always-null field
 * would invite a caller to start populating it. The audit listener writes null itself.
 */
public record QuotationExpiredEvent(UUID quotationId, String quoteNo,
                                    UUID quotationVersionId, LocalDate validUntil) {}
```

- [ ] **Step 4: Create the sweep**

Create `src/main/java/com/easycrm/sales/QuotationExpirySweep.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.visibility.VisibleFinder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * One tenant's worth of quotation auto-expiry. Takes {@code asOf} as a parameter and never
 * reads a clock: determinism in tests comes from passing the date in, because no test may
 * override the Clock bean without forking the Spring context every IntegrationTest shares
 * (see ClockConfig).
 *
 * <p>Assumes it is already running inside TenantJobRunner's per-tenant transaction with the
 * tenant context bound -- so the quotations it loads are MANAGED and the status change is
 * flushed by dirty checking. It deliberately does not inject QuotationRepository: that
 * repository is guarded, and every read goes through VisibleFinder.
 */
@Component
public class QuotationExpirySweep {

    private final VisibleFinder finder;
    private final QuotationVersionRepository versions;
    private final ApplicationEventPublisher events;

    public QuotationExpirySweep(VisibleFinder finder, QuotationVersionRepository versions,
                                ApplicationEventPublisher events) {
        this.finder = finder;
        this.versions = versions;
        this.events = events;
    }

    /** Expires every lapsed SENT quotation in the current tenant. Returns how many. */
    public int run(LocalDate asOf) {
        List<Quotation> due = finder.listQuotations(QuotationSpecifications.expirableAsOf(asOf));
        for (Quotation q : due) {
            QuotationVersion version = versions.findById(q.getCurrentVersionId())
                .orElseThrow(() -> new NotFoundException("quotation version not found"));
            q.expire();
            events.publishEvent(new QuotationExpiredEvent(
                q.getId(), q.getQuoteNo(), version.getId(), version.getValidUntil()));
        }
        return due.size();
    }
}
```

- [ ] **Step 5: Create the audit listener**

Create `src/main/java/com/easycrm/sales/QuotationExpiredAuditListener.java`:

```java
package com.easycrm.sales;

import com.easycrm.iam.AuditService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sits beside OrderAcceptedAuditListener and is wired the same way -- synchronous, in the
 * publisher's transaction (Spring default) -- so the audit row commits or rolls back with
 * the expiry itself (challenge #3 atomicity).
 *
 * <p>The actor is null: no human did this. AuditLog.actorUserId is nullable precisely so a
 * system-initiated change can say so rather than borrow someone's id.
 */
@Component
public class QuotationExpiredAuditListener {

    private final AuditService audit;

    public QuotationExpiredAuditListener(AuditService audit) { this.audit = audit; }

    @EventListener
    public void on(QuotationExpiredEvent e) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("quotationId", e.quotationId().toString());
        detail.put("quoteNo", e.quoteNo());
        detail.put("quotationVersionId", e.quotationVersionId().toString());
        detail.put("validUntil", e.validUntil() == null ? null : e.validUntil().toString());
        audit.record("QUOTATION_EXPIRED", null, detail);
    }
}
```

- [ ] **Step 6: Create the activity listener**

Create `src/main/java/com/easycrm/sales/QuotationExpiredActivityListener.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.format.IndianFormats;
import com.easycrm.platform.visibility.SubjectType;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Puts the auto-expiry on the quotation's own timeline, so a salesperson sees why a quote
 * stopped being live instead of finding a status that changed overnight with no explanation.
 * Mirrors QuotationAcceptedActivityListener exactly, including being synchronous and in the
 * publisher's transaction.
 *
 * <p>The actor is null -- logSystem's actorUserId is nullable for exactly this case.
 */
@Component
public class QuotationExpiredActivityListener {

    private final ActivityService activities;

    public QuotationExpiredActivityListener(ActivityService activities) {
        this.activities = activities;
    }

    @EventListener
    public void on(QuotationExpiredEvent e) {
        activities.logSystem(SubjectType.QUOTATION, e.quotationId(), ActivityType.NOTE,
            "Quotation " + e.quoteNo() + " expired — it was valid until "
                + IndianFormats.date(e.validUntil()),
            null);
    }
}
```

- [ ] **Step 7: Update `ActivityService.logSystem`'s Javadoc**

Its Javadoc currently claims "this method has exactly ONE call site — QuotationAcceptedActivityListener" and requires any new caller to justify itself. There are now two. In `src/main/java/com/easycrm/sales/ActivityService.java`, find that paragraph and replace the sentence:

```
     * only sound because this method has exactly ONE call site --
     * QuotationAcceptedActivityListener, added in a later task. Any new caller must be
     * able to make the same claim; one that cannot wants create() and the full gate.
```

with:

```
     * only sound because every call site can make it. There are two:
     * QuotationAcceptedActivityListener, and QuotationExpiredActivityListener (whose
     * subject was loaded through VisibleFinder.listQuotations inside
     * QuotationExpirySweep). Any new caller must be able to make the same claim; one that
     * cannot wants create() and the full gate.
```

Match the file's exact existing text when editing — the block above is the substance, not necessarily the exact wrapping.

- [ ] **Step 8: Run to verify it passes**

Run: `./gradlew :test --tests 'com.easycrm.sales.QuotationExpirySweepTest'`
Expected: PASS, 5 tests.

- [ ] **Step 9: Run the ArchUnit guards**

Run: `./gradlew :test --tests 'com.easycrm.arch.*'`
Expected: PASS. `ActivityRepositoryScopingArchTest` matters here — only `ActivityService` may touch `ActivityRepository`, and the new listener goes through the service, so it should stay green.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/easycrm/sales/QuotationExpiredEvent.java \
        src/main/java/com/easycrm/sales/QuotationExpirySweep.java \
        src/main/java/com/easycrm/sales/QuotationExpiredAuditListener.java \
        src/main/java/com/easycrm/sales/QuotationExpiredActivityListener.java \
        src/main/java/com/easycrm/sales/ActivityService.java \
        src/test/java/com/easycrm/sales/QuotationExpirySweepTest.java
git commit -m "feat: expire lapsed quotations with an audit row and a timeline activity"
```

---

### Task 6: Wire the schedule

**Files:**
- Create: `src/main/java/com/easycrm/platform/job/SchedulingConfig.java`
- Create: `src/main/java/com/easycrm/sales/QuotationExpiryJob.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/com/easycrm/support/IntegrationTest.java`
- Test: `src/test/java/com/easycrm/sales/QuotationExpiryJobSchedulingTest.java` (create)

**Interfaces:**
- Consumes: `TenantJobRunner.forEachTenant` (Task 4), `QuotationExpirySweep.run(LocalDate)` (Task 5), `DueWindow.todayDate(Instant)` (Task 1).
- Produces: nothing later tasks depend on.

**The property must be verified, not assumed.** Challenge #42 found that test-property precedence on this project works the reverse of the obvious reading, and `IntegrationTest`'s own comment block spells out the `@TestPropertySource` vs `@DynamicPropertySource` rule. Step 5 exists so "the cron is off in tests" is a proven fact rather than a hope.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/easycrm/sales/QuotationExpiryJobSchedulingTest.java`:

```java
package com.easycrm.sales;

import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the auto-expiry cron really is disabled for the test suite. Without this, every
 * integration test would race a live nightly job against its own fixtures -- and the
 * failure would be intermittent and blamed on something else.
 *
 * <p>Asserting the registered task list, not just the property value: the property is the
 * mechanism, an unregistered task is the outcome. See spec 2026-08-31 §7 and challenge #42
 * on this project's test-property precedence.
 */
@SpringBootTest
class QuotationExpiryJobSchedulingTest extends IntegrationTest {

    @Autowired Environment environment;
    @Autowired ScheduledAnnotationBeanPostProcessor scheduledPostProcessor;
    @Autowired QuotationExpiryJob job;

    @Test
    void theCronIsDisabledForTheTestSuite() {
        assertThat(environment.getProperty("easycrm.jobs.quotation-expiry.cron")).isEqualTo("-");
    }

    @Test
    void noScheduledTaskIsRegisteredForTheJob() {
        assertThat(scheduledPostProcessor.getScheduledTasks()).isEmpty();
    }

    @Test
    void theJobBeanStillExistsSoTheWiringIsReal() {
        // A disabled cron must not mean a missing bean -- otherwise this test class would
        // pass for the wrong reason and production would have no job at all.
        assertThat(job).isNotNull();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :test --tests 'com.easycrm.sales.QuotationExpiryJobSchedulingTest'`
Expected: FAIL — compilation error, `QuotationExpiryJob` does not exist.

- [ ] **Step 3: Enable scheduling and add the job**

Create `src/main/java/com/easycrm/platform/job/SchedulingConfig.java`:

```java
package com.easycrm.platform.job;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on @Scheduled processing. Separate from any job so that "does this app run
 * scheduled work at all" is one grep, and so a test can reason about the scheduler without
 * pulling in a job's dependencies.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
```

Create `src/main/java/com/easycrm/sales/QuotationExpiryJob.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.job.TenantJobRunner;
import com.easycrm.platform.time.DueWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Nightly quotation auto-expiry. Deliberately thin: it resolves today's IST date and hands
 * off. Every test drives QuotationExpirySweep or TenantJobRunner directly, so no test ever
 * waits on a cron.
 *
 * <p>The zone is pinned to Asia/Kolkata rather than inherited from the server, so the job
 * fires at 00:30 IST wherever it is deployed -- just after the IST midnight at which a
 * lapsed quotation becomes expirable, so the flip is prompt rather than half a day late.
 */
@Component
public class QuotationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(QuotationExpiryJob.class);
    private static final String JOB_NAME = "quotation-expiry";

    private final TenantJobRunner runner;
    private final QuotationExpirySweep sweep;
    private final Clock clock;

    public QuotationExpiryJob(TenantJobRunner runner, QuotationExpirySweep sweep, Clock clock) {
        this.runner = runner;
        this.sweep = sweep;
        this.clock = clock;
    }

    /**
     * Catches everything on purpose. TenantJobRunner already isolates per-tenant failures,
     * but the tenant-list read itself sits outside that loop, and an exception escaping a
     * @Scheduled method is logged by Spring and then forgotten -- with no summary line, so
     * the run looks like it simply found nothing to do. Logging it here keeps a failed run
     * distinguishable from an empty one.
     */
    @Scheduled(cron = "${easycrm.jobs.quotation-expiry.cron}", zone = "Asia/Kolkata")
    public void run() {
        try {
            LocalDate asOf = DueWindow.todayDate(clock.instant());
            TenantJobRunner.JobSummary summary =
                runner.forEachTenant(JOB_NAME, tenantId -> sweep.run(asOf));
            log.info("quotation-expiry as of {}: {} expired across {} tenants ({} failed)",
                     asOf, summary.itemsProcessed(), summary.tenantsSwept(),
                     summary.tenantsFailed());
        } catch (RuntimeException e) {
            log.error("quotation-expiry run failed before it could sweep any tenant", e);
        }
    }
}
```

- [ ] **Step 4: Add the production property**

In `src/main/resources/application.yml`, inside the existing `easycrm:` block (it starts at line 38), add:

```yaml
  # Scheduled work. Cron is a property, not a constant, so it can be moved without a
  # release — and so the test suite can disable it outright ("-" is Spring's disabled-cron
  # value). 00:30 in the zone pinned on @Scheduled (Asia/Kolkata), i.e. just after the IST
  # midnight at which a lapsed quotation becomes expirable.
  jobs:
    quotation-expiry:
      cron: "0 30 0 * * *"
```

Match the file's existing two-space indentation under `easycrm:`.

- [ ] **Step 5: Disable it for the test suite**

In `src/test/java/com/easycrm/support/IntegrationTest.java`, change:

```java
@TestPropertySource(properties = "easycrm.rate-limit.enabled=false")
```

to:

```java
// The nightly auto-expiry cron is OFF for the suite, for the same reason the rate limiter
// is: every @SpringBootTest here shares ONE cached context, so a live job would race every
// test class's fixtures and fail intermittently, somewhere else, for reasons that look
// unrelated. "-" is Spring's Scheduled.CRON_DISABLED value, which skips task registration
// entirely rather than scheduling something that never fires.
@TestPropertySource(properties = {
    "easycrm.rate-limit.enabled=false",
    "easycrm.jobs.quotation-expiry.cron=-"
})
```

Leave the existing comment block above the annotation in place — it documents the `@DynamicPropertySource` precedence rule and is still true.

- [ ] **Step 6: Run to verify it passes**

Run: `./gradlew :test --tests 'com.easycrm.sales.QuotationExpiryJobSchedulingTest'`
Expected: PASS, 3 tests.

If `noScheduledTaskIsRegisteredForTheJob` fails, the property override did **not** take effect — do not work around it by deleting the assertion. Read `IntegrationTest`'s precedence comment and fix the wiring so the cron really is disabled.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/easycrm/platform/job/SchedulingConfig.java \
        src/main/java/com/easycrm/sales/QuotationExpiryJob.java \
        src/main/resources/application.yml \
        src/test/java/com/easycrm/support/IntegrationTest.java \
        src/test/java/com/easycrm/sales/QuotationExpiryJobSchedulingTest.java
git commit -m "feat: schedule quotation auto-expiry nightly at 00:30 IST"
```

---

### Task 7: Full verification and documentation

**Files:**
- Modify: `docs/superpowers/engineering-challenges.md`
- Modify: `docs/superpowers/annotations-reference.md`
- Modify: `docs/superpowers/HANDOFF.md`

**Interfaces:** none — this task produces documentation only.

- [ ] **Step 1: Run the whole suite and count**

```bash
open -a Docker   # if not already up; wait for `docker info` to succeed
cd backend && ./gradlew clean test
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'errors="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "errors:", s}'
```

Expected: **0 failures, 0 errors**, and a total around **450** (432 baseline + 3 `DueWindow` + 5 `QuotationTest` + 4 specification + 6 runner + 5 sweep + 3 scheduling = 458; anything in that region is fine, the exact number depends on how you split assertions). Record the real number — it goes into the handoff in Step 4.

- [ ] **Step 2: Append two challenge-log entries**

Append to `docs/superpowers/engineering-challenges.md` using the template at the bottom of that file (Problem → why it's hard → Solution → Lesson). Read the last existing entry first to match the numbering and heading style.

Entry A — **the tenant context must be bound before the transaction opens, and getting it wrong is silent.** Cover: a scheduled job has no JWT, so nothing establishes `TenantContext` the way the auth filter does; `TenantAwareTransactionManager.doBegin` reads the ThreadLocal to set the `app.current_tenant` GUC and Hibernate resolves a session's tenant once at session-open; therefore context set after the transaction opens binds to nothing, `doBegin` returns early with the GUC unset, and RLS-scoped tables return **zero rows instead of raising** — a job that silently does nothing looks exactly like a job with nothing to do. Solution: `TenantJobRunner` owns the ordering so no job re-derives it, and `TenantJobRunnerTest.eachTenantsBodySeesOnlyItsOwnRows` is verified to fail when the order is inverted. Include the second half: why `TransactionTemplate` rather than `@Transactional` — a `@Transactional` method called from the runner's own loop is a self-invocation, the proxy is bypassed, and the per-tenant transaction silently joins the caller's, collapsing the boundary. Lesson: when the failure mode is "returns nothing" rather than "throws", the test that proves the guard fires is worth more than the guard.

Entry B — **an IST calendar date compared against a `LocalDate` column.** Cover: `validUntil` is a `LocalDate` a user typed in IST; the server clock is `Clock.systemUTC()`; IST is UTC+5:30 so the IST day rolls over at 18:30 UTC the previous day. Comparing `validUntil` against a UTC-derived date would expire every tenant's quotations 5½ hours early, every day, and only on the UTC-evening side of the boundary — so it would look correct in a morning-run test and wrong in production. Solution: `DueWindow.todayDate(Instant)` beside the existing IST window arithmetic (one home for the zone, not two), strictly-before comparison so a quote is valid through its stated day, and `@Scheduled(zone = "Asia/Kolkata")` so the fire time is IST regardless of the server's timezone. Lesson: a date column entered in a local zone must be compared against a date computed in that same zone; "the server is UTC" is not neutrality, it is a different answer.

- [ ] **Step 3: Add the two new annotations**

In `docs/superpowers/annotations-reference.md`, add rows in the file's existing format (origin, purpose, meta-annotation composition):

- `@EnableScheduling` — `org.springframework.scheduling.annotation`. Registers `ScheduledAnnotationBeanPostProcessor`, which scans beans for `@Scheduled` and registers tasks with a `TaskScheduler`. Without it `@Scheduled` is inert and silently does nothing. Used on `platform/job/SchedulingConfig`.
- `@Scheduled` — `org.springframework.scheduling.annotation`. Marks a no-arg method for periodic invocation. `cron` accepts a property placeholder, and the literal `"-"` (`Scheduled.CRON_DISABLED`) skips registration entirely — which is how the test suite turns the job off. `zone` pins the cron's timezone independent of the server default; this project uses `Asia/Kolkata`.

- [ ] **Step 4: Update the handoff**

In `docs/superpowers/HANDOFF.md`:

1. Update the **Last updated** line at the top to today with a one-paragraph summary: auto-expiry is built; the codebase now has its first scheduled job and a reusable `TenantJobRunner` seam; the tenant-context-before-transaction ordering is structural rather than remembered.
2. In §0, update the baseline test count from **432** to the real number from Step 1.
3. In §8, mark backlog item **#2 (scheduled auto-expiry) as DONE**, the way item #1 is marked, with the merge commit. Update the "Suggested default" paragraph: with #1 and #2 both closed, **#3 (user invitations) is the largest genuinely-open product item** and the strongest remaining claim; #4 (cursor pagination) and `platform-web` are unchanged; PF19 stays blocked on the billing thread's design.
4. In §3, add a `quotation-auto-expiry` entry describing what landed, in the style of the existing slice entries.
5. Add to the "Before any second app instance" note: the nightly sweep has no distributed lock, so N instances each run it. It cannot double-write (the `@Version` optimistic lock means the loser's retry finds nothing to expire) but it does duplicate the work. This sits alongside the rate limiter's in-process store, which has the sharper version of the same problem.
6. Add a line to the deferred-Minor backlog: **`QuotationExpirySweep` issues one `findById` per candidate to read its version's `validUntil`.** Irrelevant at current volumes; if a tenant ever has thousands of lapsed quotes in one night, batch-load the versions instead.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/engineering-challenges.md \
        docs/superpowers/annotations-reference.md \
        docs/superpowers/HANDOFF.md
git commit -m "docs: record the quotation auto-expiry slice"
```

- [ ] **Step 6: Final green check**

Run: `./gradlew clean test` one last time, plus the count commands from Step 1. Expected: 0 failures, 0 errors. Do not claim the branch is done on anything less.

---

## Verification Checklist

Before calling the branch complete:

- [ ] `./gradlew clean test` — 0 failures, 0 errors, ~450+ tests
- [ ] The prove-it-can-fail step in Task 4 Step 6 was actually run, and the isolation test actually went red with the ordering inverted
- [ ] `./gradlew :test --tests 'com.easycrm.arch.*'` passes — all four ArchUnit guards
- [ ] No Flyway migration was added
- [ ] No commit message mentions Claude or AI; all commits author as `divyam`
- [ ] Both challenge-log entries are written
- [ ] The handoff's baseline test count matches reality
