# Enquiry Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `Enquiry` lead-capture aggregate — the wedge's head — with a 5-stage guarded lifecycle, phone-normalized "one active enquiry per phone" dedupe, and REST create/get/list/edit/advance/lose.

**Architecture:** A single tenant-scoped JPA entity (`Enquiry extends TenantScopedEntity`) under `com.easycrm.sales`, mirroring the `Order`/`Quotation` aggregates. Transition guards live in the entity (like `Quotation.markSent`); the service orchestrates, normalizes the phone, and does the app-level dedupe pre-check backed by a Postgres partial unique index. List filtering uses a JPA `Specification` so any subset of the three optional filters composes correctly.

**Tech Stack:** Spring Boot 4.1, Java 25, Hibernate 7, Spring Data JPA (`JpaSpecificationExecutor`), PostgreSQL + RLS, Flyway, JUnit 5 + Testcontainers + MockMvc + jayway JsonPath.

## Global Constraints

- **Tenant isolation is structural** — `Enquiry extends TenantScopedEntity` (`@TenantId` + RLS). Never hand-write `WHERE tenant_id`. The list `Specification` adds only user filters; tenant scoping comes from the RLS session. ArchUnit enforces the base-class rule.
- **Money is never a `double`** — `expected_value` is `NUMERIC(18,2)` in Postgres, `BigDecimal` in Java, JSON **string** on the wire via the global `BigDecimalStringModule` (no per-field annotation).
- **`ddl-auto: validate`** — every migration column type/length must match the entity `@Column` mapping exactly (`VARCHAR(n)` ↔ `length = n`, `NUMERIC(18,2)` ↔ `precision = 18, scale = 2`).
- **Cross-tenant reads return 404** (get) / **empty** (list), never 403/200.
- **Illegal state transitions throw `ValidationException("stage", ...)` → 422** (the same pattern `QuotationService` uses for `status`). `ConflictException` → 409. `NotFoundException` → 404. All already wired in `ApiExceptionHandler`.
- **Commits:** author as `divyam <divyam.0444@gmail.com>`, plain `git commit`. Never mention Claude/AI, never add a `Co-Authored-By` trailer. One task per commit (a task may contain a TDD red→green pair; commit once at the end of the task unless a step says otherwise).
- **Build/test:** `cd backend && ./gradlew test` (Docker must be running for Testcontainers). Package under `com.easycrm.sales` (+ `.web`, `.web.dto`).
- **Baseline:** `main` at 132 passing tests. Branch `enquiry-slice` is already checked out with the spec committed.

**Spec:** `docs/superpowers/specs/2026-07-27-enquiry-design.md` (read it — this plan implements it verbatim).

---

### Task 1: Phone normalization utility

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/PhoneNormalizer.java`
- Test: `backend/src/test/java/com/easycrm/sales/PhoneNormalizerTest.java`

**Interfaces:**
- Consumes: `com.easycrm.platform.error.ValidationException(String field, String message)`.
- Produces: `PhoneNormalizer.normalize(String raw) -> String` (static) — strips non-digits, drops a leading `91` (12→10) or leading `0` (11→10), returns a 10-digit string; throws `ValidationException("contactPhone", ...)` if the result is not exactly 10 digits or input is null/blank.

- [ ] **Step 1: Write the failing test**

```java
package com.easycrm.sales;

import com.easycrm.platform.error.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhoneNormalizerTest {

    @Test
    void stripsFormattingAndCountryCodeToTenDigits() {
        assertThat(PhoneNormalizer.normalize("+91 98765 43210")).isEqualTo("9876543210");
        assertThat(PhoneNormalizer.normalize("098765 43210")).isEqualTo("9876543210");
        assertThat(PhoneNormalizer.normalize("(98765)-43210")).isEqualTo("9876543210");
        assertThat(PhoneNormalizer.normalize("9876543210")).isEqualTo("9876543210");
        assertThat(PhoneNormalizer.normalize("91-98765-43210")).isEqualTo("9876543210");
    }

    @Test
    void rejectsWrongLength() {
        assertThatThrownBy(() -> PhoneNormalizer.normalize("98765"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> PhoneNormalizer.normalize("12345678901")) // 11 digits, no leading 0
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> PhoneNormalizer.normalize(null))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> PhoneNormalizer.normalize("   "))
            .isInstanceOf(ValidationException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.PhoneNormalizerTest'`
Expected: FAIL — `PhoneNormalizer` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package com.easycrm.sales;

import com.easycrm.platform.error.ValidationException;

/**
 * Canonicalises an Indian phone number to its 10-digit national form so it can serve
 * as a stable dedupe key. Strips formatting, a +91 country code, and a leading trunk 0.
 */
public final class PhoneNormalizer {

    private PhoneNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException("contactPhone", "phone number is required");
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        if (digits.length() != 10) {
            throw new ValidationException("contactPhone",
                "must be a valid 10-digit Indian phone number");
        }
        return digits;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.PhoneNormalizerTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/PhoneNormalizer.java \
        backend/src/test/java/com/easycrm/sales/PhoneNormalizerTest.java
git commit -m "feat(enquiry): phone normalizer for dedupe key"
```

---

### Task 2: Stage/source enums + `Enquiry` entity with guarded transitions

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/EnquirySource.java`
- Create: `backend/src/main/java/com/easycrm/sales/EnquiryStage.java`
- Create: `backend/src/main/java/com/easycrm/sales/Enquiry.java`
- Test: `backend/src/test/java/com/easycrm/sales/EnquiryTest.java`

**Interfaces:**
- Consumes: `PhoneNormalizer.normalize` (Task 1) is NOT called here — the service passes an already-normalized phone into the entity. `com.easycrm.platform.persistence.TenantScopedEntity`, `com.easycrm.platform.error.ValidationException`.
- Produces:
  - `EnquirySource { INDIAMART, WHATSAPP, PHONE, REFERRAL, MANUAL, IMPORT }`.
  - `EnquiryStage { NEW, CONTACTED, QUALIFIED, CONVERTED, LOST }` with `boolean isActive()` (true for NEW/CONTACTED/QUALIFIED) and `boolean isTerminal()`.
  - `Enquiry` entity, constructor `Enquiry(UUID customerId, String contactName, String contactPhone, String normalizedPhone, String contactEmail, EnquirySource source, String requirementText, UUID assignedTo, BigDecimal expectedValue)` — sets `stage = NEW`.
  - Mutators: `updateHeader(UUID customerId, String contactName, String contactPhone, String normalizedPhone, String contactEmail, EnquirySource source, String requirementText, UUID assignedTo, BigDecimal expectedValue)`; `advanceTo(EnquiryStage target)`; `lose(String lostReason)`; `markConverted()`. All active-only where noted; all throw `ValidationException("stage", ...)` on an illegal move.
  - Getters: `getId, getCustomerId, getContactName, getContactPhone, getNormalizedPhone, getContactEmail, getSource, getRequirementText, getAssignedTo, getStage, getExpectedValue, getLostReason`.

- [ ] **Step 1: Write the failing test**

```java
package com.easycrm.sales;

import com.easycrm.platform.error.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnquiryTest {

    private Enquiry newEnquiry() {
        return new Enquiry(null, "Ravi", "9876543210", "9876543210", null,
            EnquirySource.INDIAMART, "10 bags cement", null, null);
    }

    @Test
    void startsInNewStage() {
        assertThat(newEnquiry().getStage()).isEqualTo(EnquiryStage.NEW);
    }

    @Test
    void advancesForwardIncludingSkip() {
        Enquiry e = newEnquiry();
        e.advanceTo(EnquiryStage.QUALIFIED); // skip CONTACTED — allowed
        assertThat(e.getStage()).isEqualTo(EnquiryStage.QUALIFIED);
    }

    @Test
    void rejectsBackwardAndSameStage() {
        Enquiry e = newEnquiry();
        e.advanceTo(EnquiryStage.CONTACTED);
        assertThatThrownBy(() -> e.advanceTo(EnquiryStage.NEW))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> e.advanceTo(EnquiryStage.CONTACTED))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void advanceCannotTargetTerminalStages() {
        assertThatThrownBy(() -> newEnquiry().advanceTo(EnquiryStage.LOST))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> newEnquiry().advanceTo(EnquiryStage.CONVERTED))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void loseRequiresReasonAndIsTerminal() {
        Enquiry e = newEnquiry();
        assertThatThrownBy(() -> e.lose("  "))
            .isInstanceOf(ValidationException.class);
        e.lose("bought elsewhere");
        assertThat(e.getStage()).isEqualTo(EnquiryStage.LOST);
        assertThat(e.getLostReason()).isEqualTo("bought elsewhere");
        // terminal: no further transitions
        assertThatThrownBy(() -> e.advanceTo(EnquiryStage.QUALIFIED))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> e.lose("again"))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void markConvertedIsTerminalAndActiveOnly() {
        Enquiry e = newEnquiry();
        e.markConverted();
        assertThat(e.getStage()).isEqualTo(EnquiryStage.CONVERTED);
        assertThatThrownBy(() -> e.markConverted())
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void updateHeaderRejectedOnTerminal() {
        Enquiry e = newEnquiry();
        e.lose("gone");
        assertThatThrownBy(() -> e.updateHeader(null, "X", "9999999999", "9999999999",
            null, EnquirySource.PHONE, null, null, null))
            .isInstanceOf(ValidationException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.EnquiryTest'`
Expected: FAIL — `EnquirySource`/`EnquiryStage`/`Enquiry` do not exist.

- [ ] **Step 3: Write minimal implementation**

`EnquirySource.java`:

```java
package com.easycrm.sales;

// Own copy of the six lead sources; keeps `sales` decoupled from `crm.CustomerSource`
// (each aggregate owns its enum, as QuotationStatus/OrderStatus/VersionStatus do).
public enum EnquirySource { INDIAMART, WHATSAPP, PHONE, REFERRAL, MANUAL, IMPORT }
```

`EnquiryStage.java`:

```java
package com.easycrm.sales;

public enum EnquiryStage {
    NEW, CONTACTED, QUALIFIED, CONVERTED, LOST;

    public boolean isTerminal() { return this == CONVERTED || this == LOST; }
    public boolean isActive()   { return !isTerminal(); }
}
```

`Enquiry.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "enquiry")
public class Enquiry extends TenantScopedEntity {

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "contact_name", nullable = false, length = 200)
    private String contactName;

    @Column(name = "contact_phone", nullable = false, length = 20)
    private String contactPhone;

    @Column(name = "normalized_phone", nullable = false, length = 10)
    private String normalizedPhone;

    @Column(name = "contact_email", length = 254)
    private String contactEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EnquirySource source;

    @Column(name = "requirement_text", length = 2000)
    private String requirementText;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EnquiryStage stage;

    @Column(name = "expected_value", precision = 18, scale = 2)
    private BigDecimal expectedValue;

    @Column(name = "lost_reason", length = 500)
    private String lostReason;

    protected Enquiry() {}

    public Enquiry(UUID customerId, String contactName, String contactPhone, String normalizedPhone,
                   String contactEmail, EnquirySource source, String requirementText,
                   UUID assignedTo, BigDecimal expectedValue) {
        this.customerId = customerId;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.normalizedPhone = normalizedPhone;
        this.contactEmail = contactEmail;
        this.source = source;
        this.requirementText = requirementText;
        this.assignedTo = assignedTo;
        this.expectedValue = expectedValue;
        this.stage = EnquiryStage.NEW;
    }

    /** Edit header fields. Allowed only while the enquiry is active. */
    public void updateHeader(UUID customerId, String contactName, String contactPhone,
                             String normalizedPhone, String contactEmail, EnquirySource source,
                             String requirementText, UUID assignedTo, BigDecimal expectedValue) {
        requireActive("edited");
        this.customerId = customerId;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.normalizedPhone = normalizedPhone;
        this.contactEmail = contactEmail;
        this.source = source;
        this.requirementText = requirementText;
        this.assignedTo = assignedTo;
        this.expectedValue = expectedValue;
    }

    /** Advance to a later active stage (NEW < CONTACTED < QUALIFIED). Skips allowed; no going back. */
    public void advanceTo(EnquiryStage target) {
        requireActive("advanced");
        if (!target.isActive() || target.ordinal() <= this.stage.ordinal()) {
            throw new ValidationException("stage",
                "can only advance to a later active stage");
        }
        this.stage = target;
    }

    public void lose(String lostReason) {
        requireActive("lost");
        if (lostReason == null || lostReason.isBlank()) {
            throw new ValidationException("lostReason", "a reason is required to mark an enquiry lost");
        }
        this.stage = EnquiryStage.LOST;
        this.lostReason = lostReason;
    }

    /** Reserved for the later enquiry->quotation conversion slice; no controller reaches this yet. */
    public void markConverted() {
        requireActive("converted");
        this.stage = EnquiryStage.CONVERTED;
    }

    private void requireActive(String verb) {
        if (stage.isTerminal()) {
            throw new ValidationException("stage",
                "a " + stage.name().toLowerCase() + " enquiry cannot be " + verb);
        }
    }

    public UUID getCustomerId() { return customerId; }
    public String getContactName() { return contactName; }
    public String getContactPhone() { return contactPhone; }
    public String getNormalizedPhone() { return normalizedPhone; }
    public String getContactEmail() { return contactEmail; }
    public EnquirySource getSource() { return source; }
    public String getRequirementText() { return requirementText; }
    public UUID getAssignedTo() { return assignedTo; }
    public EnquiryStage getStage() { return stage; }
    public BigDecimal getExpectedValue() { return expectedValue; }
    public String getLostReason() { return lostReason; }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.EnquiryTest'`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/EnquirySource.java \
        backend/src/main/java/com/easycrm/sales/EnquiryStage.java \
        backend/src/main/java/com/easycrm/sales/Enquiry.java \
        backend/src/test/java/com/easycrm/sales/EnquiryTest.java
git commit -m "feat(enquiry): Enquiry entity with guarded stage transitions"
```

---

### Task 3: Migration + RLS + repository + partial-index dedupe test

**Files:**
- Create: `backend/src/main/resources/db/migration/V20__enquiry.sql`
- Create: `backend/src/main/resources/db/migration/V21__rls_enquiry.sql`
- Create: `backend/src/main/java/com/easycrm/sales/EnquiryRepository.java`
- Test: `backend/src/test/java/com/easycrm/sales/EnquiryRepositoryTest.java`

**Interfaces:**
- Consumes: `Enquiry`, `EnquiryStage`, `EnquirySource` (Task 2); `com.easycrm.support.IntegrationTest`, `com.easycrm.platform.tenancy.TenantContext`.
- Produces: `EnquiryRepository extends JpaRepository<Enquiry, UUID>, JpaSpecificationExecutor<Enquiry>` with `Optional<Enquiry> findByNormalizedPhoneAndStageNot(String normalizedPhone, EnquiryStage stage)` — used by the service pre-check to find an existing *active* (non-... see note) enquiry. **Note:** "active" spans two terminal stages, so the pre-check actually needs "not CONVERTED and not LOST." Provide instead: `List<Enquiry> findByNormalizedPhone(String normalizedPhone)` and let the service filter to active, OR a derived query `findByNormalizedPhoneAndStageIn(String, Collection<EnquiryStage>)`. This task ships `findByNormalizedPhone` (simplest, RLS-scoped); the service (Task 4) filters `.stream().filter(e -> e.getStage().isActive())`.

**Migration notes:** `enquiry` is not reserved, so table name = class name. Column types/lengths must match the Task 2 `@Column` mappings exactly (`validate` mode). The partial unique index encodes the "one active enquiry per phone" invariant.

- [ ] **Step 1: Write the migrations**

`V20__enquiry.sql`:

```sql
CREATE TABLE enquiry (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL,
    customer_id       UUID,
    contact_name      VARCHAR(200) NOT NULL,
    contact_phone     VARCHAR(20) NOT NULL,
    normalized_phone  VARCHAR(10) NOT NULL,
    contact_email     VARCHAR(254),
    source            VARCHAR(16) NOT NULL,
    requirement_text  VARCHAR(2000),
    assigned_to       UUID,
    stage             VARCHAR(16) NOT NULL,
    expected_value    NUMERIC(18,2),
    lost_reason       VARCHAR(500),
    created_at        TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ,
    version           BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_enquiry_tenant ON enquiry (tenant_id, id);

-- One active (non-terminal) enquiry per phone per tenant. Terminal enquiries
-- (CONVERTED/LOST) drop out of the predicate, freeing the phone for a fresh lead.
CREATE UNIQUE INDEX uq_enquiry_tenant_active_phone
    ON enquiry (tenant_id, normalized_phone)
    WHERE stage NOT IN ('CONVERTED', 'LOST');
```

`V21__rls_enquiry.sql`:

```sql
ALTER TABLE enquiry ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON enquiry
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
```

- [ ] **Step 2: Write the repository**

```java
package com.easycrm.sales;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface EnquiryRepository
        extends JpaRepository<Enquiry, UUID>, JpaSpecificationExecutor<Enquiry> {

    // RLS-scoped derived query: must run inside a transaction so the tenant GUC is set,
    // otherwise RLS returns zero rows (see engineering-challenges #8).
    @Transactional(readOnly = true)
    List<Enquiry> findByNormalizedPhone(String normalizedPhone);
}
```

- [ ] **Step 3: Write the failing repository test**

```java
package com.easycrm.sales;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class EnquiryRepositoryTest extends IntegrationTest {

    @Autowired EnquiryRepository enquiries;
    @Autowired TransactionTemplate tx;

    @AfterEach void clear() { TenantContext.clear(); }

    private Enquiry active(String phone) {
        return new Enquiry(null, "Ravi", phone, phone, null,
            EnquirySource.PHONE, null, null, null);
    }

    @Test
    void partialIndexBlocksSecondActiveEnquiryForSamePhone() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant, UUID.randomUUID(), "OWNER");
        tx.executeWithoutResult(s -> enquiries.save(active("9876543210")));

        assertThatThrownBy(() ->
            tx.executeWithoutResult(s -> enquiries.saveAndFlush(active("9876543210"))))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void terminalEnquiryFreesThePhone() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant, UUID.randomUUID(), "OWNER");
        tx.executeWithoutResult(s -> {
            Enquiry first = active("9998887776");
            first.lose("gone");           // -> LOST, leaves the partial index
            enquiries.save(first);
        });
        assertThatCode(() ->
            tx.executeWithoutResult(s -> enquiries.saveAndFlush(active("9998887776"))))
            .doesNotThrowAnyException();

        // CONVERTED also frees the phone
        UUID tenant2 = UUID.randomUUID();
        TenantContext.set(tenant2, UUID.randomUUID(), "OWNER");
        tx.executeWithoutResult(s -> {
            Enquiry c = active("7776665554");
            c.markConverted();
            enquiries.save(c);
        });
        assertThatCode(() ->
            tx.executeWithoutResult(s -> enquiries.saveAndFlush(active("7776665554"))))
            .doesNotThrowAnyException();
    }
}
```

> **Confirm `TenantContext.set(...)` signature before running** — check `com.easycrm.platform.tenancy.TenantContext` and an existing repository test (e.g. `OrderRepositoryTest`) and match the exact `set` arguments (tenant id, user id, role) and the `TransactionTemplate` bean wiring used there. Adjust the two lines above if the house scaffolding differs (some tests use `asTenant(...)` helpers instead).

- [ ] **Step 4: Run tests to verify they fail, then pass**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.EnquiryRepositoryTest'`
Expected FIRST (before migrations existed / if repo missing): FAIL. With V20/V21 + repository in place: PASS (2 tests). If it fails on `ddl-auto: validate` mismatch, reconcile the migration column type/length with the Task 2 `@Column` mapping.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V20__enquiry.sql \
        backend/src/main/resources/db/migration/V21__rls_enquiry.sql \
        backend/src/main/java/com/easycrm/sales/EnquiryRepository.java \
        backend/src/test/java/com/easycrm/sales/EnquiryRepositoryTest.java
git commit -m "feat(enquiry): table, RLS, and partial-index dedupe repository"
```

---

### Task 4: Create endpoint — normalization + dedupe pre-check

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/EnquiryService.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/EnquiryCreateRequest.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/EnquiryResponse.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/EnquiryController.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/EnquiryCreateTest.java`

**Interfaces:**
- Consumes: `EnquiryRepository.findByNormalizedPhone` + `save` (Task 3); `PhoneNormalizer.normalize` (Task 1); `Enquiry` ctor (Task 2); `ConflictException`, `ValidationException`, `PageResponse`, `NotFoundException`.
- Produces:
  - `EnquiryService.create(EnquiryCreateRequest req) -> EnquiryResponse` (`@Transactional`).
  - `EnquiryCreateRequest(UUID customerId, @NotBlank String contactName, @NotBlank String contactPhone, String contactEmail, @NotNull EnquirySource source, String requirementText, UUID assignedTo, BigDecimal expectedValue)`.
  - `EnquiryResponse` record + `EnquiryResponse.of(Enquiry)`.
  - `EnquiryController` — `POST /api/v1/enquiries` → 201.

- [ ] **Step 1: Write the failing test**

```java
package com.easycrm.sales.web;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EnquiryCreateTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void createsWithNormalizedPhoneAndMoneyString() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        mvc.perform(post("/api/v1/enquiries").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"contactName":"Ravi","contactPhone":"+91 98765 43210",
                     "source":"INDIAMART","requirementText":"10 bags",
                     "expectedValue":"50000.00"}"""))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.stage").value("NEW"))
            .andExpect(jsonPath("$.normalizedPhone").value("9876543210"))
            .andExpect(jsonPath("$.expectedValue").value("50000.00")); // JSON string, not number
    }

    @Test
    void rejectsInvalidPhoneWith422() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        mvc.perform(post("/api/v1/enquiries").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"contactName":"Ravi","contactPhone":"12345","source":"PHONE"}"""))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.fields.contactPhone").exists());
    }

    @Test
    void secondActiveEnquiryForSamePhoneConflicts() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String body = """
            {"contactName":"Ravi","contactPhone":"098765 43210","source":"WHATSAPP"}""";
        mvc.perform(post("/api/v1/enquiries").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/enquiries").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.EnquiryCreateTest'`
Expected: FAIL — service/controller/DTOs don't exist (404/compilation).

- [ ] **Step 3: Write minimal implementation**

`EnquiryCreateRequest.java`:

```java
package com.easycrm.sales.web.dto;

import com.easycrm.sales.EnquirySource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record EnquiryCreateRequest(
    UUID customerId,
    @NotBlank String contactName,
    @NotBlank String contactPhone,
    String contactEmail,
    @NotNull EnquirySource source,
    String requirementText,
    UUID assignedTo,
    BigDecimal expectedValue) {}
```

`EnquiryResponse.java`:

```java
package com.easycrm.sales.web.dto;

import com.easycrm.sales.Enquiry;
import com.easycrm.sales.EnquirySource;
import com.easycrm.sales.EnquiryStage;

import java.math.BigDecimal;
import java.util.UUID;

public record EnquiryResponse(
    UUID id, UUID customerId, String contactName, String contactPhone, String normalizedPhone,
    String contactEmail, EnquirySource source, String requirementText, UUID assignedTo,
    EnquiryStage stage, BigDecimal expectedValue, String lostReason) {

    public static EnquiryResponse of(Enquiry e) {
        return new EnquiryResponse(e.getId(), e.getCustomerId(), e.getContactName(),
            e.getContactPhone(), e.getNormalizedPhone(), e.getContactEmail(), e.getSource(),
            e.getRequirementText(), e.getAssignedTo(), e.getStage(), e.getExpectedValue(),
            e.getLostReason());
    }
}
```

`EnquiryService.java` (create only for now; get/list/update/transitions added in Tasks 5-6):

```java
package com.easycrm.sales;

import com.easycrm.platform.error.ConflictException;
import com.easycrm.sales.web.dto.EnquiryCreateRequest;
import com.easycrm.sales.web.dto.EnquiryResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnquiryService {

    private final EnquiryRepository enquiries;

    public EnquiryService(EnquiryRepository enquiries) { this.enquiries = enquiries; }

    @Transactional
    public EnquiryResponse create(EnquiryCreateRequest req) {
        String normalized = PhoneNormalizer.normalize(req.contactPhone());
        requireNoActiveDuplicate(normalized);
        Enquiry saved = enquiries.save(new Enquiry(
            req.customerId(), req.contactName(), req.contactPhone(), normalized,
            req.contactEmail(), req.source(), req.requirementText(),
            req.assignedTo(), req.expectedValue()));
        return EnquiryResponse.of(saved);
    }

    /**
     * App-level pre-check for the "one active enquiry per phone" invariant. This is
     * check-then-act; the partial unique index + the global DataIntegrityViolation->409
     * handler (challenge #15) is the concurrency backstop.
     */
    private void requireNoActiveDuplicate(String normalizedPhone) {
        enquiries.findByNormalizedPhone(normalizedPhone).stream()
            .filter(e -> e.getStage().isActive())
            .findAny()
            .ifPresent(e -> {
                throw new ConflictException(
                    "an active enquiry already exists for this phone (id " + e.getId() + ")");
            });
    }
}
```

`EnquiryController.java` (create only for now):

```java
package com.easycrm.sales.web;

import com.easycrm.sales.EnquiryService;
import com.easycrm.sales.web.dto.EnquiryCreateRequest;
import com.easycrm.sales.web.dto.EnquiryResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/enquiries")
public class EnquiryController {

    private final EnquiryService service;

    public EnquiryController(EnquiryService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<EnquiryResponse> create(@Valid @RequestBody EnquiryCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.EnquiryCreateTest'`
Expected: PASS (3 tests). If `$.expectedValue` comes back as a number, the global `BigDecimalStringModule` isn't applying — it should already be registered from P1b; do not add a per-field annotation.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/EnquiryService.java \
        backend/src/main/java/com/easycrm/sales/web/dto/EnquiryCreateRequest.java \
        backend/src/main/java/com/easycrm/sales/web/dto/EnquiryResponse.java \
        backend/src/main/java/com/easycrm/sales/web/EnquiryController.java \
        backend/src/test/java/com/easycrm/sales/web/EnquiryCreateTest.java
git commit -m "feat(enquiry): create endpoint with phone normalization and dedupe"
```

---

### Task 5: Get + filtered list (Specification) + cross-tenant isolation

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/EnquirySpecifications.java`
- Modify: `backend/src/main/java/com/easycrm/sales/EnquiryService.java` (add `get`, `list`)
- Modify: `backend/src/main/java/com/easycrm/sales/web/EnquiryController.java` (add GET by id, GET list)
- Test: `backend/src/test/java/com/easycrm/sales/web/EnquiryListTest.java`

**Interfaces:**
- Consumes: `EnquiryRepository.findById`, `findAll(Specification, Pageable)` (from `JpaSpecificationExecutor`, Task 3); `PageResponse.of`; `NotFoundException`.
- Produces:
  - `EnquirySpecifications.filter(EnquiryStage stage, UUID assignedTo, EnquirySource source) -> Specification<Enquiry>` — AND-composes only the non-null filters.
  - `EnquiryService.get(UUID id) -> EnquiryResponse` (`@Transactional(readOnly = true)`).
  - `EnquiryService.list(EnquiryStage stage, UUID assignedTo, EnquirySource source, Pageable pageable) -> PageResponse<EnquiryResponse>` (`@Transactional(readOnly = true)`).
  - `GET /api/v1/enquiries/{id}` and `GET /api/v1/enquiries?stage=&assignedTo=&source=`.

- [ ] **Step 1: Write the failing test**

```java
package com.easycrm.sales.web;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EnquiryListTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String create(String auth, String name, String phone, String source, String assignedTo)
            throws Exception {
        String assignedJson = assignedTo == null ? "" : ",\"assignedTo\":\"" + assignedTo + "\"";
        return JsonPath.read(mvc.perform(post("/api/v1/enquiries").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(
                    "{\"contactName\":\"%s\",\"contactPhone\":\"%s\",\"source\":\"%s\"%s}"
                        .formatted(name, phone, source, assignedJson)))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void getByIdReturnsEnquiry() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = create(auth, "Ravi", "9876543210", "PHONE", null);
        mvc.perform(get("/api/v1/enquiries/" + id).header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void twoFiltersCombineCorrectly() throws Exception {
        // Regression guard: order-list dropped one filter when two were supplied.
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String userA = UUID.randomUUID().toString();
        String userB = UUID.randomUUID().toString();
        create(auth, "A", "9000000001", "PHONE", userA);      // source=PHONE, assignee=A
        create(auth, "B", "9000000002", "WHATSAPP", userA);   // source=WHATSAPP, assignee=A
        create(auth, "C", "9000000003", "PHONE", userB);      // source=PHONE, assignee=B

        // source=PHONE AND assignedTo=A -> only the first
        mvc.perform(get("/api/v1/enquiries").header("Authorization", auth)
                .param("source", "PHONE").param("assignedTo", userA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].contactName").value("A"));
    }

    @Test
    void crossTenantGetReturns404AndListIsEmpty() throws Exception {
        String authA = "Bearer " + tokens.provisionOwner("27").token();
        String id = create(authA, "Ravi", "9876543210", "PHONE", null);

        String authB = "Bearer " + tokens.provisionOwner("29").token();
        mvc.perform(get("/api/v1/enquiries/" + id).header("Authorization", authB))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/enquiries").header("Authorization", authB))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.EnquiryListTest'`
Expected: FAIL — get/list endpoints and `EnquirySpecifications` don't exist.

- [ ] **Step 3: Write minimal implementation**

`EnquirySpecifications.java`:

```java
package com.easycrm.sales;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class EnquirySpecifications {

    private EnquirySpecifications() {}

    /** AND-composes whichever filters are non-null. Tenant scoping comes from RLS, not here. */
    public static Specification<Enquiry> filter(EnquiryStage stage, UUID assignedTo,
                                                EnquirySource source) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (stage != null)      ps.add(cb.equal(root.get("stage"), stage));
            if (assignedTo != null) ps.add(cb.equal(root.get("assignedTo"), assignedTo));
            if (source != null)     ps.add(cb.equal(root.get("source"), source));
            return cb.and(ps.toArray(new Predicate[0])); // empty -> always-true conjunction
        };
    }
}
```

Add to `EnquiryService` (imports: `NotFoundException`, `PageResponse`, `Pageable`, `EnquiryStage`, `EnquirySource`, `UUID`):

```java
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public EnquiryResponse get(java.util.UUID id) {
        return EnquiryResponse.of(enquiries.findById(id)
            .orElseThrow(() -> new com.easycrm.platform.error.NotFoundException("enquiry not found")));
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public com.easycrm.platform.web.PageResponse<EnquiryResponse> list(
            EnquiryStage stage, java.util.UUID assignedTo, EnquirySource source,
            org.springframework.data.domain.Pageable pageable) {
        return com.easycrm.platform.web.PageResponse.of(
            enquiries.findAll(EnquirySpecifications.filter(stage, assignedTo, source), pageable)
                .map(EnquiryResponse::of));
    }
```

> Prefer top-of-file imports over fully-qualified names to match house style; the FQNs above are only to make the diff unambiguous. Clean them up when adding the methods.

Add to `EnquiryController`:

```java
    @org.springframework.web.bind.annotation.GetMapping("/{id}")
    public EnquiryResponse get(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        return service.get(id);
    }

    @org.springframework.web.bind.annotation.GetMapping
    public com.easycrm.platform.web.PageResponse<EnquiryResponse> list(
            @org.springframework.web.bind.annotation.RequestParam(required = false)
                com.easycrm.sales.EnquiryStage stage,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
                java.util.UUID assignedTo,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
                com.easycrm.sales.EnquirySource source,
            org.springframework.data.domain.Pageable pageable) {
        return service.list(stage, assignedTo, source, pageable);
    }
```

> Same note: convert to clean imports matching `OrderController`/`CustomerController` style.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.EnquiryListTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/EnquirySpecifications.java \
        backend/src/main/java/com/easycrm/sales/EnquiryService.java \
        backend/src/main/java/com/easycrm/sales/web/EnquiryController.java \
        backend/src/test/java/com/easycrm/sales/web/EnquiryListTest.java
git commit -m "feat(enquiry): get + filtered list via Specification"
```

---

### Task 6: Edit (PATCH, active-only + re-dedupe) + advance + lose

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/EnquiryUpdateRequest.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/AdvanceRequest.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/LoseRequest.java`
- Modify: `backend/src/main/java/com/easycrm/sales/EnquiryService.java` (add `update`, `advance`, `lose`)
- Modify: `backend/src/main/java/com/easycrm/sales/web/EnquiryController.java` (add PATCH, advance, lose)
- Test: `backend/src/test/java/com/easycrm/sales/web/EnquiryStateMachineTest.java`

**Interfaces:**
- Consumes: `Enquiry.updateHeader/advanceTo/lose` (Task 2); `EnquiryRepository.findByNormalizedPhone/findById`; `PhoneNormalizer.normalize`; `ConflictException`, `NotFoundException`, `ValidationException`.
- Produces:
  - `EnquiryUpdateRequest(UUID customerId, @NotBlank String contactName, @NotBlank String contactPhone, String contactEmail, @NotNull EnquirySource source, String requirementText, UUID assignedTo, BigDecimal expectedValue)`.
  - `AdvanceRequest(@NotNull EnquiryStage stage)`.
  - `LoseRequest(@NotBlank String lostReason)`.
  - `EnquiryService.update(UUID id, EnquiryUpdateRequest req)`, `advance(UUID id, EnquiryStage target)`, `lose(UUID id, String reason)` — all `@Transactional`, all return `EnquiryResponse`.
  - `PATCH /enquiries/{id}`, `POST /enquiries/{id}/advance`, `POST /enquiries/{id}/lose`.

- [ ] **Step 1: Write the failing test**

```java
package com.easycrm.sales.web;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EnquiryStateMachineTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String create(String auth, String phone) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/v1/enquiries").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(
                    "{\"contactName\":\"Ravi\",\"contactPhone\":\"%s\",\"source\":\"PHONE\"}"
                        .formatted(phone)))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void advanceSkipThenLoseAndTerminalGuards() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = create(auth, "9876543210");

        mvc.perform(post("/api/v1/enquiries/" + id + "/advance").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"stage\":\"QUALIFIED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stage").value("QUALIFIED"));

        // backward -> 422
        mvc.perform(post("/api/v1/enquiries/" + id + "/advance").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"stage\":\"CONTACTED\"}"))
            .andExpect(status().isUnprocessableEntity());

        // lose -> terminal
        mvc.perform(post("/api/v1/enquiries/" + id + "/lose").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"lostReason\":\"gone\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stage").value("LOST"))
            .andExpect(jsonPath("$.lostReason").value("gone"));

        // advancing a terminal enquiry -> 422
        mvc.perform(post("/api/v1/enquiries/" + id + "/advance").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"stage\":\"QUALIFIED\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void loseWithBlankReasonIs400() throws Exception {
        // @NotBlank on the request body -> MethodArgumentNotValid -> 400 (bean validation
        // runs before the service). This is the bean-validation guard; the entity guard
        // (422) is the defence-in-depth backstop covered in EnquiryTest.
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = create(auth, "9876543211");
        mvc.perform(post("/api/v1/enquiries/" + id + "/lose").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"lostReason\":\"   \"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void editUpdatesFieldsAndBlocksOnTerminal() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = create(auth, "9876543212");

        mvc.perform(patch("/api/v1/enquiries/" + id).header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"contactName":"Ravi Kumar","contactPhone":"9876543212",
                     "source":"REFERRAL","expectedValue":"12000.00"}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contactName").value("Ravi Kumar"))
            .andExpect(jsonPath("$.source").value("REFERRAL"))
            .andExpect(jsonPath("$.expectedValue").value("12000.00"));

        mvc.perform(post("/api/v1/enquiries/" + id + "/lose").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"lostReason\":\"gone\"}"))
            .andExpect(status().isOk());

        // editing a terminal enquiry -> 422
        mvc.perform(patch("/api/v1/enquiries/" + id).header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"contactName":"Nope","contactPhone":"9876543212","source":"PHONE"}"""))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void editingPhoneIntoAnotherActiveEnquiryConflicts() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        create(auth, "9111111111");            // occupies phone A
        String id2 = create(auth, "9222222222"); // to be edited onto A
        mvc.perform(patch("/api/v1/enquiries/" + id2).header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"contactName":"Ravi","contactPhone":"9111111111","source":"PHONE"}"""))
            .andExpect(status().isConflict());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.EnquiryStateMachineTest'`
Expected: FAIL — update/advance/lose endpoints and DTOs don't exist.

- [ ] **Step 3: Write minimal implementation**

`EnquiryUpdateRequest.java` (identical shape to create, minus nothing — kept separate so the two can diverge):

```java
package com.easycrm.sales.web.dto;

import com.easycrm.sales.EnquirySource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record EnquiryUpdateRequest(
    UUID customerId,
    @NotBlank String contactName,
    @NotBlank String contactPhone,
    String contactEmail,
    @NotNull EnquirySource source,
    String requirementText,
    UUID assignedTo,
    BigDecimal expectedValue) {}
```

`AdvanceRequest.java`:

```java
package com.easycrm.sales.web.dto;

import com.easycrm.sales.EnquiryStage;
import jakarta.validation.constraints.NotNull;

public record AdvanceRequest(@NotNull EnquiryStage stage) {}
```

`LoseRequest.java`:

```java
package com.easycrm.sales.web.dto;

import jakarta.validation.constraints.NotBlank;

public record LoseRequest(@NotBlank String lostReason) {}
```

Add to `EnquiryService` (reuse the existing private `requireNoActiveDuplicate`, but it must ignore the enquiry being edited — change it to take the current id, or add an overload). Replace `requireNoActiveDuplicate(String)` usage with an id-aware version:

```java
    @Transactional
    public EnquiryResponse update(java.util.UUID id, EnquiryUpdateRequest req) {
        Enquiry e = find(id);
        String normalized = PhoneNormalizer.normalize(req.contactPhone());
        requireNoActiveDuplicateExcept(normalized, id);
        e.updateHeader(req.customerId(), req.contactName(), req.contactPhone(), normalized,
            req.contactEmail(), req.source(), req.requirementText(),
            req.assignedTo(), req.expectedValue());
        return EnquiryResponse.of(e);
    }

    @Transactional
    public EnquiryResponse advance(java.util.UUID id, EnquiryStage target) {
        Enquiry e = find(id);
        e.advanceTo(target);
        return EnquiryResponse.of(e);
    }

    @Transactional
    public EnquiryResponse lose(java.util.UUID id, String reason) {
        Enquiry e = find(id);
        e.lose(reason);
        return EnquiryResponse.of(e);
    }

    private Enquiry find(java.util.UUID id) {
        return enquiries.findById(id)
            .orElseThrow(() -> new com.easycrm.platform.error.NotFoundException("enquiry not found"));
    }

    private void requireNoActiveDuplicateExcept(String normalizedPhone, java.util.UUID selfId) {
        enquiries.findByNormalizedPhone(normalizedPhone).stream()
            .filter(e -> e.getStage().isActive())
            .filter(e -> !e.getId().equals(selfId))
            .findAny()
            .ifPresent(e -> {
                throw new com.easycrm.platform.error.ConflictException(
                    "an active enquiry already exists for this phone (id " + e.getId() + ")");
            });
    }
```

Then refactor `create` to call `requireNoActiveDuplicateExcept(normalized, null)` and delete the old `requireNoActiveDuplicate` (with the `null` self-id, the `!e.getId().equals(null)` filter keeps every match — a `null` never equals a UUID). Convert the FQNs to top-of-file imports to match house style.

Add to `EnquiryController` (clean imports):

```java
    @PatchMapping("/{id}")
    public EnquiryResponse update(@PathVariable UUID id,
                                  @Valid @RequestBody EnquiryUpdateRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/advance")
    public EnquiryResponse advance(@PathVariable UUID id,
                                   @Valid @RequestBody AdvanceRequest req) {
        return service.advance(id, req.stage());
    }

    @PostMapping("/{id}/lose")
    public EnquiryResponse lose(@PathVariable UUID id,
                                @Valid @RequestBody LoseRequest req) {
        return service.lose(id, req.lostReason());
    }
```

- [ ] **Step 4: Run test to verify it passes, then the full suite**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.EnquiryStateMachineTest'`
Expected: PASS (4 tests).
Then: `cd backend && ./gradlew test`
Expected: full suite green (132 baseline + the new enquiry tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/web/dto/EnquiryUpdateRequest.java \
        backend/src/main/java/com/easycrm/sales/web/dto/AdvanceRequest.java \
        backend/src/main/java/com/easycrm/sales/web/dto/LoseRequest.java \
        backend/src/main/java/com/easycrm/sales/EnquiryService.java \
        backend/src/main/java/com/easycrm/sales/web/EnquiryController.java \
        backend/src/test/java/com/easycrm/sales/web/EnquiryStateMachineTest.java
git commit -m "feat(enquiry): edit, advance, and lose transitions"
```

---

### Task 7: Documentation — challenge #23, annotations, HANDOFF, ledger

**Files:**
- Modify: `docs/superpowers/engineering-challenges.md` (append #23)
- Modify: `docs/superpowers/annotations-reference.md` (verify; add row only if a genuinely new annotation appeared — none expected)
- Modify: `docs/superpowers/HANDOFF.md` (mark enquiry slice done, update test count, adjust deferred list)
- Modify: `.superpowers/sdd/progress.md` (record the slice, if the ledger is in use)

**No test.** This is the CLAUDE.md-mandated documentation pass. Do it in the same branch before finishing.

- [ ] **Step 1: Append engineering-challenge #23**

Use the template at the bottom of `engineering-challenges.md`. Content:

- **Problem:** "dedupe on normalized phone within tenant" — but a returning customer's number legitimately re-enquires, so a plain `UNIQUE(tenant_id, normalized_phone)` is wrong (it permanently blocks repeat business), while a purely app-level check-then-act pre-check races under concurrency.
- **Why it's hard:** the invariant is *state-scoped* ("one **active** enquiry per phone") — it must hold across concurrent creates yet must not fire once the prior enquiry is terminal. A total unique index can't express "only while active."
- **Solution:** a Postgres **partial unique index** `... WHERE stage NOT IN ('CONVERTED','LOST')` encodes exactly the active-only invariant structurally; the app-level pre-check (`findByNormalizedPhone` filtered to active) gives the friendly 409-with-id, and the existing global `DataIntegrityViolationException`→409 handler (challenge #15) is the concurrency backstop. Terminal transitions drop a row out of the predicate, freeing the phone.
- **Lesson:** when a uniqueness rule is conditional on entity state, reach for a partial index rather than either a total index (too strict) or app-only checks (racy). The predicate is the spec.

- [ ] **Step 2: Verify annotations-reference**

Confirm no genuinely new *annotation* was introduced (`@Entity/@Table/@Column/@Enumerated/@RestController/@RequestMapping/@PostMapping/@GetMapping/@PatchMapping/@RequestBody/@Valid/@RequestParam/@PathVariable/@Service/@Transactional/@NotBlank/@NotNull` are all already documented; `@PatchMapping` was added in P1b). `JpaSpecificationExecutor` and `org.springframework.data.jpa.domain.Specification` are **interfaces, not annotations** — the reference's "things that look like annotations but aren't" section is where a one-line note *may* be added, optional. Add nothing else.

- [ ] **Step 3: Update HANDOFF.md**

- Move the enquiry entry out of "deferred" into a "done" state for this branch (§3/§4/§8).
- Update the passing-test count from 132 to the new total (read it off the final `./gradlew test` run — do not guess).
- Note in the deferred list that the enquiry→quotation **convert** endpoint, `activity`/`follow_up`, visibility filtering, and cursor pagination remain out of scope.

- [ ] **Step 4: Update `.superpowers/sdd/progress.md`** (if present/in use) with a one-line-per-task summary mirroring the order-accept ledger format, plus a "Deferred Minor backlog (enquiry)" section if any minors surfaced.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/engineering-challenges.md docs/superpowers/HANDOFF.md \
        docs/superpowers/annotations-reference.md .superpowers/sdd/progress.md
git commit -m "docs(enquiry): log challenge #23, update handoff and ledger"
```

---

## Self-Review (completed while writing this plan)

**1. Spec coverage:**
- Entity/table/fields (spec §4.1) → Task 2 + Task 3 migration. ✓
- `EnquirySource`/`EnquiryStage` enums (§4.2) → Task 2. ✓
- Phone normalization rules (§5.1) → Task 1. ✓
- "One active enquiry per phone" partial index + pre-check + backstop (§5.2) → Task 3 (index/repo test) + Task 4/6 (pre-check). ✓
- 5-stage guarded state machine (§6) → Task 2 (entity guards) + Task 6 (endpoints). ✓
- REST create/get/list/patch/advance/lose (§7) → Tasks 4/5/6. ✓
- List filter combine, order-list-bug regression (§7.1) → Task 5 (`Specification` + two-filter test). ✓
- Testing matrix (§8) → distributed across Tasks 1–6. ✓
- Docs obligations #23 + annotations (§9) → Task 7. ✓
- Out-of-scope (§10) — nothing built for convert/activity/visibility/cursor. ✓

**2. Placeholder scan:** No TBD/TODO/"add error handling"/"similar to Task N". Every code step carries full code. The only deliberately-open item is the `TenantContext.set` signature in Task 3, flagged with an explicit "confirm against `OrderRepositoryTest`" instruction rather than left vague.

**3. Type consistency:** `Enquiry` ctor arg order and `updateHeader` arg order match between Task 2 (definition) and Tasks 4/6 (callers). `EnquiryResponse.of` field order matches the record. `findByNormalizedPhone` returns `List<Enquiry>` (Task 3) and is streamed/filtered in Tasks 4 & 6. `EnquirySpecifications.filter(stage, assignedTo, source)` signature matches the `list` caller and controller params. `advanceTo`/`lose`/`markConverted`/`updateHeader` names are identical across entity, service, and tests.

**Note on the `requireNoActiveDuplicate` refactor:** Task 4 ships `requireNoActiveDuplicate(String)`; Task 6 replaces it with `requireNoActiveDuplicateExcept(String, UUID)` and repoints `create` at it with a `null` self-id. This is an intentional in-task refactor, called out explicitly in Task 6 Step 3 so the implementer doesn't leave a dead method behind.
