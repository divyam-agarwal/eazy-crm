# Sales Hardening Implementation Plan (optimistic-lock 409 + quote-uniqueness backstop)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close two deferred codebase-wide gaps: map optimistic-lock failures to HTTP 409 (not 500), and add a structural `UNIQUE(tenant_id, enquiry_id)` backstop for one-quote-per-enquiry.

**Architecture:** Item 1 adds one `@ExceptionHandler(OptimisticLockingFailureException.class)` → 409 to `ApiExceptionHandler`, proven by a deterministic handler unit test plus a single-threaded stale-write repo test. Item 2 adds a Flyway migration + matching entity `@Table` constraint, proven by a repo-level constraint test. No new endpoints, DTOs, or behaviour changes to existing flows.

**Tech Stack:** Spring Boot 4.1, Java 25, Hibernate 7, PostgreSQL (RLS), JUnit 5 + Testcontainers + AssertJ.

## Global Constraints

- **Error mapping:** cross-tenant/missing → 404; `ConflictException` / `DataIntegrityViolationException` / **`OptimisticLockingFailureException`** → 409; `ValidationException` → 422. Catch Spring's **base** `org.springframework.dao.OptimisticLockingFailureException` (covers `org.springframework.orm.ObjectOptimisticLockingFailureException`).
- **Tenant isolation structural** — `@TenantId` + RLS; never hand-write `WHERE tenant_id`.
- **`ddl-auto: validate`** — migration column/type must match the entity; validate does NOT check unique constraints, so the entity `@UniqueConstraint` is documentary and the migration is the enforcement.
- **Money is never a double** (untouched here).
- **Commits:** author as `divyam <divyam.0444@gmail.com>`, plain `git commit`. Never mention Claude/AI, never add a `Co-Authored-By` trailer. One task per commit.
- **TDD:** failing test → run-to-confirm-fail → minimal code → run-to-pass → commit.
- **Build/test:** `cd backend && ./gradlew test` (Docker up for Testcontainers). If a gradle command hits a network/socket error in this harness, re-run with the Bash tool's `dangerouslyDisableSandbox: true`.

---

## File Structure

- **Modify:** `backend/src/main/java/com/easycrm/platform/error/ApiExceptionHandler.java` — add the optimistic-lock handler.
- **Modify:** `backend/src/test/java/com/easycrm/platform/error/ApiExceptionHandlerTest.java` — handler unit test.
- **Modify:** `backend/src/test/java/com/easycrm/sales/EnquiryRepositoryTest.java` — stale-write test (proves `@Version` is live).
- **Create:** `backend/src/main/resources/db/migration/V22__quotation_enquiry_unique.sql` — the unique constraint.
- **Modify:** `backend/src/main/java/com/easycrm/sales/Quotation.java` — matching `@Table` constraint.
- **Modify:** `backend/src/test/java/com/easycrm/sales/QuotationRepositoryTest.java` — constraint tests.
- **Modify (Task 3):** `docs/superpowers/engineering-challenges.md`, `docs/superpowers/HANDOFF.md`.

---

### Task 1: Optimistic-lock → 409 handler

**Files:**
- Modify: `backend/src/main/java/com/easycrm/platform/error/ApiExceptionHandler.java`
- Test: `backend/src/test/java/com/easycrm/platform/error/ApiExceptionHandlerTest.java`
- Test: `backend/src/test/java/com/easycrm/sales/EnquiryRepositoryTest.java`

**Interfaces:**
- Consumes: existing `ApiExceptionHandler.body(HttpStatus, String, String, Map)` private helper; `EnquiryRepository` (`save`, `saveAndFlush`, `findById`); `Enquiry.advanceTo(EnquiryStage)`; the `tx` (`TransactionTemplate`) and `active(phone)` helper already in `EnquiryRepositoryTest`.
- Produces: `ApiExceptionHandler.optimisticLock(OptimisticLockingFailureException)` → `ResponseEntity` with 409 / `error.code = "CONFLICT"`.

- [ ] **Step 1: Write the failing handler unit test**

In `ApiExceptionHandlerTest.java`, add these imports (below the existing ones):

```java
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import java.util.UUID;
```

Add this test method inside the class:

```java
    @Test
    @SuppressWarnings("unchecked")
    void optimisticLockMapsTo409() {
        ResponseEntity<Map<String, Object>> resp =
            handler.optimisticLock(new ObjectOptimisticLockingFailureException(Object.class, UUID.randomUUID()));

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        Map<String, Object> error = (Map<String, Object>) resp.getBody().get("error");
        assertEquals("CONFLICT", error.get("code"));
    }
```

- [ ] **Step 2: Run it to confirm it fails (does not compile)**

Run: `cd backend && ./gradlew test --tests "com.easycrm.platform.error.ApiExceptionHandlerTest"`
Expected: FAIL — compilation error, `handler.optimisticLock(...)` does not exist yet.

- [ ] **Step 3: Add the handler**

In `ApiExceptionHandler.java`, add the import:

```java
import org.springframework.dao.OptimisticLockingFailureException;
```

Add this handler method immediately after the existing `dataIntegrity` handler (after its closing brace, before the `validation` handler):

```java
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> optimisticLock(OptimisticLockingFailureException ex) {
        // A concurrent @Version write lost the race. Data integrity is intact (exactly one writer
        // wins); the loser gets 409 instead of a raw 500. Sibling of the DataIntegrityViolation
        // backstop above — ObjectOptimisticLockingFailureException does NOT extend
        // DataIntegrityViolationException, so it needs its own handler.
        return body(HttpStatus.CONFLICT, "CONFLICT",
            "the request could not be completed due to a concurrent update; please retry", null);
    }
```

- [ ] **Step 4: Run the handler unit test to confirm it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.platform.error.ApiExceptionHandlerTest"`
Expected: PASS (both `validationExceptionMapsTo422WithFields` and `optimisticLockMapsTo409`).

- [ ] **Step 5: Write the stale-write repo test (proves `@Version` is live)**

In `EnquiryRepositoryTest.java`, add these imports:

```java
import org.springframework.dao.OptimisticLockingFailureException;
```

(It already imports `assertThatThrownBy`, `TenantPrincipal`, `UUID`, `TransactionTemplate tx`, and has the `active(phone)` helper.)

Add this test method inside the class:

```java
    @Test
    void staleUpdateThrowsOptimisticLockingFailure() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantPrincipal(tenant, UUID.randomUUID(), "OWNER"));

        // Create at version 0.
        UUID id = tx.execute(s -> enquiries.save(active("9876500000")).getId());

        // A detached copy still at version 0.
        Enquiry stale = tx.execute(s -> enquiries.findById(id).orElseThrow());

        // Advance the DB row to version 1 in a separate transaction.
        tx.executeWithoutResult(s -> {
            Enquiry fresh = enquiries.findById(id).orElseThrow();
            fresh.advanceTo(EnquiryStage.CONTACTED);
            enquiries.saveAndFlush(fresh);
        });

        // Saving the stale (v0) copy over the now-v1 row loses the optimistic-lock race.
        assertThatThrownBy(() ->
            tx.executeWithoutResult(s -> {
                stale.advanceTo(EnquiryStage.QUALIFIED);
                enquiries.saveAndFlush(stale);
            }))
            .isInstanceOf(OptimisticLockingFailureException.class);
    }
```

- [ ] **Step 6: Run the repo test to confirm it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.sales.EnquiryRepositoryTest"`
Expected: PASS (3 tests: the 2 existing + `staleUpdateThrowsOptimisticLockingFailure`). This is a characterization test — it exercises the existing `@Version` on `BaseEntity`, confirming a real code path produces exactly the exception the new handler maps.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/easycrm/platform/error/ApiExceptionHandler.java \
        backend/src/test/java/com/easycrm/platform/error/ApiExceptionHandlerTest.java \
        backend/src/test/java/com/easycrm/sales/EnquiryRepositoryTest.java
git commit -m "feat(error): map optimistic-lock failures to 409 instead of 500"
```

---

### Task 2: Structural UNIQUE(tenant_id, enquiry_id) backstop

**Files:**
- Create: `backend/src/main/resources/db/migration/V22__quotation_enquiry_unique.sql`
- Modify: `backend/src/main/java/com/easycrm/sales/Quotation.java`
- Test: `backend/src/test/java/com/easycrm/sales/QuotationRepositoryTest.java`

**Interfaces:**
- Consumes: `QuotationRepository` (`save`, `saveAndFlush`); `new Quotation(UUID customerId, UUID enquiryId)`; the `asTenant(UUID)` helper and inline `new TransactionTemplate(txManager)` already in `QuotationRepositoryTest`.
- Produces: DB constraint `uq_quotation_tenant_enquiry` on `quotation(tenant_id, enquiry_id)`; a same-`(tenant, enquiry_id)` insert now throws `DataIntegrityViolationException` (→ 409 via the existing challenge #15 handler).

- [ ] **Step 1: Write the failing constraint tests**

In `QuotationRepositoryTest.java`, add these imports:

```java
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

Add these two test methods inside the class:

```java
    @Test
    void uniqueConstraintBlocksSecondQuotationForSameEnquiry() {
        UUID tenant = UUID.randomUUID();
        asTenant(tenant);
        UUID enquiryId = UUID.randomUUID();
        new TransactionTemplate(txManager).executeWithoutResult(s ->
            quotations.save(new Quotation(UUID.randomUUID(), enquiryId)));

        assertThatThrownBy(() ->
            new TransactionTemplate(txManager).executeWithoutResult(s ->
                quotations.saveAndFlush(new Quotation(UUID.randomUUID(), enquiryId))))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enquiryLessQuotationsCoexist() {
        UUID tenant = UUID.randomUUID();
        asTenant(tenant);
        assertThatCode(() -> {
            new TransactionTemplate(txManager).executeWithoutResult(s ->
                quotations.save(new Quotation(UUID.randomUUID(), null)));
            new TransactionTemplate(txManager).executeWithoutResult(s ->
                quotations.saveAndFlush(new Quotation(UUID.randomUUID(), null)));
        }).doesNotThrowAnyException();
    }
```

- [ ] **Step 2: Run to confirm the uniqueness test fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.sales.QuotationRepositoryTest"`
Expected: FAIL — `uniqueConstraintBlocksSecondQuotationForSameEnquiry` fails because, with no constraint yet, the second save succeeds (no exception thrown). `enquiryLessQuotationsCoexist` passes already.

- [ ] **Step 3: Add the migration**

Create `backend/src/main/resources/db/migration/V22__quotation_enquiry_unique.sql`:

```sql
-- One quotation per enquiry per tenant (structural backstop for the entity terminal guard).
-- Postgres treats NULLs as distinct, so enquiry-less quotations (enquiry_id IS NULL) coexist freely.
ALTER TABLE quotation
    ADD CONSTRAINT uq_quotation_tenant_enquiry UNIQUE (tenant_id, enquiry_id);
```

- [ ] **Step 4: Add the matching `@Table` constraint to the entity**

In `Quotation.java`, replace the existing `@Table(...)` annotation:

```java
@Table(name = "quotation",
       uniqueConstraints = @UniqueConstraint(name = "uq_quotation_tenant_no",
                                             columnNames = {"tenant_id", "quote_no"}))
```

with:

```java
@Table(name = "quotation",
       uniqueConstraints = {
           @UniqueConstraint(name = "uq_quotation_tenant_no",
                             columnNames = {"tenant_id", "quote_no"}),
           @UniqueConstraint(name = "uq_quotation_tenant_enquiry",
                             columnNames = {"tenant_id", "enquiry_id"})
       })
```

(`@UniqueConstraint` and `@Table` are already imported.)

- [ ] **Step 5: Run the constraint tests to confirm they pass**

Run: `cd backend && ./gradlew test --tests "com.easycrm.sales.QuotationRepositoryTest"`
Expected: PASS (5 tests: 3 existing + 2 new). Flyway applies V22 to the fresh Testcontainers DB before the entity validates.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V22__quotation_enquiry_unique.sql \
        backend/src/main/java/com/easycrm/sales/Quotation.java \
        backend/src/test/java/com/easycrm/sales/QuotationRepositoryTest.java
git commit -m "feat(sales): add UNIQUE(tenant_id, enquiry_id) backstop on quotation"
```

---

### Task 3: Full-suite verification + documentation

**Files:**
- Modify: `docs/superpowers/engineering-challenges.md`
- Modify: `docs/superpowers/HANDOFF.md`

- [ ] **Step 1: Run the whole suite from clean**

Run: `cd backend && ./gradlew clean test`
Expected: PASS — 162 prior + 3 new = **165 tests**, all green. Confirm the count from the JUnit XML (`build/test-results/test/*.xml`). In particular confirm no existing quotation test regressed against the new UNIQUE constraint (none should — no existing test inserts two quotations with the same non-null `enquiry_id` in one tenant).

- [ ] **Step 2: Update challenge #25 (the 500 gap is now closed)**

In `docs/superpowers/engineering-challenges.md`, find challenge #25's parenthetical about the race-loser surfacing as HTTP 500 (it currently ends "...deferred to a dedicated optimistic-lock→409 hardening slice, not this one."). Update it to state the gap is **now closed** by the global `OptimisticLockingFailureException`→409 handler added in this slice (reference the challenge-#26 entry if you add one in Step 3).

- [ ] **Step 3: Evaluate + (likely) add challenge #26**

Judge against the CLAUDE.md bar. This one plausibly clears it: the non-obvious insight is that `OptimisticLockingFailureException` and `DataIntegrityViolationException` are **sibling** `DataAccessException` subtrees — a naive reader assumes the existing "DB conflict → 409" backstop already covers optimistic-lock failures, but it doesn't, so a lost-update race silently 500s until a second, separate handler is added. If you judge it worth logging, append challenge #26 using the template at the bottom of the file (Problem → why hard → Solution → Lesson). If you judge it too thin, skip it and note in the commit that you evaluated and skipped. No new annotation is introduced, so `annotations-reference.md` needs no change.

- [ ] **Step 4: Update the handoff**

In `docs/superpowers/HANDOFF.md`: move the two items ("Optimistic-lock → 409 (codebase-wide)" and "Structural backstop for one-quote-per-enquiry") **out** of the §4 deferred list, marking them done on this slice; bump the §3 test count (162 → 165) and the "current state" / latest-slice lines; add the sales-hardening spec + plan to the §2 read-order list. Keep edits factual.

- [ ] **Step 5: Commit the docs**

```bash
git add docs/superpowers/
git commit -m "docs(sales): log optimistic-lock 409 hardening + update handoff"
```

---

## Self-Review

**Spec coverage:**
- §4.1 handler → Task 1 Steps 3. §4.2 handler unit test → Task 1 Steps 1–4; §4.2 repo stale-write test → Task 1 Steps 5–6.
- §5.1 migration → Task 2 Step 3. §5.2 entity `@Table` → Task 2 Step 4. §5.4 tests (same-enquiry blocked + NULLs coexist) → Task 2 Steps 1, 5.
- §5.3 (guard bypass surfaces as 409 via the existing challenge #15 handler) → covered by construction (no new mapping); the DataIntegrityViolation→409 handler already exists and is unit-tested elsewhere.
- §6 count 162 → 165 → Task 3 Step 1.
- §7 docs (update #25, evaluate #26, HANDOFF, no annotation change) → Task 3 Steps 2–4.

**Placeholder scan:** none — exact code, paths, commands, expected output in every step.

**Type consistency:** `handler.optimisticLock(OptimisticLockingFailureException)`, `ObjectOptimisticLockingFailureException(Class, Object)`, `Enquiry.advanceTo(EnquiryStage)`, `active(String)`, `TransactionTemplate tx`/`txManager`, `asTenant(UUID)`, `new Quotation(UUID, UUID)`, `DataIntegrityViolationException`, `OptimisticLockingFailureException` — all verified against current source. Constraint name `uq_quotation_tenant_enquiry` is identical in the migration (Task 2 Step 3) and the entity `@UniqueConstraint` (Task 2 Step 4).
