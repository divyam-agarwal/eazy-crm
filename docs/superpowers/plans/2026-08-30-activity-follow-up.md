# Activity Log & Follow-Ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `activity` log and first-class `follow_up` tasks across all four funnel aggregates, delivering the product's "you never lose a follow-up" promise.

**Architecture:** Two new aggregates in `com.easycrm.sales`, both polymorphic against `Customer`/`Enquiry`/`Quotation`/`Order` via a `(subject_type, subject_id)` pair. `follow_up` owns an `assigned_to` and joins the existing guarded-repository visibility set; `activity` is gated at its subject through a new `VisibleFinder.requireVisibleSubject` and is protected structurally by extending the bare `Repository` marker so no unscoped read method exists. `OVERDUE` is a read-time predicate, not a status column — this slice ships no scheduler.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Hibernate `@TenantId`, PostgreSQL 16 + Flyway, Postgres RLS, ArchUnit, JUnit 5 + AssertJ + MockMvc, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-08-30-activity-follow-up-design.md`

## Global Constraints

- **Money is never a `double`.** Not directly relevant to this slice (no monetary fields), but `BigDecimal`/`NUMERIC` if one appears.
- **Tenant isolation is structural.** Never hand-write `WHERE tenant_id = ?`. Every new entity extends `TenantScopedEntity` (which supplies `@TenantId`) or `TenantScopingArchTest` fails the build.
- **Every new table gets RLS `ENABLE` + `FORCE` + a `tenant_isolation` policy** in the same task that creates it. `RlsCoverageIntegrationTest` keys on the presence of a `tenant_id` column and will go red otherwise.
- **Commits author as `divyam`.** Plain `git commit`, no `-c user.name=` override, no `Co-Authored-By: Claude` trailer, no mention of Claude/AI anywhere in the message.
- **Filtered test runs must be project-qualified.** `./gradlew :test --tests '<filter>'` for root-project tests. An unqualified `./gradlew test --tests '…'` applies the filter to *both* projects and fails on whichever has no match.
- **Baseline on `main` is 352 tests, 0 failures, 0 errors** (verified green 2026-08-30). Every task must leave the suite green.
- **Branch:** `activity-follow-up`, already cut from `main` at `830fd47`; the spec is committed on it as `212099f`.
- **Cross-tenant and not-visible-to-you both return 404**, never 403.

### Deviation from the spec's migration numbering — read this

Spec §5.3 describes a single `V29__rls_activity_follow_up.sql` covering both tables. **This plan does not do that**, because the two tables land in different tasks (Task 2 and Task 6) and `RlsCoverageIntegrationTest` would go red at Task 2 and stay red until Task 6. Each table therefore ships its own RLS migration in its own task:

| file | task |
|---|---|
| `V27__activity.sql` | Task 2 |
| `V28__rls_activity.sql` | Task 2 |
| `V29__follow_up.sql` | Task 6 |
| `V30__rls_follow_up.sql` | Task 6 |

Same tables, same policies, same `FORCE`; only the file split differs. Nothing else in the spec changes.

### Task count

The spec estimated ~11 tasks. This plan has **14**, because several spec sections split cleanly at a reviewer boundary (the activity write path and the activity edit path can be accepted independently; the follow-up read path and its transitions likewise). No scope was added.

---

## File Structure

**New — `com.easycrm.platform.visibility`**
- `SubjectType.java` — the polymorphic subject enum. Lives here because `VisibleFinder` owns the resolve gate that switches over it.

**New — `com.easycrm.platform.time`**
- `ClockConfig.java` — the codebase's first `Clock` bean.
- `DueWindow.java` — pure IST day-boundary computation.

**New — `com.easycrm.iam`**
- `AssignableUsers.java` — extracted from the two verbatim copies in `EnquiryService` and `CustomerService`.

**New — `com.easycrm.sales`**
- `Activity.java`, `ActivityType.java`, `ActivitySource.java`, `ActivityRepository.java`, `ActivityService.java`
- `FollowUp.java`, `FollowUpStatus.java`, `FollowUpScope.java`, `FollowUpRepository.java`, `FollowUpService.java`, `FollowUpSpecifications.java`
- `QuotationAcceptedActivityListener.java`

**New — `com.easycrm.sales.web` / `.web.dto`**
- `ActivityController.java`, `FollowUpController.java`
- `ActivityCreateRequest`, `ActivityUpdateRequest`, `ActivityResponse`, `NextFollowUpRequest`
- `FollowUpCreateRequest`, `FollowUpUpdateRequest`, `FollowUpCompleteRequest`, `FollowUpCancelRequest`, `FollowUpResponse`, `FollowUpSummaryResponse`

**Modified**
- `VisibleFinder.java` — `requireVisibleSubject`, `findFollowUp`, `pageFollowUps`
- `VisibilityPolicy.java` — `followUps()`
- `EnquiryService.java`, `CustomerService.java` — route through `AssignableUsers`
- `VisibilityScopingArchTest.java` — `FollowUpRepository` into `GUARDED_REPOSITORIES`

---

## Task 1: `SubjectType` and the `requireVisibleSubject` gate

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/visibility/SubjectType.java`
- Modify: `backend/src/main/java/com/easycrm/platform/visibility/VisibleFinder.java`
- Test: `backend/src/test/java/com/easycrm/platform/visibility/RequireVisibleSubjectTest.java`

**Interfaces:**
- Consumes: `VisibleFinder.findCustomer/findEnquiry/findQuotation/findOrder` (all exist).
- Produces: `SubjectType` (values `CUSTOMER`, `ENQUIRY`, `QUOTATION`, `ORDER`) and `UUID VisibleFinder.requireVisibleSubject(SubjectType type, UUID id)` — returns the id it was given, throws `NotFoundException` if the subject is absent, another tenant's, or not visible to the caller. Every later task's write and read path calls this.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/easycrm/platform/visibility/RequireVisibleSubjectTest.java`:

```java
package com.easycrm.platform.visibility;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.Enquiry;
import com.easycrm.sales.EnquiryRepository;
import com.easycrm.sales.EnquirySource;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gate protecting the activity table. See spec
 * 2026-08-30-activity-follow-up-design.md §4.2.
 */
@SpringBootTest
class RequireVisibleSubjectTest extends IntegrationTest {

    @Autowired VisibleFinder finder;
    @Autowired CustomerRepository customers;
    @Autowired EnquiryRepository enquiries;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID execAId = UUID.randomUUID();
    private final UUID execBId = UUID.randomUUID();

    private UUID myEnquiry, execBEnquiry, myCustomer;

    @BeforeEach
    void seed() {
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, execAId, "OWNER"));
        tx.executeWithoutResult(s -> {
            myCustomer = customers.saveAndFlush(
                new Customer("Mine Traders", null, "MH", execAId)).getId();
            myEnquiry = enquiries.saveAndFlush(newEnquiry("9876500011", execAId)).getId();
            execBEnquiry = enquiries.saveAndFlush(newEnquiry("9876500012", execBId)).getId();
        });
        TenantContext.clear();
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void returnsTheIdWhenTheSubjectIsVisible() {
        asExecA(() -> assertThat(
            finder.requireVisibleSubject(SubjectType.ENQUIRY, myEnquiry)).isEqualTo(myEnquiry));
    }

    @Test
    void throwsNotFoundForAnotherExecsSubject() {
        asExecA(() -> assertThatThrownBy(
            () -> finder.requireVisibleSubject(SubjectType.ENQUIRY, execBEnquiry))
            .isInstanceOf(NotFoundException.class));
    }

    @Test
    void throwsNotFoundForAnIdThatDoesNotExist() {
        asExecA(() -> assertThatThrownBy(
            () -> finder.requireVisibleSubject(SubjectType.ENQUIRY, UUID.randomUUID()))
            .isInstanceOf(NotFoundException.class));
    }

    @Test
    void resolvesCustomerSubjectsToo() {
        asExecA(() -> assertThat(
            finder.requireVisibleSubject(SubjectType.CUSTOMER, myCustomer)).isEqualTo(myCustomer));
    }

    @Test
    void anUnrestrictedRoleSeesAnotherExecsSubject() {
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, execAId, "OWNER"),
            () -> tx.executeWithoutResult(s -> assertThat(
                finder.requireVisibleSubject(SubjectType.ENQUIRY, execBEnquiry))
                .isEqualTo(execBEnquiry)));
    }

    private void asExecA(Runnable body) {
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, execAId, "SALES_EXEC"),
            () -> tx.executeWithoutResult(s -> body.run()));
    }

    private Enquiry newEnquiry(String phone, UUID assignedTo) {
        return new Enquiry(null, "Contact", phone, phone, null,
            EnquirySource.MANUAL, "need goods", assignedTo, null);
    }
}
```

> **Note on `new Customer(...)`:** check `Customer`'s actual constructor signature before running — read `backend/src/main/java/com/easycrm/crm/Customer.java` and match it exactly. The four-arg shape above is the expected one (business name, GSTIN, state code, assignedTo); if it differs, adapt the call, not the test's intent.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.platform.visibility.RequireVisibleSubjectTest'
```

Expected: FAIL to compile — `SubjectType` does not exist, `requireVisibleSubject` is undefined.

- [ ] **Step 3: Create `SubjectType`**

`backend/src/main/java/com/easycrm/platform/visibility/SubjectType.java`:

```java
package com.easycrm.platform.visibility;

/**
 * The aggregates an activity or follow-up may hang off. Lives in this package rather than
 * in sales because VisibleFinder owns the resolve gate that switches over it — see spec
 * 2026-08-30-activity-follow-up-design.md §5.
 *
 * <p>Adding a value here is a visibility decision: VisibleFinder.requireVisibleSubject must
 * gain a matching branch, or the new subject type resolves to nothing and every activity
 * against it 404s.
 */
public enum SubjectType { CUSTOMER, ENQUIRY, QUOTATION, ORDER }
```

- [ ] **Step 4: Add the gate to `VisibleFinder`**

Append to `VisibleFinder`, and add `import com.easycrm.platform.error.NotFoundException;`:

```java
    /**
     * Resolves a polymorphic subject through the same visibility filter as a direct read,
     * returning the id unchanged so call sites can inline it. Cross-tenant, non-existent
     * and not-visible-to-you all surface as NotFoundException — the house 404 rule.
     *
     * <p>This is the ONLY thing protecting the activity table: ActivityRepository declares
     * no read that is not subject-scoped, so an activity cannot be reached without first
     * naming a subject, and a subject cannot be named without passing through here.
     * See spec 2026-08-30-activity-follow-up-design.md §4.2.
     */
    public UUID requireVisibleSubject(SubjectType type, UUID id) {
        boolean visible = switch (type) {
            case CUSTOMER  -> findCustomer(id).isPresent();
            case ENQUIRY   -> findEnquiry(id).isPresent();
            case QUOTATION -> findQuotation(id).isPresent();
            case ORDER     -> findOrder(id).isPresent();
        };
        if (!visible) {
            throw new NotFoundException(
                type.name().toLowerCase() + " " + id + " was not found");
        }
        return id;
    }
```

The `switch` is exhaustive over the enum with no `default`, so adding a `SubjectType` value fails compilation here rather than silently returning false.

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.platform.visibility.RequireVisibleSubjectTest'
```

Expected: PASS, 5 tests.

- [ ] **Step 6: Run the full suite**

```bash
cd backend && ./gradlew test
```

Expected: green.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/easycrm/platform/visibility/SubjectType.java \
        backend/src/main/java/com/easycrm/platform/visibility/VisibleFinder.java \
        backend/src/test/java/com/easycrm/platform/visibility/RequireVisibleSubjectTest.java
git commit -m "feat: add the polymorphic subject visibility gate

SubjectType names the four aggregates an activity or follow-up can hang
off, and VisibleFinder.requireVisibleSubject resolves one through the same
filter as a direct read. Keeping the gate inside VisibleFinder means the
guarded-repository rule needs no new exemption: the one class already
permitted to read those repositories is the one doing it."
```

---

## Task 2: The `Activity` aggregate, its table, and RLS

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/Activity.java`, `ActivityType.java`, `ActivitySource.java`, `ActivityRepository.java`
- Create: `backend/src/main/resources/db/migration/V27__activity.sql`, `V28__rls_activity.sql`
- Test: `backend/src/test/java/com/easycrm/sales/ActivityTest.java`, `ActivityRepositoryTest.java`

**Interfaces:**
- Consumes: `SubjectType` (Task 1), `TenantScopedEntity`, `ValidationException`.
- Produces:
  - `enum ActivityType { CALL, WHATSAPP, EMAIL, VISIT, NOTE }`
  - `enum ActivitySource { MANUAL, SYSTEM }`
  - `Activity.manual(SubjectType, UUID subjectId, ActivityType, String body, String outcome, Instant occurredAt, UUID loggedBy, Instant now)`
  - `Activity.system(SubjectType, UUID subjectId, ActivityType, String body, UUID actorUserId, Instant now)`
  - `void Activity.edit(String body, String outcome, UUID editorUserId)`
  - getters: `getSubjectType`, `getSubjectId`, `getType`, `getBody`, `getOutcome`, `getOccurredAt`, `getLoggedBy`, `getSource`
  - `ActivityRepository` with exactly three methods (see Step 5).

- [ ] **Step 1: Write the failing aggregate test**

`backend/src/test/java/com/easycrm/sales/ActivityTest.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.visibility.SubjectType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure aggregate invariants. See spec 2026-08-30-activity-follow-up-design.md §7.1. */
class ActivityTest {

    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
    private static final UUID SUBJECT = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID SOMEONE_ELSE = UUID.randomUUID();

    @Test
    void aManualActivityRecordsWhoLoggedItAndIsMarkedManual() {
        Activity a = manual(NOW.minusSeconds(3600), ME);

        assertThat(a.getLoggedBy()).isEqualTo(ME);
        assertThat(a.getSource()).isEqualTo(ActivitySource.MANUAL);
        assertThat(a.getSubjectType()).isEqualTo(SubjectType.ENQUIRY);
        assertThat(a.getSubjectId()).isEqualTo(SUBJECT);
    }

    @Test
    void occurredAtMayBeInThePast() {
        assertThat(manual(NOW.minusSeconds(86_400), ME).getOccurredAt())
            .isEqualTo(NOW.minusSeconds(86_400));
    }

    @Test
    void occurredAtMayBeExactlyNow() {
        assertThat(manual(NOW, ME).getOccurredAt()).isEqualTo(NOW);
    }

    @Test
    void occurredAtInTheFutureIsRejected() {
        assertThatThrownBy(() -> manual(NOW.plusSeconds(1), ME))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).getFields())
                .containsKey("occurredAt"));
    }

    @Test
    void theLoggerMayEditTheirOwnEntry() {
        Activity a = manual(NOW, ME);
        a.edit("corrected body", "spoke to the owner", ME);

        assertThat(a.getBody()).isEqualTo("corrected body");
        assertThat(a.getOutcome()).isEqualTo("spoke to the owner");
    }

    @Test
    void anotherUserCannotEditIt_andNothingIsMutated() {
        Activity a = manual(NOW, ME);

        assertThatThrownBy(() -> a.edit("hijacked", "nope", SOMEONE_ELSE))
            .isInstanceOf(ValidationException.class);

        assertThat(a.getBody()).isEqualTo("rang them");
        assertThat(a.getOutcome()).isEqualTo("no answer");
    }

    @Test
    void aSystemActivityCannotBeEdited_andNothingIsMutated() {
        Activity a = Activity.system(SubjectType.QUOTATION, SUBJECT, ActivityType.NOTE,
            "Quotation accepted", ME, NOW);

        assertThatThrownBy(() -> a.edit("rewritten", null, ME))
            .isInstanceOf(ValidationException.class);

        assertThat(a.getBody()).isEqualTo("Quotation accepted");
        assertThat(a.getSource()).isEqualTo(ActivitySource.SYSTEM);
    }

    private static Activity manual(Instant occurredAt, UUID loggedBy) {
        return Activity.manual(SubjectType.ENQUIRY, SUBJECT, ActivityType.CALL,
            "rang them", "no answer", occurredAt, loggedBy, NOW);
    }
}
```

Note the two `_andNothingIsMutated` tests assert state is untouched after a rejected edit, not merely that an exception was thrown. This is deliberate — deferred-backlog item 11 records exactly that gap in `OrderTest`, and there is no reason to reproduce it on a new aggregate.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.ActivityTest'
```

Expected: FAIL to compile — `Activity`, `ActivityType`, `ActivitySource` do not exist.

- [ ] **Step 3: Create the two enums**

`backend/src/main/java/com/easycrm/sales/ActivityType.java`:

```java
package com.easycrm.sales;

/** What kind of contact happened. Parent spec 2026-07-22 §data-model. */
public enum ActivityType { CALL, WHATSAPP, EMAIL, VISIT, NOTE }
```

`backend/src/main/java/com/easycrm/sales/ActivitySource.java`:

```java
package com.easycrm.sales;

/**
 * Whether a human logged this row or the system did. SYSTEM rows are never editable —
 * they are a record of something the application itself observed, and letting a user
 * rewrite one would make the log unreliable exactly where it is most trustworthy.
 */
public enum ActivitySource { MANUAL, SYSTEM }
```

- [ ] **Step 4: Create the `Activity` aggregate**

`backend/src/main/java/com/easycrm/sales/Activity.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.persistence.TenantScopedEntity;
import com.easycrm.platform.visibility.SubjectType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A logged contact against one of the four funnel aggregates. Append-mostly: body and
 * outcome may be corrected by whoever logged the row; nothing else ever changes and
 * nothing is ever deleted. See spec 2026-08-30-activity-follow-up-design.md §5.1, §7.1.
 *
 * <p>Visibility is derived from the SUBJECT, not from this row — there is no assigned_to
 * here. ActivityRepository declares no read that is not subject-scoped, so every path to
 * an Activity passes VisibleFinder.requireVisibleSubject first (§4.2).
 */
@Entity
@Table(name = "activity")
public class Activity extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, updatable = false, length = 16)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private ActivityType type;

    @Column(length = 2000)
    private String body;

    /**
     * Free text, deliberately not an enum: the parent spec names the field but never
     * enumerates its values, and nothing reports on it yet. Promoting it once real
     * outcomes have been observed is a migration; inventing the wrong enum now and
     * living with it is the expensive direction (spec §5.1).
     */
    @Column(length = 200)
    private String outcome;

    /**
     * When the contact happened, which is NOT when the row was written — a 3pm call gets
     * logged at 9pm after the shop closes. The timeline sorts on this; createdAt remains
     * the immutable record of insertion.
     */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "logged_by", updatable = false)
    private UUID loggedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 8)
    private ActivitySource source;

    protected Activity() {}

    private Activity(SubjectType subjectType, UUID subjectId, ActivityType type, String body,
                     String outcome, Instant occurredAt, UUID loggedBy, ActivitySource source,
                     Instant now) {
        if (occurredAt.isAfter(now)) {
            throw new ValidationException("occurredAt",
                "cannot log a contact that has not happened yet");
        }
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.type = type;
        this.body = body;
        this.outcome = outcome;
        this.occurredAt = occurredAt;
        this.loggedBy = loggedBy;
        this.source = source;
    }

    /** A human logged this. {@code now} is passed in so the invariant is unit-testable. */
    public static Activity manual(SubjectType subjectType, UUID subjectId, ActivityType type,
                                  String body, String outcome, Instant occurredAt,
                                  UUID loggedBy, Instant now) {
        return new Activity(subjectType, subjectId, type, body, outcome, occurredAt,
            loggedBy, ActivitySource.MANUAL, now);
    }

    /**
     * The application logged this in response to something it observed. occurredAt is
     * always now — a system event happens when it happens.
     */
    public static Activity system(SubjectType subjectType, UUID subjectId, ActivityType type,
                                  String body, UUID actorUserId, Instant now) {
        return new Activity(subjectType, subjectId, type, body, null, now,
            actorUserId, ActivitySource.SYSTEM, now);
    }

    /**
     * Correct a typo. Scoped to body and outcome: changing which enquiry a call was about,
     * or when it happened, is rewriting history rather than correcting a mistake.
     *
     * <p>Both guards run BEFORE any assignment, so a rejected edit leaves the row
     * untouched — asserted directly in ActivityTest.
     */
    public void edit(String body, String outcome, UUID editorUserId) {
        if (source == ActivitySource.SYSTEM) {
            throw new ValidationException("id", "a system-logged activity cannot be edited");
        }
        if (loggedBy == null || !loggedBy.equals(editorUserId)) {
            throw new ValidationException("id", "only the user who logged an activity may edit it");
        }
        this.body = body;
        this.outcome = outcome;
    }

    public SubjectType getSubjectType() { return subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public ActivityType getType() { return type; }
    public String getBody() { return body; }
    public String getOutcome() { return outcome; }
    public Instant getOccurredAt() { return occurredAt; }
    public UUID getLoggedBy() { return loggedBy; }
    public ActivitySource getSource() { return source; }
}
```

- [ ] **Step 5: Create `ActivityRepository` — note the unusual supertype**

`backend/src/main/java/com/easycrm/sales/ActivityRepository.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.visibility.SubjectType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * EXTENDS THE BARE {@code Repository} MARKER ON PURPOSE — do not "fix" this to
 * JpaRepository. Every other repository here extends JpaRepository, which inherits
 * findById/findAll/findAllById. Those methods are not DECLARED on the sub-interface, so a
 * guard phrased over declared methods would happily pass a service calling
 * {@code activities.findById(id)} with no subject resolution at all. Repository is a pure
 * marker and inherits nothing, so the three methods below are the complete set of
 * operations that exist: an activity cannot be read without naming a subject, because
 * there is no method that lets you.
 *
 * <p>ActivityRepositoryScopingArchTest fails the build if this supertype changes or if a
 * non-subject-scoped read is added. See spec 2026-08-30-activity-follow-up-design.md §4.2, §8.
 */
public interface ActivityRepository extends Repository<Activity, UUID> {

    Activity save(Activity activity);

    Page<Activity> findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(
        SubjectType subjectType, UUID subjectId, Pageable pageable);

    Optional<Activity> findByIdAndSubjectTypeAndSubjectId(
        UUID id, SubjectType subjectType, UUID subjectId);
}
```

- [ ] **Step 6: Create the migrations**

`backend/src/main/resources/db/migration/V27__activity.sql`:

```sql
CREATE TABLE activity (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    subject_type  VARCHAR(16) NOT NULL,
    subject_id    UUID NOT NULL,
    type          VARCHAR(16) NOT NULL,
    body          VARCHAR(2000),
    outcome       VARCHAR(200),
    occurred_at   TIMESTAMPTZ NOT NULL,
    logged_by     UUID,
    source        VARCHAR(8) NOT NULL,
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ,
    version       BIGINT NOT NULL DEFAULT 0
);

-- The timeline query: every activity read is scoped to one subject and ordered by
-- occurred_at DESC. Fully covered by this index.
CREATE INDEX idx_activity_subject
    ON activity (tenant_id, subject_type, subject_id, occurred_at DESC);
```

`backend/src/main/resources/db/migration/V28__rls_activity.sql`:

```sql
ALTER TABLE activity ENABLE ROW LEVEL SECURITY;
ALTER TABLE activity FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON activity
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
```

- [ ] **Step 7: Write the persistence test**

`backend/src/test/java/com/easycrm/sales/ActivityRepositoryTest.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ActivityRepositoryTest extends IntegrationTest {

    @Autowired ActivityRepository activities;
    @Autowired TransactionTemplate tx;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void readsBackTheSubjectTimelineNewestFirst() {
        Instant now = Instant.now();
        asTenant(tenantA, () -> {
            activities.save(Activity.manual(SubjectType.ENQUIRY, subject, ActivityType.CALL,
                "older", null, now.minusSeconds(7200), user, now));
            activities.save(Activity.manual(SubjectType.ENQUIRY, subject, ActivityType.CALL,
                "newer", null, now.minusSeconds(60), user, now));
        });

        asTenant(tenantA, () -> assertThat(
            activities.findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                SubjectType.ENQUIRY, subject, PageRequest.of(0, 10)).getContent())
            .extracting(Activity::getBody)
            .containsExactly("newer", "older"));
    }

    @Test
    void anotherTenantSeesNothing() {
        Instant now = Instant.now();
        asTenant(tenantA, () -> activities.save(
            Activity.manual(SubjectType.ENQUIRY, subject, ActivityType.CALL,
                "tenant A only", null, now, user, now)));

        asTenant(tenantB, () -> assertThat(
            activities.findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                SubjectType.ENQUIRY, subject, PageRequest.of(0, 10)).getContent()).isEmpty());
    }

    @Test
    void findByIdIsScopedToTheSubjectItWasFiledUnder() {
        Instant now = Instant.now();
        UUID id = asTenantReturning(tenantA, () -> activities.save(
            Activity.manual(SubjectType.ENQUIRY, subject, ActivityType.NOTE,
                "note", null, now, user, now)).getId());

        asTenant(tenantA, () -> {
            assertThat(activities.findByIdAndSubjectTypeAndSubjectId(
                id, SubjectType.ENQUIRY, subject)).isPresent();
            // Right id, wrong subject -> nothing. This is what makes a by-id-alone
            // lookup unnecessary (spec §9).
            assertThat(activities.findByIdAndSubjectTypeAndSubjectId(
                id, SubjectType.ENQUIRY, UUID.randomUUID())).isEmpty();
            assertThat(activities.findByIdAndSubjectTypeAndSubjectId(
                id, SubjectType.CUSTOMER, subject)).isEmpty();
        });
    }

    private void asTenant(UUID tenantId, Runnable body) {
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, user, "OWNER"),
            () -> tx.executeWithoutResult(s -> body.run()));
    }

    private <T> T asTenantReturning(UUID tenantId, java.util.function.Supplier<T> body) {
        return TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, user, "OWNER"),
            () -> tx.execute(s -> body.get()));
    }
}
```

- [ ] **Step 8: Run both tests**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.ActivityTest' --tests 'com.easycrm.sales.ActivityRepositoryTest'
```

Expected: PASS, 10 tests.

- [ ] **Step 9: Run the full suite — `RlsCoverageIntegrationTest` is the real check here**

```bash
cd backend && ./gradlew test
```

Expected: green. `RlsCoverageIntegrationTest` keys on the `tenant_id` column and would fail if `V28` were missing or omitted `FORCE`; `TenantScopingArchTest` would fail if `Activity` did not extend `TenantScopedEntity`. If either is red, the migration is wrong — fix it rather than allowlisting the table.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/Activity.java \
        backend/src/main/java/com/easycrm/sales/ActivityType.java \
        backend/src/main/java/com/easycrm/sales/ActivitySource.java \
        backend/src/main/java/com/easycrm/sales/ActivityRepository.java \
        backend/src/main/resources/db/migration/V27__activity.sql \
        backend/src/main/resources/db/migration/V28__rls_activity.sql \
        backend/src/test/java/com/easycrm/sales/ActivityTest.java \
        backend/src/test/java/com/easycrm/sales/ActivityRepositoryTest.java
git commit -m "feat: add the activity aggregate, its table and RLS

Polymorphic against the four funnel aggregates via (subject_type,
subject_id). Body and outcome are correctable by whoever logged the row;
everything else is fixed at creation and nothing is ever deleted.

ActivityRepository extends the bare Repository marker rather than
JpaRepository, so it inherits no unscoped read. That is the mechanism, not
a stylistic choice: findById is inherited rather than declared, so a guard
over declared methods would not have caught a service calling it."
```

---

## Task 3: The `ActivityRepository` structural guard

**Files:**
- Create: `backend/src/test/java/com/easycrm/arch/ActivityRepositoryScopingArchTest.java`

**Interfaces:**
- Consumes: `ActivityRepository` (Task 2), `SubjectType` (Task 1).
- Produces: nothing consumed by later tasks — this is a guard.

- [ ] **Step 1: Write the test**

`backend/src/test/java/com/easycrm/arch/ActivityRepositoryScopingArchTest.java`:

```java
package com.easycrm.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The structural half of the activity visibility gate. See spec
 * 2026-08-30-activity-follow-up-design.md §4.2 and §8.
 *
 * <p>Assertion 1 is the load-bearing one and assertion 2 is worthless without it: if the
 * interface extended JpaRepository, findById/findAll would be INHERITED rather than
 * declared, so a rule over declared methods would pass a service that reads an activity
 * with no subject in hand.
 */
class ActivityRepositoryScopingArchTest {

    private static final String REPOSITORY = "com.easycrm.sales.ActivityRepository";
    private static final String SUBJECT_TYPE = "com.easycrm.platform.visibility.SubjectType";

    /** Supertypes that would silently reintroduce unscoped reads by inheritance. */
    private static final Set<String> FORBIDDEN_SUPERTYPES = Set.of(
        "org.springframework.data.repository.CrudRepository",
        "org.springframework.data.repository.ListCrudRepository",
        "org.springframework.data.repository.PagingAndSortingRepository",
        "org.springframework.data.repository.ListPagingAndSortingRepository",
        "org.springframework.data.jpa.repository.JpaRepository",
        "org.springframework.data.jpa.repository.JpaSpecificationExecutor");

    private JavaClass activityRepository() {
        JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.easycrm");
        return classes.get(REPOSITORY);
    }

    @Test
    void inheritsNoUnscopedReadMethods() {
        List<String> supertypes = activityRepository().getAllRawInterfaces().stream()
            .map(JavaClass::getFullName)
            .toList();

        assertThat(supertypes)
            .as("ActivityRepository must extend the bare Repository marker. Extending "
              + "JpaRepository (or any of these) inherits findById/findAll, which are not "
              + "declared here and so escape the declared-method rule below — see spec §4.2")
            .doesNotContainAnyElementsOf(FORBIDDEN_SUPERTYPES)
            .contains("org.springframework.data.repository.Repository");
    }

    @Test
    void declaresNoReadThatIsNotScopedToASubject() {
        for (JavaMethod method : activityRepository().getMethods()) {
            if (method.getName().equals("save")) continue;

            List<String> params = method.getRawParameterTypes().stream()
                .map(JavaClass::getFullName)
                .toList();

            assertThat(params)
                .as("ActivityRepository.%s must take a SubjectType — an activity read that "
                  + "does not name a subject bypasses VisibleFinder.requireVisibleSubject "
                  + "entirely (spec §4.2)", method.getName())
                .contains(SUBJECT_TYPE);

            assertThat(params)
                .as("ActivityRepository.%s must also take a subject id", method.getName())
                .contains("java.util.UUID");
        }
    }
}
```

Note assertion 2 checks **parameter types**, not the method name — so `findByLoggedBy` fails while a correctly-scoped finder named something unexpected still passes.

- [ ] **Step 2: Run it — expect PASS**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.arch.ActivityRepositoryScopingArchTest'
```

Expected: PASS, 2 tests. (This guard is written against code that already satisfies it, so the meaningful verification is the falsification in Steps 3–6.)

- [ ] **Step 3: Falsify assertion 1**

Temporarily change `ActivityRepository`'s declaration to:

```java
public interface ActivityRepository extends JpaRepository<Activity, UUID> {
```

(add `import org.springframework.data.jpa.repository.JpaRepository;`). Run:

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.arch.ActivityRepositoryScopingArchTest'
```

Expected: FAIL on `inheritsNoUnscopedReadMethods`. **Revert the change.** A guard whose most important assertion was never observed failing is not evidence.

- [ ] **Step 4: Falsify assertion 2**

Temporarily add to `ActivityRepository`:

```java
    java.util.List<Activity> findByLoggedBy(UUID loggedBy);
```

Run the same command. Expected: FAIL on `declaresNoReadThatIsNotScopedToASubject`, naming `findByLoggedBy`. **Revert the change.**

- [ ] **Step 5: Confirm the file is back to its Task 2 state**

```bash
cd backend && git diff --exit-code src/main/java/com/easycrm/sales/ActivityRepository.java && echo "clean"
```

Expected: prints `clean`. If it does not, the falsification edits were not fully reverted.

- [ ] **Step 6: Run the full suite**

```bash
cd backend && ./gradlew test
```

Expected: green.

- [ ] **Step 7: Commit**

```bash
git add backend/src/test/java/com/easycrm/arch/ActivityRepositoryScopingArchTest.java
git commit -m "test: guard the activity repository's subject-scoping structurally

Two assertions. The first pins the bare Repository supertype and is the
load-bearing one — the second is worthless without it, because under
JpaRepository the dangerous methods are inherited rather than declared and
a declared-method rule never sees them. Both were observed failing before
being committed: JpaRepository trips the first, a findByLoggedBy trips the
second."
```

---

## Task 4: `ActivityService`, DTOs and the create/list endpoints

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/ActivityService.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/ActivityController.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/ActivityCreateRequest.java`, `ActivityResponse.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/ActivityEndpointTest.java`, `backend/src/test/java/com/easycrm/sales/ActivityVisibilityTest.java`

**Interfaces:**
- Consumes: `VisibleFinder.requireVisibleSubject` (Task 1), `Activity.manual` + `ActivityRepository` (Task 2), `PageResponse.of`.
- Produces:
  - `ActivityResponse.of(Activity)` — used by Tasks 5, 12.
  - `ActivityService.create(ActivityCreateRequest)` → `ActivityResponse` — extended in Task 12 with `nextFollowUp`.
  - `ActivityService.list(SubjectType, UUID, Pageable)` → `PageResponse<ActivityResponse>`
  - `ActivityService.logSystem(SubjectType, UUID, ActivityType, String body, UUID actor)` → `void` — used by Tasks 11 and 13. **This method does NOT call `requireVisibleSubject`**; see its javadoc.

- [ ] **Step 1: Write the failing endpoint test**

`backend/src/test/java/com/easycrm/sales/web/ActivityEndpointTest.java`:

```java
package com.easycrm.sales.web;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.Enquiry;
import com.easycrm.sales.EnquiryRepository;
import com.easycrm.sales.EnquirySource;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract for logging and reading activities. Spec §9. */
@SpringBootTest
@AutoConfigureMockMvc
class ActivityEndpointTest extends IntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired EnquiryRepository enquiries;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private String ownerToken;
    private UUID enquiryId;

    @BeforeEach
    void seed() {
        ownerToken = tokens.as(tenantId, ownerId, "OWNER");
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, ownerId, "OWNER"));
        tx.executeWithoutResult(s -> enquiryId = enquiries.saveAndFlush(new Enquiry(
            null, "Ramesh", "9876511001", "9876511001", null,
            EnquirySource.MANUAL, "needs 10 bags", ownerId, null)).getId());
        TenantContext.clear();
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void logsACallAgainstAnEnquiry() throws Exception {
        mvc.perform(post("/api/v1/activities").header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","type":"CALL",
                     "body":"rang them","outcome":"no answer"}
                    """.formatted(enquiryId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.type").value("CALL"))
            .andExpect(jsonPath("$.source").value("MANUAL"))
            .andExpect(jsonPath("$.body").value("rang them"))
            .andExpect(jsonPath("$.loggedBy").value(ownerId.toString()))
            .andExpect(jsonPath("$.occurredAt").exists());
    }

    @Test
    void occurredAtDefaultsToNowWhenOmitted() throws Exception {
        mvc.perform(post("/api/v1/activities").header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","type":"NOTE","body":"walked in"}
                    """.formatted(enquiryId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.occurredAt").exists());
    }

    @Test
    void aFutureOccurredAtIs422() throws Exception {
        mvc.perform(post("/api/v1/activities").header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","type":"CALL",
                     "body":"time travel","occurredAt":"%s"}
                    """.formatted(enquiryId, Instant.now().plusSeconds(86_400))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.fields.occurredAt").exists());
    }

    @Test
    void aSubjectThatDoesNotExistIs404() throws Exception {
        mvc.perform(post("/api/v1/activities").header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","type":"CALL","body":"x"}
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isNotFound());
    }

    @Test
    void listsTheSubjectTimelineNewestFirst() throws Exception {
        log("older", Instant.now().minusSeconds(7200));
        log("newer", Instant.now().minusSeconds(60));

        mvc.perform(get("/api/v1/activities")
                .param("subjectType", "ENQUIRY").param("subjectId", enquiryId.toString())
                .header(AUTH, "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].body").value("newer"))
            .andExpect(jsonPath("$.content[1].body").value("older"));
    }

    @Test
    void listingWithoutASubjectIs400() throws Exception {
        mvc.perform(get("/api/v1/activities").header(AUTH, "Bearer " + ownerToken))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listingAnInvisibleSubjectIs404() throws Exception {
        mvc.perform(get("/api/v1/activities")
                .param("subjectType", "ENQUIRY").param("subjectId", UUID.randomUUID().toString())
                .header(AUTH, "Bearer " + ownerToken))
            .andExpect(status().isNotFound());
    }

    private void log(String body, Instant occurredAt) throws Exception {
        mvc.perform(post("/api/v1/activities").header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","type":"CALL",
                     "body":"%s","occurredAt":"%s"}
                    """.formatted(enquiryId, body, occurredAt)))
            .andExpect(status().isCreated());
    }
}
```

`listingWithoutASubjectIs400` matters: it proves the route has no unscoped shape. A missing required `@RequestParam` is a `MissingServletRequestParameterException`, which Spring maps to 400 — this is the API-level expression of spec §4.2.

- [ ] **Step 2: Run it to verify it fails**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.web.ActivityEndpointTest'
```

Expected: FAIL — no such route, 404/401 on every case, and compile errors are absent because the test only speaks HTTP.

- [ ] **Step 3: Create the DTOs**

`backend/src/main/java/com/easycrm/sales/web/dto/ActivityCreateRequest.java`:

```java
package com.easycrm.sales.web.dto;

import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.sales.ActivityType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * subjectType and subjectId are required on every activity route, read and write — see
 * spec 2026-08-30-activity-follow-up-design.md §9. occurredAt is optional and defaults to
 * now; a future value is rejected by the aggregate, not here, because the comparison needs
 * the service's Clock.
 *
 * <p>nextFollowUp is added in Task 12 (the log-and-schedule flow).
 */
public record ActivityCreateRequest(
    @NotNull SubjectType subjectType,
    @NotNull UUID subjectId,
    @NotNull ActivityType type,
    @Size(max = 2000) String body,
    @Size(max = 200) String outcome,
    Instant occurredAt) {}
```

`backend/src/main/java/com/easycrm/sales/web/dto/ActivityResponse.java`:

```java
package com.easycrm.sales.web.dto;

import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.sales.Activity;
import com.easycrm.sales.ActivitySource;
import com.easycrm.sales.ActivityType;

import java.time.Instant;
import java.util.UUID;

public record ActivityResponse(
    UUID id, SubjectType subjectType, UUID subjectId, ActivityType type,
    String body, String outcome, Instant occurredAt, UUID loggedBy,
    ActivitySource source, Instant createdAt) {

    public static ActivityResponse of(Activity a) {
        return new ActivityResponse(a.getId(), a.getSubjectType(), a.getSubjectId(),
            a.getType(), a.getBody(), a.getOutcome(), a.getOccurredAt(),
            a.getLoggedBy(), a.getSource(), a.getCreatedAt());
    }
}
```

- [ ] **Step 4: Create `ActivityService`**

`backend/src/main/java/com/easycrm/sales/ActivityService.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.platform.visibility.VisibleFinder;
import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.web.dto.ActivityCreateRequest;
import com.easycrm.sales.web.dto.ActivityResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class ActivityService {

    private final ActivityRepository activities;
    private final VisibleFinder finder;
    private final Clock clock;

    public ActivityService(ActivityRepository activities, VisibleFinder finder, Clock clock) {
        this.activities = activities;
        this.finder = finder;
        this.clock = clock;
    }

    @Transactional
    public ActivityResponse create(ActivityCreateRequest req) {
        finder.requireVisibleSubject(req.subjectType(), req.subjectId());
        Instant now = clock.instant();
        Instant occurredAt = req.occurredAt() == null ? now : req.occurredAt();
        return ActivityResponse.of(activities.save(Activity.manual(
            req.subjectType(), req.subjectId(), req.type(), req.body(), req.outcome(),
            occurredAt, currentUserId(), now)));
    }

    @Transactional(readOnly = true)
    public PageResponse<ActivityResponse> list(SubjectType subjectType, UUID subjectId,
                                               Pageable pageable) {
        finder.requireVisibleSubject(subjectType, subjectId);
        return PageResponse.of(activities
            .findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(subjectType, subjectId, pageable)
            .map(ActivityResponse::of));
    }

    /**
     * Writes a SYSTEM activity for something the application observed. Deliberately does
     * NOT call requireVisibleSubject: the caller is an internal flow that has already
     * loaded and authorised the subject (an event listener, or a follow-up transition on a
     * row the caller just read through VisibleFinder). Re-resolving would be a second
     * query for no gain, and worse, it would fail outright for a listener running under a
     * synthetic principal that VisibilityPolicy treats as unrestricted-but-userless.
     *
     * <p>The safety argument is therefore "the caller already passed the gate", which is
     * only sound because this method has exactly ONE call site —
     * QuotationAcceptedActivityListener, added in a later task. Any new caller must be
     * able to make the same claim; one that cannot wants create() and the full gate.
     * (The activity written when a follow-up is completed does NOT come through here: a
     * user typed that one, so it must stay editable and goes through
     * logManualForGatedCaller instead.)
     */
    @Transactional
    public void logSystem(SubjectType subjectType, UUID subjectId, ActivityType type,
                          String body, UUID actorUserId) {
        Instant now = clock.instant();
        activities.save(Activity.system(subjectType, subjectId, type, body, actorUserId, now));
    }

    private static UUID currentUserId() {
        return TenantContext.get().map(TenantContext.TenantPrincipal::userId).orElse(null);
    }
}
```

> **Ordering note:** this service injects a `Clock` bean, which does not exist until Task 7. Task 7 must therefore be pulled forward if you are executing strictly in order — **or**, simpler, do Step 5 below now.

- [ ] **Step 5: Create the `Clock` bean now (it is needed from here on)**

`backend/src/main/java/com/easycrm/platform/time/ClockConfig.java`:

```java
package com.easycrm.platform.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The codebase's first Clock bean. Services take it rather than calling Instant.now() so
 * that time-dependent logic is expressed as a value they are handed.
 *
 * <p>Note that no test overrides this bean: doing so would fork the Spring context shared
 * by every IntegrationTest subclass. Determinism comes instead from passing an explicit
 * {@code now} into the aggregates and into DueWindow, both of which are pure. See spec
 * 2026-08-30-activity-follow-up-design.md §7.3.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() { return Clock.systemUTC(); }
}
```

- [ ] **Step 6: Create `ActivityController`**

`backend/src/main/java/com/easycrm/sales/web/ActivityController.java`:

```java
package com.easycrm.sales.web;

import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.ActivityService;
import com.easycrm.sales.web.dto.ActivityCreateRequest;
import com.easycrm.sales.web.dto.ActivityResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/activities")
public class ActivityController {

    private final ActivityService service;

    public ActivityController(ActivityService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ActivityResponse> create(@Valid @RequestBody ActivityCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    /**
     * subjectType and subjectId are REQUIRED, and that is the point: there is no unscoped
     * activity list, because an activity's visibility is derived from its subject and the
     * only gate is resolving that subject. Omitting either yields 400 from Spring before
     * any code runs. See spec 2026-08-30-activity-follow-up-design.md §4.2, §9.
     */
    @GetMapping
    public PageResponse<ActivityResponse> list(@RequestParam SubjectType subjectType,
                                               @RequestParam UUID subjectId,
                                               Pageable pageable) {
        return service.list(subjectType, subjectId, pageable);
    }
}
```

- [ ] **Step 7: Run the endpoint test**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.web.ActivityEndpointTest'
```

Expected: PASS, 7 tests.

- [ ] **Step 8: Write the visibility test**

`backend/src/test/java/com/easycrm/sales/ActivityVisibilityTest.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The load-bearing tests: an activity hanging off an enquiry I cannot see must be
 * unreachable, on both the write and the read path. Spec §4.2, §10.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActivityVisibilityTest extends IntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired EnquiryRepository enquiries;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID execAId = UUID.randomUUID();
    private final UUID execBId = UUID.randomUUID();

    private String execAToken, ownerToken;
    private UUID mine, execBEnquiry, pool;

    @BeforeEach
    void seed() {
        execAToken = tokens.as(tenantId, execAId, "SALES_EXEC");
        ownerToken = tokens.as(tenantId, UUID.randomUUID(), "OWNER");
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, execAId, "OWNER"));
        tx.executeWithoutResult(s -> {
            mine = enquiries.saveAndFlush(newEnquiry("9876522001", execAId)).getId();
            execBEnquiry = enquiries.saveAndFlush(newEnquiry("9876522002", execBId)).getId();
            pool = enquiries.saveAndFlush(newEnquiry("9876522003", null)).getId();
        });
        TenantContext.clear();
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void execCanLogAgainstTheirOwnEnquiry() throws Exception {
        logAs(execAToken, mine).andExpect(status().isCreated());
    }

    @Test
    void execCanLogAgainstAnUnassignedEnquiry() throws Exception {
        logAs(execAToken, pool).andExpect(status().isCreated());
    }

    @Test
    void execCannotLogAgainstAnotherExecsEnquiry() throws Exception {
        logAs(execAToken, execBEnquiry).andExpect(status().isNotFound());
    }

    @Test
    void execCannotReadAnotherExecsEnquiryTimeline() throws Exception {
        logAs(ownerToken, execBEnquiry).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/activities")
                .param("subjectType", "ENQUIRY").param("subjectId", execBEnquiry.toString())
                .header(AUTH, "Bearer " + execAToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void ownerCanReadAnyTimelineInTheTenant() throws Exception {
        logAs(ownerToken, execBEnquiry).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/activities")
                .param("subjectType", "ENQUIRY").param("subjectId", execBEnquiry.toString())
                .header(AUTH, "Bearer " + ownerToken))
            .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions logAs(String token, UUID subject)
            throws Exception {
        return mvc.perform(post("/api/v1/activities").header(AUTH, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"subjectType":"ENQUIRY","subjectId":"%s","type":"CALL","body":"rang"}
                """.formatted(subject)));
    }

    private Enquiry newEnquiry(String phone, UUID assignedTo) {
        return new Enquiry(null, "Contact", phone, phone, null,
            EnquirySource.MANUAL, "need goods", assignedTo, null);
    }
}
```

Both the restricted and the unrestricted role are exercised — a test that only checks the 404s proves the route is broken, not that it is scoped.

- [ ] **Step 9: Run it**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.ActivityVisibilityTest'
```

Expected: PASS, 5 tests.

- [ ] **Step 10: Run the full suite**

```bash
cd backend && ./gradlew test
```

Expected: green.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/ActivityService.java \
        backend/src/main/java/com/easycrm/sales/web/ActivityController.java \
        backend/src/main/java/com/easycrm/sales/web/dto/ActivityCreateRequest.java \
        backend/src/main/java/com/easycrm/sales/web/dto/ActivityResponse.java \
        backend/src/main/java/com/easycrm/platform/time/ClockConfig.java \
        backend/src/test/java/com/easycrm/sales/web/ActivityEndpointTest.java \
        backend/src/test/java/com/easycrm/sales/ActivityVisibilityTest.java
git commit -m "feat: log and read activities against a subject

POST /api/v1/activities and GET /api/v1/activities, both requiring
subjectType and subjectId. Omitting either on the read is a 400 from Spring
before any code runs, which is the API-level expression of the rule that an
activity has no visibility of its own.

Adds the codebase's first Clock bean. No test overrides it — determinism
comes from passing an explicit now into the pure aggregate instead, which
avoids forking the Spring context every integration test shares."
```

---

## Task 5: Editing an activity

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/ActivityUpdateRequest.java`
- Modify: `backend/src/main/java/com/easycrm/sales/ActivityService.java`, `backend/src/main/java/com/easycrm/sales/web/ActivityController.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/ActivityEditEndpointTest.java`

**Interfaces:**
- Consumes: `Activity.edit` (Task 2), `ActivityRepository.findByIdAndSubjectTypeAndSubjectId` (Task 2), `ActivityService` (Task 4).
- Produces: `ActivityService.update(UUID id, ActivityUpdateRequest)` → `ActivityResponse`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/easycrm/sales/web/ActivityEditEndpointTest.java`:

```java
package com.easycrm.sales.web;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.Enquiry;
import com.easycrm.sales.EnquiryRepository;
import com.easycrm.sales.EnquirySource;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §7.1: own MANUAL rows only, and the deliberate 422-not-404 choice. */
@SpringBootTest
@AutoConfigureMockMvc
class ActivityEditEndpointTest extends IntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired EnquiryRepository enquiries;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();

    private String ownerToken, otherToken;
    private UUID enquiryId, activityId;

    @BeforeEach
    void seed() throws Exception {
        ownerToken = tokens.as(tenantId, ownerId, "OWNER");
        otherToken = tokens.as(tenantId, otherId, "OWNER");

        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, ownerId, "OWNER"));
        tx.executeWithoutResult(s -> enquiryId = enquiries.saveAndFlush(new Enquiry(
            null, "Ramesh", "9876533001", "9876533001", null,
            EnquirySource.MANUAL, "needs bags", null, null)).getId());
        TenantContext.clear();

        String created = mvc.perform(post("/api/v1/activities")
                .header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","type":"CALL",
                     "body":"rang them","outcome":"no answer"}
                    """.formatted(enquiryId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        activityId = UUID.fromString(json.readTree(created).get("id").asText());
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void theLoggerCanCorrectTheirOwnEntry() throws Exception {
        mvc.perform(edit(ownerToken, "rang them twice", "spoke to the owner"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.body").value("rang them twice"))
            .andExpect(jsonPath("$.outcome").value("spoke to the owner"));
    }

    @Test
    void anotherUserGets422NotNotFound() throws Exception {
        mvc.perform(edit(otherToken, "hijacked", null))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aMismatchedSubjectIs404() throws Exception {
        mvc.perform(patch("/api/v1/activities/" + activityId)
                .header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","body":"x","outcome":null}
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownActivityIs404() throws Exception {
        mvc.perform(patch("/api/v1/activities/" + UUID.randomUUID())
                .header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","body":"x","outcome":null}
                    """.formatted(enquiryId)))
            .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.RequestBuilder edit(
            String token, String body, String outcome) {
        return patch("/api/v1/activities/" + activityId)
            .header(AUTH, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"subjectType":"ENQUIRY","subjectId":"%s","body":"%s","outcome":%s}
                """.formatted(enquiryId, body,
                    outcome == null ? "null" : "\"" + outcome + "\""));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.web.ActivityEditEndpointTest'
```

Expected: FAIL — no PATCH route (405 or 404).

- [ ] **Step 3: Create `ActivityUpdateRequest`**

`backend/src/main/java/com/easycrm/sales/web/dto/ActivityUpdateRequest.java`:

```java
package com.easycrm.sales.web.dto;

import com.easycrm.platform.visibility.SubjectType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * subjectType and subjectId are carried in the BODY, not merely in the path, because
 * ActivityRepository declares no by-id-alone lookup — that absence is what makes the
 * subject gate structural rather than conventional (spec §4.2, §9). The client is always
 * editing a row it just rendered inside a subject's timeline, so it has both to hand.
 *
 * <p>Only body and outcome are editable. type, occurredAt, subject and source are fixed
 * at creation: correcting a typo is a correction; changing which enquiry a call was about
 * is rewriting history.
 */
public record ActivityUpdateRequest(
    @NotNull SubjectType subjectType,
    @NotNull UUID subjectId,
    @Size(max = 2000) String body,
    @Size(max = 200) String outcome) {}
```

- [ ] **Step 4: Add `update` to `ActivityService`**

Append to `ActivityService` (and add `import com.easycrm.platform.error.NotFoundException;` and `import com.easycrm.sales.web.dto.ActivityUpdateRequest;`):

```java
    /**
     * Full replace of the two editable fields, matching the house PATCH convention: an
     * omitted body or outcome is CLEARED, not preserved (see deferred-backlog item 8).
     *
     * <p>Note the ordering: the subject gate runs first, so an activity on an invisible
     * subject 404s before ownership is ever considered. Ownership then yields 422, which
     * is correct rather than a departure from the 404 rule — the row is already provably
     * visible to this caller, so a 404 would reveal nothing extra and would actively
     * mislead a client into retrying a GET that succeeds (spec §7.1).
     */
    @Transactional
    public ActivityResponse update(UUID id, ActivityUpdateRequest req) {
        finder.requireVisibleSubject(req.subjectType(), req.subjectId());
        Activity a = activities
            .findByIdAndSubjectTypeAndSubjectId(id, req.subjectType(), req.subjectId())
            .orElseThrow(() -> new NotFoundException("activity " + id + " was not found"));
        a.edit(req.body(), req.outcome(), currentUserId());
        return ActivityResponse.of(a);
    }
```

- [ ] **Step 5: Add the route to `ActivityController`**

```java
    /**
     * Full-header-replace on the two editable fields, per the house convention shared with
     * EnquiryController.patch and QuotationController.patch: an omitted nullable field is
     * cleared. Own MANUAL rows only.
     */
    @PatchMapping("/{id}")
    public ActivityResponse update(@PathVariable UUID id,
                                   @Valid @RequestBody ActivityUpdateRequest req) {
        return service.update(id, req);
    }
```

Add imports: `org.springframework.web.bind.annotation.PatchMapping`, `PathVariable`, and `com.easycrm.sales.web.dto.ActivityUpdateRequest`.

- [ ] **Step 6: Run the test**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.web.ActivityEditEndpointTest'
```

Expected: PASS, 4 tests.

- [ ] **Step 7: Run the full suite**

```bash
cd backend && ./gradlew test
```

Expected: green.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/ActivityService.java \
        backend/src/main/java/com/easycrm/sales/web/ActivityController.java \
        backend/src/main/java/com/easycrm/sales/web/dto/ActivityUpdateRequest.java \
        backend/src/test/java/com/easycrm/sales/web/ActivityEditEndpointTest.java
git commit -m "feat: let a user correct an activity they logged

Body and outcome only, own MANUAL rows only. The subject gate runs before
the ownership check, so an invisible subject 404s and someone else's
activity 422s — which is not a departure from the 404 rule: that row is
already provably visible to the caller, so 404 would reveal nothing and
would send a client into retrying a GET that succeeds."
```

---

## Task 6: The `FollowUp` aggregate, its table, and RLS

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/FollowUp.java`, `FollowUpStatus.java`, `FollowUpRepository.java`
- Create: `backend/src/main/resources/db/migration/V29__follow_up.sql`, `V30__rls_follow_up.sql`
- Test: `backend/src/test/java/com/easycrm/sales/FollowUpTest.java`

**Interfaces:**
- Consumes: `SubjectType` (Task 1), `TenantScopedEntity`, `ValidationException`.
- Produces:
  - `enum FollowUpStatus { PENDING, DONE, CANCELLED }`
  - `new FollowUp(SubjectType, UUID subjectId, Instant dueAt, UUID assignedTo, String note, UUID createdBy)`
  - `void reschedule(Instant dueAt, UUID assignedTo, String note)`
  - `void complete(String completionNote, Instant now)`
  - `void cancel(String reason, Instant now)`
  - getters: `getSubjectType`, `getSubjectId`, `getDueAt`, `getAssignedTo`, `getStatus`, `getNote`, `getCompletedAt`, `getCompletionNote`, `getCreatedBy`
  - `FollowUpRepository extends JpaRepository<FollowUp, UUID>, JpaSpecificationExecutor<FollowUp>`

- [ ] **Step 1: Write the failing aggregate test**

`backend/src/test/java/com/easycrm/sales/FollowUpTest.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.visibility.SubjectType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure aggregate invariants. See spec 2026-08-30-activity-follow-up-design.md §7.2. */
class FollowUpTest {

    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
    private static final Instant DUE = Instant.parse("2026-09-02T04:30:00Z");
    private static final UUID SUBJECT = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();

    @Test
    void aNewFollowUpIsPending() {
        FollowUp f = pending();

        assertThat(f.getStatus()).isEqualTo(FollowUpStatus.PENDING);
        assertThat(f.getDueAt()).isEqualTo(DUE);
        assertThat(f.getAssignedTo()).isEqualTo(ME);
        assertThat(f.getCompletedAt()).isNull();
    }

    @Test
    void aPastDueDateIsAllowedOnCreate() {
        // "I should have called them yesterday" is real and useful to record. It lands in
        // scope=OVERDUE, which is exactly where it belongs (spec §7.2).
        FollowUp f = new FollowUp(SubjectType.ENQUIRY, SUBJECT,
            NOW.minusSeconds(86_400), ME, "overdue already", ME);

        assertThat(f.getStatus()).isEqualTo(FollowUpStatus.PENDING);
    }

    @Test
    void completingRecordsTheNoteAndTheTime() {
        FollowUp f = pending();
        f.complete("spoke to them, sending a revised quote", NOW);

        assertThat(f.getStatus()).isEqualTo(FollowUpStatus.DONE);
        assertThat(f.getCompletedAt()).isEqualTo(NOW);
        assertThat(f.getCompletionNote()).isEqualTo("spoke to them, sending a revised quote");
    }

    @Test
    void cancellingRecordsTheReason() {
        FollowUp f = pending();
        f.cancel("customer went with a competitor", NOW);

        assertThat(f.getStatus()).isEqualTo(FollowUpStatus.CANCELLED);
        assertThat(f.getCompletedAt()).isEqualTo(NOW);
        assertThat(f.getCompletionNote()).isEqualTo("customer went with a competitor");
    }

    @Test
    void cancellingWithoutAReasonIsRejected_andNothingIsMutated() {
        FollowUp f = pending();

        assertThatThrownBy(() -> f.cancel("  ", NOW))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).getFields())
                .containsKey("reason"));

        assertThat(f.getStatus()).isEqualTo(FollowUpStatus.PENDING);
        assertThat(f.getCompletedAt()).isNull();
        assertThat(f.getCompletionNote()).isNull();
    }

    @Test
    void completingTwiceIsRejected_andNothingIsMutated() {
        FollowUp f = pending();
        f.complete("done", NOW);
        Instant later = NOW.plusSeconds(3600);

        assertThatThrownBy(() -> f.complete("again", later))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).getFields().get("status"))
                .contains("done"));

        assertThat(f.getCompletedAt()).isEqualTo(NOW);
        assertThat(f.getCompletionNote()).isEqualTo("done");
    }

    @Test
    void cancellingACompletedFollowUpIsRejected_andNothingIsMutated() {
        FollowUp f = pending();
        f.complete("done", NOW);

        assertThatThrownBy(() -> f.cancel("changed my mind", NOW.plusSeconds(60)))
            .isInstanceOf(ValidationException.class);

        assertThat(f.getStatus()).isEqualTo(FollowUpStatus.DONE);
        assertThat(f.getCompletionNote()).isEqualTo("done");
    }

    @Test
    void reschedulingAPendingFollowUpMovesIt() {
        FollowUp f = pending();
        Instant newDue = DUE.plusSeconds(86_400);
        UUID someoneElse = UUID.randomUUID();

        f.reschedule(newDue, someoneElse, "pushed to Wednesday");

        assertThat(f.getDueAt()).isEqualTo(newDue);
        assertThat(f.getAssignedTo()).isEqualTo(someoneElse);
        assertThat(f.getNote()).isEqualTo("pushed to Wednesday");
    }

    @Test
    void reschedulingACancelledFollowUpIsRejected_andNothingIsMutated() {
        FollowUp f = pending();
        f.cancel("not interested", NOW);

        assertThatThrownBy(() -> f.reschedule(DUE.plusSeconds(86_400), ME, "revived"))
            .isInstanceOf(ValidationException.class);

        assertThat(f.getDueAt()).isEqualTo(DUE);
        assertThat(f.getNote()).isEqualTo("ring back about the rate");
    }

    @Test
    void anAssigneeIsRequired() {
        assertThatThrownBy(() -> new FollowUp(SubjectType.ENQUIRY, SUBJECT, DUE, null,
            "nobody owns this", ME))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).getFields())
                .containsKey("assignedTo"));
    }

    private static FollowUp pending() {
        return new FollowUp(SubjectType.ENQUIRY, SUBJECT, DUE, ME,
            "ring back about the rate", ME);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.FollowUpTest'
```

Expected: FAIL to compile — `FollowUp`, `FollowUpStatus` do not exist.

- [ ] **Step 3: Create `FollowUpStatus`**

`backend/src/main/java/com/easycrm/sales/FollowUpStatus.java`:

```java
package com.easycrm.sales;

/**
 * Note what is ABSENT: there is no OVERDUE. Overdue is a read-time predicate
 * (status = PENDING AND due_at &lt; now), not stored state — so there is no job that can
 * fall behind and leave a row lying about itself. See spec
 * 2026-08-30-activity-follow-up-design.md §3.
 */
public enum FollowUpStatus {
    PENDING, DONE, CANCELLED;

    public boolean isTerminal() { return this != PENDING; }
}
```

- [ ] **Step 4: Create the `FollowUp` aggregate**

`backend/src/main/java/com/easycrm/sales/FollowUp.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.persistence.TenantScopedEntity;
import com.easycrm.platform.visibility.SubjectType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A due-dated task against one of the four funnel aggregates — the thing the product's
 * "you never lose a follow-up" promise is made of. See spec
 * 2026-08-30-activity-follow-up-design.md §5.2, §7.2.
 *
 * <p>Unlike Activity, this row carries its own assigned_to and therefore has intrinsic
 * visibility: it joins the guarded-repository set and is filtered by VisibilityPolicy
 * (§4.1). That asymmetry is deliberate, not an oversight.
 */
@Entity
@Table(name = "follow_up")
public class FollowUp extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, updatable = false, length = 16)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    /**
     * NOT NULL by design. A follow-up nobody owns is precisely the failure this feature
     * exists to prevent, which is also why VisibilityPolicy.followUps() is a plain
     * equality rather than the ownedOrUnassigned() shape the other aggregates use — the
     * IS NULL branch would be unreachable (§4.1).
     */
    @Column(name = "assigned_to", nullable = false)
    private UUID assignedTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FollowUpStatus status;

    @Column(length = 500)
    private String note;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** The note sent to /complete, or the reason sent to /cancel — status says which. */
    @Column(name = "completion_note", length = 500)
    private String completionNote;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected FollowUp() {}

    public FollowUp(SubjectType subjectType, UUID subjectId, Instant dueAt, UUID assignedTo,
                    String note, UUID createdBy) {
        if (assignedTo == null) {
            throw new ValidationException("assignedTo", "a follow-up must have an owner");
        }
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.dueAt = dueAt;
        this.assignedTo = assignedTo;
        this.note = note;
        this.createdBy = createdBy;
        this.status = FollowUpStatus.PENDING;
    }

    /** Move, reassign, or re-word a pending follow-up. Full replace, per house PATCH. */
    public void reschedule(Instant dueAt, UUID assignedTo, String note) {
        requirePending("rescheduled");
        if (assignedTo == null) {
            throw new ValidationException("assignedTo", "a follow-up must have an owner");
        }
        this.dueAt = dueAt;
        this.assignedTo = assignedTo;
        this.note = note;
    }

    public void complete(String completionNote, Instant now) {
        requirePending("completed");
        this.status = FollowUpStatus.DONE;
        this.completedAt = now;
        this.completionNote = completionNote;
    }

    public void cancel(String reason, Instant now) {
        requirePending("cancelled");
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("reason",
                "a reason is required to cancel a follow-up");
        }
        this.status = FollowUpStatus.CANCELLED;
        this.completedAt = now;
        this.completionNote = reason;
    }

    /**
     * Every guard runs BEFORE any assignment, so a rejected transition leaves the row
     * untouched. FollowUpTest asserts that directly rather than only asserting the
     * exception type — deferred-backlog item 11 records what happens when it does not.
     */
    private void requirePending(String verb) {
        if (status.isTerminal()) {
            throw new ValidationException("status",
                "a " + status.name().toLowerCase() + " follow-up cannot be " + verb);
        }
    }

    public SubjectType getSubjectType() { return subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public Instant getDueAt() { return dueAt; }
    public UUID getAssignedTo() { return assignedTo; }
    public FollowUpStatus getStatus() { return status; }
    public String getNote() { return note; }
    public Instant getCompletedAt() { return completedAt; }
    public String getCompletionNote() { return completionNote; }
    public UUID getCreatedBy() { return createdBy; }
}
```

- [ ] **Step 5: Create `FollowUpRepository`**

`backend/src/main/java/com/easycrm/sales/FollowUpRepository.java`:

```java
package com.easycrm.sales;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/**
 * Unlike ActivityRepository, this one extends JpaRepository normally: a follow-up has its
 * own assigned_to, so it is filtered by VisibilityPolicy through VisibleFinder rather than
 * gated at a subject. Task 9 adds it to VisibilityScopingArchTest.GUARDED_REPOSITORIES,
 * after which every read here must go through VisibleFinder or the build fails.
 *
 * <p>Declare no custom finders. Any added would need a name in that test's shared
 * ALLOWED_METHODS set, which is a visibility decision requiring the same review as adding
 * a table to TenantScopingArchTest.GLOBAL_TABLES.
 */
public interface FollowUpRepository
        extends JpaRepository<FollowUp, UUID>, JpaSpecificationExecutor<FollowUp> {
}
```

- [ ] **Step 6: Create the migrations**

`backend/src/main/resources/db/migration/V29__follow_up.sql`:

```sql
CREATE TABLE follow_up (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    subject_type     VARCHAR(16) NOT NULL,
    subject_id       UUID NOT NULL,
    due_at           TIMESTAMPTZ NOT NULL,
    assigned_to      UUID NOT NULL,
    status           VARCHAR(16) NOT NULL,
    note             VARCHAR(500),
    completed_at     TIMESTAMPTZ,
    completion_note  VARCHAR(500),
    created_by       UUID,
    created_at       TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ,
    version          BIGINT NOT NULL DEFAULT 0
);

-- The dashboard's hottest query, run on every login: my pending follow-ups ordered by
-- due date. The record-visibility slice shipped its assigned_to predicate on customer and
-- enquiry with no index behind it (HANDOFF §8, "Before the first large tenant"); getting
-- it right at creation costs one line, retrofitting costs a migration on a live table.
CREATE INDEX idx_follow_up_owner_due
    ON follow_up (tenant_id, assigned_to, status, due_at);

CREATE INDEX idx_follow_up_subject
    ON follow_up (tenant_id, subject_type, subject_id);
```

`backend/src/main/resources/db/migration/V30__rls_follow_up.sql`:

```sql
ALTER TABLE follow_up ENABLE ROW LEVEL SECURITY;
ALTER TABLE follow_up FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON follow_up
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
```

- [ ] **Step 7: Run the aggregate test**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.FollowUpTest'
```

Expected: PASS, 10 tests.

- [ ] **Step 8: Run the full suite**

```bash
cd backend && ./gradlew test
```

Expected: green — `RlsCoverageIntegrationTest` and `TenantScopingArchTest` both cover the new table automatically.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/FollowUp.java \
        backend/src/main/java/com/easycrm/sales/FollowUpStatus.java \
        backend/src/main/java/com/easycrm/sales/FollowUpRepository.java \
        backend/src/main/resources/db/migration/V29__follow_up.sql \
        backend/src/main/resources/db/migration/V30__rls_follow_up.sql \
        backend/src/test/java/com/easycrm/sales/FollowUpTest.java
git commit -m "feat: add the follow-up aggregate, its table and RLS

Due-dated, always owned, polymorphic against the same four subjects as an
activity. FollowUpStatus has no OVERDUE value on purpose: overdue is a
read-time predicate, so no job exists that could fall behind and leave a
row misreporting its own state.

The (tenant_id, assigned_to, status, due_at) index ships with the table
rather than later — this is the query every login runs, and the equivalent
predicate on customer and enquiry is already unindexed."
```

---

## Task 7: `DueWindow` — IST day boundaries

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/time/DueWindow.java`
- Test: `backend/src/test/java/com/easycrm/platform/time/DueWindowTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `DueWindow.Window` (a record of `Instant startOfToday, Instant endOfToday`) and `static Window DueWindow.today(Instant now)`. Task 10's `FollowUpSpecifications` consumes `endOfToday`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/easycrm/platform/time/DueWindowTest.java`:

```java
package com.easycrm.platform.time;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Midnight correctness, proven with no Spring at all. This is why the Clock bean is never
 * overridden in an integration test: doing so would fork the context every IntegrationTest
 * subclass shares, and these edges are exactly what needed determinism. See spec
 * 2026-08-30-activity-follow-up-design.md §7.3.
 *
 * <p>IST is UTC+5:30, so an IST day runs from 18:30 UTC the previous day to 18:30 UTC.
 */
class DueWindowTest {

    @Test
    void middayIstResolvesToThatIstDay() {
        // 2026-08-30 12:00 IST == 2026-08-30 06:30 UTC
        DueWindow.Window w = DueWindow.today(Instant.parse("2026-08-30T06:30:00Z"));

        assertThat(w.startOfToday()).isEqualTo(Instant.parse("2026-08-29T18:30:00Z"));
        assertThat(w.endOfToday()).isEqualTo(Instant.parse("2026-08-30T18:30:00Z"));
    }

    @Test
    void oneSecondBeforeIstMidnightIsStillTheSameDay() {
        // 2026-08-30 23:59:59 IST == 2026-08-30 18:29:59 UTC
        DueWindow.Window w = DueWindow.today(Instant.parse("2026-08-30T18:29:59Z"));

        assertThat(w.startOfToday()).isEqualTo(Instant.parse("2026-08-29T18:30:00Z"));
        assertThat(w.endOfToday()).isEqualTo(Instant.parse("2026-08-30T18:30:00Z"));
    }

    @Test
    void istMidnightExactlyRollsToTheNextDay() {
        // 2026-08-31 00:00:00 IST == 2026-08-30 18:30:00 UTC
        DueWindow.Window w = DueWindow.today(Instant.parse("2026-08-30T18:30:00Z"));

        assertThat(w.startOfToday()).isEqualTo(Instant.parse("2026-08-30T18:30:00Z"));
        assertThat(w.endOfToday()).isEqualTo(Instant.parse("2026-08-31T18:30:00Z"));
    }

    @Test
    void aUtcInstantLateInTheEveningFallsOnTheNextIstDay() {
        // 2026-08-30 20:00 UTC == 2026-08-31 01:30 IST — the case a naive UTC-based
        // implementation gets wrong, and the reason this class exists at all.
        DueWindow.Window w = DueWindow.today(Instant.parse("2026-08-30T20:00:00Z"));

        assertThat(w.startOfToday()).isEqualTo(Instant.parse("2026-08-30T18:30:00Z"));
        assertThat(w.endOfToday()).isEqualTo(Instant.parse("2026-08-31T18:30:00Z"));
    }

    @Test
    void theWindowIsExactlyTwentyFourHours() {
        DueWindow.Window w = DueWindow.today(Instant.parse("2026-08-30T06:30:00Z"));

        assertThat(java.time.Duration.between(w.startOfToday(), w.endOfToday()))
            .isEqualTo(java.time.Duration.ofHours(24));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.platform.time.DueWindowTest'
```

Expected: FAIL to compile — `DueWindow` does not exist.

- [ ] **Step 3: Read the existing IST constant so it is not duplicated**

```bash
cd backend && sed -n '1,25p' src/main/java/com/easycrm/platform/format/IndianFormats.java
```

Confirm it declares `private static final ZoneId IST = ZoneId.of("Asia/Kolkata")`. If that field is private (it is), `DueWindow` declares its own with an explicit comment pointing at it — the spec's instruction is to not introduce a *second timezone value*, not to force a public constant into an unrelated formatting class.

- [ ] **Step 4: Create `DueWindow`**

`backend/src/main/java/com/easycrm/platform/time/DueWindow.java`:

```java
package com.easycrm.platform.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Today's boundaries in IST, as instants. Pure and static so midnight correctness can be
 * proven without Spring — see DueWindowTest and spec §7.3.
 *
 * <p>Every tenant is Indian by product definition, so the zone is a constant rather than a
 * per-tenant column; adding that column before a non-Indian tenant exists would be
 * speculative. The value matches IndianFormats' own IST field, which is private to that
 * class; this is the same zone, not a second opinion about it.
 */
public final class DueWindow {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private DueWindow() {}

    /** Half-open: {@code [startOfToday, endOfToday)}. */
    public record Window(Instant startOfToday, Instant endOfToday) {}

    public static Window today(Instant now) {
        LocalDate todayInIst = now.atZone(IST).toLocalDate();
        return new Window(
            todayInIst.atStartOfDay(IST).toInstant(),
            todayInIst.plusDays(1).atStartOfDay(IST).toInstant());
    }
}
```

- [ ] **Step 5: Run the test**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.platform.time.DueWindowTest'
```

Expected: PASS, 5 tests.

- [ ] **Step 6: Run the full suite**

```bash
cd backend && ./gradlew test
```

Expected: green.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/easycrm/platform/time/DueWindow.java \
        backend/src/test/java/com/easycrm/platform/time/DueWindowTest.java
git commit -m "feat: compute today's IST boundaries as a pure function

Splitting this out of the service is what lets the midnight edges be tested
directly — 23:59:59 IST, midnight exactly, and a UTC evening instant that
falls on the next IST day. The alternative, overriding the Clock bean in an
integration test, would fork the Spring context all 64 integration classes
share for a suite that runs in twelve seconds."
```

---

## Task 8: Extract `AssignableUsers`

**Files:**
- Create: `backend/src/main/java/com/easycrm/iam/AssignableUsers.java`
- Modify: `backend/src/main/java/com/easycrm/sales/EnquiryService.java`, `backend/src/main/java/com/easycrm/crm/CustomerService.java`
- Test: `backend/src/test/java/com/easycrm/iam/AssignableUsersTest.java`

**Interfaces:**
- Consumes: `UserRepository`, `UserStatus`, `ValidationException`.
- Produces: `void AssignableUsers.require(UUID userId)` — no-op when `userId` is null, otherwise throws `ValidationException("assignedTo", "must be an active user")` unless an `ACTIVE` user with that id exists. Consumed by `FollowUpService` (Task 10).

**Why this is in scope:** `requireAssignableUser` is currently copy-pasted verbatim into `EnquiryService` (~line 101) and `CustomerService` (~line 100). `FollowUpService` needs the same check, which would make three copies. Rule of three, and it is code this slice already has to touch. Nothing else about those two services changes.

- [ ] **Step 1: Read both existing copies to confirm they are identical**

```bash
cd backend && sed -n '/private void requireAssignableUser/,/^    }/p' \
  src/main/java/com/easycrm/sales/EnquiryService.java \
  src/main/java/com/easycrm/crm/CustomerService.java
```

Expected: two identical five-line bodies. **If they differ**, stop and reconcile the difference explicitly rather than silently picking one — a behavioural difference between them would be a latent bug this extraction would paper over.

- [ ] **Step 2: Write the failing test**

`backend/src/test/java/com/easycrm/iam/AssignableUsersTest.java`:

```java
package com.easycrm.iam;

import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Extracted from the verbatim copies in EnquiryService and CustomerService. Spec §7.4.
 */
@SpringBootTest
class AssignableUsersTest extends IntegrationTest {

    @Autowired AssignableUsers assignableUsers;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void aNullAssigneeIsAllowed() {
        asTenant(() -> assertThatCode(() -> assignableUsers.require(null))
            .doesNotThrowAnyException());
    }

    @Test
    void anUnknownUserIsRejected() {
        asTenant(() -> assertThatThrownBy(() -> assignableUsers.require(UUID.randomUUID()))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> org.assertj.core.api.Assertions
                .assertThat(((ValidationException) e).getFields()).containsKey("assignedTo")));
    }

    private void asTenant(Runnable body) {
        TenantContext.runAs(
            new TenantContext.TenantPrincipal(tenantId, UUID.randomUUID(), "OWNER"),
            () -> tx.executeWithoutResult(s -> body.run()));
    }
}
```

> **Note:** an "active user is accepted" case is deliberately not written here — creating a real `User` row requires the `iam` fixtures, and the accepted path is already covered end-to-end by the existing `EnquiryService`/`CustomerService` assignment tests, which must stay green through this refactor. Those tests are the regression guard for this task; if any of them break, the extraction changed behaviour.

- [ ] **Step 3: Run it to verify it fails**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.AssignableUsersTest'
```

Expected: FAIL to compile — `AssignableUsers` does not exist.

- [ ] **Step 4: Create `AssignableUsers`**

`backend/src/main/java/com/easycrm/iam/AssignableUsers.java`:

```java
package com.easycrm.iam;

import com.easycrm.platform.error.ValidationException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * "Is this user someone I can assign work to?" — extracted from the identical private
 * copies that had accumulated in EnquiryService and CustomerService when FollowUpService
 * needed a third. See spec 2026-08-30-activity-follow-up-design.md §7.4.
 *
 * <p>A null assignee is allowed and is a no-op: the funnel aggregates treat unassigned as
 * a legitimate pool state. FollowUp does not — it rejects a null owner in its own
 * constructor, which is a separate and stricter rule.
 */
@Component
public class AssignableUsers {

    private final UserRepository users;

    public AssignableUsers(UserRepository users) { this.users = users; }

    public void require(UUID userId) {
        if (userId == null) return;
        users.findById(userId)
            .filter(u -> u.getStatus() == UserStatus.ACTIVE)
            .orElseThrow(() -> new ValidationException("assignedTo", "must be an active user"));
    }
}
```

- [ ] **Step 5: Rewire `EnquiryService`**

Replace the `UserRepository users` field, its constructor parameter and assignment, and the private `requireAssignableUser` method with an `AssignableUsers assignableUsers` dependency. Change the two call sites from `requireAssignableUser(req.assignedTo())` to `assignableUsers.require(req.assignedTo())`. Remove the now-unused `import com.easycrm.iam.UserRepository;` and `import com.easycrm.iam.UserStatus;`, and add `import com.easycrm.iam.AssignableUsers;`.

Check whether `users` is referenced anywhere else in the class first:

```bash
cd backend && grep -n 'users' src/main/java/com/easycrm/sales/EnquiryService.java
```

If it is used for anything beyond `requireAssignableUser`, keep the field and only replace the method.

- [ ] **Step 6: Rewire `CustomerService` the same way**

```bash
cd backend && grep -n 'users' src/main/java/com/easycrm/crm/CustomerService.java
```

Same treatment.

> **Constructor arity changed on both services.** The record-visibility slice hit this exact issue (its ledger records a reviewer checking for direct-construction call sites). Verify no test or production code constructs either service manually:
>
> ```bash
> cd backend && grep -rn 'new EnquiryService(\|new CustomerService(' src/
> ```
>
> Expected: no results — both are Spring-wired everywhere.

- [ ] **Step 7: Run the affected tests**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.AssignableUsersTest' \
  --tests 'com.easycrm.sales.*Enquiry*' --tests 'com.easycrm.crm.*'
```

Expected: PASS. The pre-existing enquiry and customer assignment tests are the real regression guard here.

- [ ] **Step 8: Run the full suite**

```bash
cd backend && ./gradlew test
```

Expected: green, same total as before this task plus 2.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/easycrm/iam/AssignableUsers.java \
        backend/src/main/java/com/easycrm/sales/EnquiryService.java \
        backend/src/main/java/com/easycrm/crm/CustomerService.java \
        backend/src/test/java/com/easycrm/iam/AssignableUsersTest.java
git commit -m "refactor: extract AssignableUsers from its two copies

EnquiryService and CustomerService held identical private copies of the
active-user check; FollowUpService needs a third. Rule of three, on code
this slice has to touch anyway. Behaviour is unchanged and the existing
enquiry and customer assignment tests are the guard for that."
```

---

## Task 9: Wire follow-ups into the visibility layer

**Files:**
- Modify: `backend/src/main/java/com/easycrm/platform/visibility/VisibilityPolicy.java`, `VisibleFinder.java`
- Modify: `backend/src/test/java/com/easycrm/arch/VisibilityScopingArchTest.java`
- Create: `backend/src/main/java/com/easycrm/sales/FollowUpScope.java`, `FollowUpSpecifications.java`
- Test: `backend/src/test/java/com/easycrm/sales/FollowUpSpecificationsTest.java`

**Interfaces:**
- Consumes: `FollowUp`, `FollowUpStatus`, `FollowUpRepository` (Task 6), `DueWindow` (Task 7), `SubjectType` (Task 1).
- Produces:
  - `enum FollowUpScope { OVERDUE, DUE_TODAY, UPCOMING, ALL }`
  - `Specification<FollowUp> VisibilityPolicy.followUps()`
  - `Optional<FollowUp> VisibleFinder.findFollowUp(UUID)`, `Page<FollowUp> VisibleFinder.pageFollowUps(Specification<FollowUp>, Pageable)`
  - `Specification<FollowUp> FollowUpSpecifications.filter(FollowUpScope, FollowUpStatus, UUID assignedTo, SubjectType, UUID subjectId, Instant now, Instant endOfToday)`

- [ ] **Step 1: Create `FollowUpScope`**

`backend/src/main/java/com/easycrm/sales/FollowUpScope.java`:

```java
package com.easycrm.sales;

/**
 * The three scopes are DISJOINT and exhaustive over PENDING, which is a decision rather
 * than an accident. The naive reading — OVERDUE is due_at &lt; now, DUE_TODAY is anything
 * falling inside today — puts a 9am follow-up read at 3pm in BOTH, so the dashboard's
 * three counts would double-count and would not sum to the pending total. A tile whose
 * parts do not sum to its whole is a bug report waiting to happen.
 *
 * <p>See spec 2026-08-30-activity-follow-up-design.md §9.
 */
public enum FollowUpScope {
    /** PENDING and already past due: {@code due_at < now}. */
    OVERDUE,
    /** PENDING and still to do today: {@code now <= due_at < endOfTodayIST}. */
    DUE_TODAY,
    /** PENDING and later: {@code due_at >= endOfTodayIST}. */
    UPCOMING,
    /** No due_at predicate at all; an explicit status filter still applies. */
    ALL
}
```

- [ ] **Step 2: Write the failing specification test**

`backend/src/test/java/com/easycrm/sales/FollowUpSpecificationsTest.java`:

```java
package com.easycrm.sales;

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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The disjointness property of §9, asserted directly: three follow-ups placed on either
 * side of the two boundaries, each scope returning exactly one, and the three counts
 * summing to the pending total. This is what catches a regression to the overlapping
 * definitions the spec rejects.
 */
@SpringBootTest
class FollowUpSpecificationsTest extends IntegrationTest {

    @Autowired FollowUpRepository followUps;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID me = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();

    // Fixed clock values, not Instant.now(): the boundaries are what is under test.
    private static final Instant NOW = Instant.parse("2026-08-30T06:30:00Z");        // 12:00 IST
    private static final Instant END_OF_TODAY = Instant.parse("2026-08-30T18:30:00Z"); // 00:00 IST +1

    @BeforeEach
    void seed() {
        asTenant(() -> {
            followUps.saveAndFlush(f(NOW.minusSeconds(7200), "overdue"));    // 10:00 IST
            followUps.saveAndFlush(f(NOW.plusSeconds(7200), "due today"));   // 14:00 IST
            followUps.saveAndFlush(f(END_OF_TODAY.plusSeconds(3600), "upcoming"));
        });
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void overdueReturnsOnlyThePastOne() {
        assertThat(notesFor(FollowUpScope.OVERDUE)).containsExactly("overdue");
    }

    @Test
    void dueTodayReturnsOnlyTheRestOfTodayOne() {
        assertThat(notesFor(FollowUpScope.DUE_TODAY)).containsExactly("due today");
    }

    @Test
    void upcomingReturnsOnlyTheLaterOne() {
        assertThat(notesFor(FollowUpScope.UPCOMING)).containsExactly("upcoming");
    }

    @Test
    void theThreeScopesPartitionThePendingSet() {
        int overdue = notesFor(FollowUpScope.OVERDUE).size();
        int dueToday = notesFor(FollowUpScope.DUE_TODAY).size();
        int upcoming = notesFor(FollowUpScope.UPCOMING).size();

        assertThat(overdue + dueToday + upcoming)
            .as("the three scopes must partition PENDING exactly — see FollowUpScope")
            .isEqualTo(notesFor(FollowUpScope.ALL).size());
    }

    @Test
    void aCompletedFollowUpFallsOutOfEveryDueScope() {
        asTenant(() -> {
            FollowUp done = followUps.saveAndFlush(f(NOW.minusSeconds(7200), "finished"));
            done.complete("rang them", NOW);
            followUps.saveAndFlush(done);
        });

        assertThat(notesFor(FollowUpScope.OVERDUE)).containsExactly("overdue");
    }

    private java.util.List<String> notesFor(FollowUpScope scope) {
        return TenantContext.runAs(
            new TenantContext.TenantPrincipal(tenantId, me, "OWNER"),
            () -> tx.execute(s -> followUps.findAll(
                    FollowUpSpecifications.filter(scope, null, null, null, null,
                        NOW, END_OF_TODAY),
                    PageRequest.of(0, 50))
                .getContent().stream().map(FollowUp::getNote).toList()));
    }

    private FollowUp f(Instant dueAt, String note) {
        return new FollowUp(SubjectType.ENQUIRY, subject, dueAt, me, note, me);
    }

    private void asTenant(Runnable body) {
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, me, "OWNER"),
            () -> tx.executeWithoutResult(s -> body.run()));
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.FollowUpSpecificationsTest'
```

Expected: FAIL to compile — `FollowUpSpecifications` does not exist.

- [ ] **Step 4: Create `FollowUpSpecifications`**

`backend/src/main/java/com/easycrm/sales/FollowUpSpecifications.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.visibility.SubjectType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AND-composes whichever filters are non-null. Tenant scoping comes from RLS and
 * visibility from VisibilityPolicy — neither is expressed here.
 *
 * <p>Uses string-keyed {@code root.get(...)} like the four specification classes that
 * preceded it, so a field rename fails at runtime rather than at compile time. That is a
 * known limitation (deferred-backlog item 9), and the item's own instruction is to fix all
 * of them together or none — adding a fifth consistent class is the correct move here.
 */
public final class FollowUpSpecifications {

    private FollowUpSpecifications() {}

    public static Specification<FollowUp> filter(FollowUpScope scope, FollowUpStatus status,
                                                 UUID assignedTo, SubjectType subjectType,
                                                 UUID subjectId, Instant now,
                                                 Instant endOfToday) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (status != null)       ps.add(cb.equal(root.get("status"), status));
            if (assignedTo != null)   ps.add(cb.equal(root.get("assignedTo"), assignedTo));
            if (subjectType != null)  ps.add(cb.equal(root.get("subjectType"), subjectType));
            if (subjectId != null)    ps.add(cb.equal(root.get("subjectId"), subjectId));

            // The due scopes are inherently about PENDING work, so each one implies
            // status = PENDING. ALL adds no due_at predicate and no implied status,
            // leaving the explicit status filter above as the only one.
            if (scope != null && scope != FollowUpScope.ALL) {
                ps.add(cb.equal(root.get("status"), FollowUpStatus.PENDING));
                switch (scope) {
                    case OVERDUE -> ps.add(cb.lessThan(root.get("dueAt"), now));
                    case DUE_TODAY -> {
                        ps.add(cb.greaterThanOrEqualTo(root.get("dueAt"), now));
                        ps.add(cb.lessThan(root.get("dueAt"), endOfToday));
                    }
                    case UPCOMING -> ps.add(cb.greaterThanOrEqualTo(root.get("dueAt"), endOfToday));
                    case ALL -> { /* unreachable — guarded above */ }
                }
            }
            return cb.and(ps.toArray(new Predicate[0])); // empty -> always-true conjunction
        };
    }
}
```

- [ ] **Step 5: Run the specification test**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.FollowUpSpecificationsTest'
```

Expected: PASS, 5 tests.

- [ ] **Step 6: Add `followUps()` to `VisibilityPolicy`**

Add to `VisibilityPolicy` (and `import com.easycrm.sales.FollowUp;`):

```java
    /**
     * A follow-up carries its own owner, so its visibility is intrinsic rather than
     * derived from a subject — this is the half of the asymmetry described in spec §4.1.
     *
     * <p>Note this is NOT ownedOrUnassigned(): follow_up.assigned_to is NOT NULL, because
     * a follow-up nobody owns is precisely the failure this feature exists to prevent, so
     * the IS NULL branch the other aggregates carry would be unreachable code here.
     */
    public Specification<FollowUp> followUps() {
        if (unrestricted()) return unrestrictedSpec();
        UUID me = currentUserId();
        return (root, query, cb) -> cb.equal(root.get("assignedTo"), me);
    }
```

- [ ] **Step 7: Add the finder methods to `VisibleFinder`**

Add the `FollowUpRepository` to the constructor and field list, then:

```java
    public Optional<FollowUp> findFollowUp(UUID id) {
        return followUps.findOne(policy.followUps().and(hasId(id)));
    }

    public Page<FollowUp> pageFollowUps(Specification<FollowUp> filter, Pageable pageable) {
        return followUps.findAll(and(policy.followUps(), filter), pageable);
    }
```

> **Constructor arity changes again.** Re-run the direct-construction check:
>
> ```bash
> cd backend && grep -rn 'new VisibleFinder(' src/
> ```
>
> Expected: no results.

- [ ] **Step 8: Add `FollowUpRepository` to the ArchUnit guard**

In `VisibilityScopingArchTest`, extend `GUARDED_REPOSITORIES`:

```java
    private static final Set<String> GUARDED_REPOSITORIES = Set.of(
        "com.easycrm.crm.CustomerRepository",
        "com.easycrm.sales.EnquiryRepository",
        "com.easycrm.sales.QuotationRepository",
        "com.easycrm.sales.OrderRepository",
        "com.easycrm.sales.FollowUpRepository");
```

Add **no** new entries to `ALLOWED_METHODS` — `FollowUpRepository` declares no custom finders, so it should need none. If the build fails asking for one, that is the guard correctly reporting that a read slipped past `VisibleFinder`; fix the call site, not the allowlist.

- [ ] **Step 9: Run the guards and the visibility tests**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.arch.*' --tests 'com.easycrm.platform.visibility.*'
```

Expected: PASS.

> Note `FollowUpSpecificationsTest` calls `followUps.findAll(...)` and `saveAndFlush(...)` directly, from a **test** class. `VisibilityScopingArchTest` imports with `DO_NOT_INCLUDE_TESTS`, so test-class reads are outside its scope and this is fine. Production reads must go through `VisibleFinder`.

- [ ] **Step 10: Run the full suite**

```bash
cd backend && ./gradlew test
```

Expected: green.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/com/easycrm/platform/visibility/VisibilityPolicy.java \
        backend/src/main/java/com/easycrm/platform/visibility/VisibleFinder.java \
        backend/src/main/java/com/easycrm/sales/FollowUpScope.java \
        backend/src/main/java/com/easycrm/sales/FollowUpSpecifications.java \
        backend/src/test/java/com/easycrm/arch/VisibilityScopingArchTest.java \
        backend/src/test/java/com/easycrm/sales/FollowUpSpecificationsTest.java
git commit -m "feat: filter follow-ups by owner and scope them by due date

follow_up joins the guarded repository set with a plain assigned_to
equality rather than the ownedOrUnassigned shape the other aggregates use,
because the column is NOT NULL here and the IS NULL branch would be dead.

The three due scopes are disjoint and exhaustive over PENDING, with a test
asserting they partition the set. Under the naive definitions a 9am
follow-up read at 3pm is both overdue and due today, and the dashboard's
three counts then fail to sum to their own total."
```

---

## Task 10: `FollowUpService`, DTOs, and the read surface

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/FollowUpService.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/FollowUpController.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/FollowUpCreateRequest.java`, `FollowUpResponse.java`, `FollowUpSummaryResponse.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/FollowUpEndpointTest.java`, `backend/src/test/java/com/easycrm/sales/FollowUpVisibilityTest.java`

**Interfaces:**
- Consumes: `FollowUp` (Task 6), `DueWindow` (Task 7), `AssignableUsers` (Task 8), `VisibleFinder.findFollowUp`/`pageFollowUps`/`requireVisibleSubject`, `FollowUpSpecifications` (Task 9).
- Produces:
  - `FollowUpResponse.of(FollowUp, Instant now)` — carries a derived `overdue` boolean; used by Task 11.
  - `FollowUpService.create(FollowUpCreateRequest)`, `.get(UUID)`, `.list(...)`, `.summary()`
  - `FollowUpService.find(UUID)` (package-private helper) — used by Task 11.
  - Task 11 also adds `ActivityService.logManualForGatedCaller(SubjectType, UUID, ActivityType, String body, String outcome)`.

- [ ] **Step 1: Write the failing endpoint test**

`backend/src/test/java/com/easycrm/sales/web/FollowUpEndpointTest.java`:

```java
package com.easycrm.sales.web;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.Enquiry;
import com.easycrm.sales.EnquiryRepository;
import com.easycrm.sales.EnquirySource;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract for creating and reading follow-ups. Spec §9. */
@SpringBootTest
@AutoConfigureMockMvc
class FollowUpEndpointTest extends IntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired EnquiryRepository enquiries;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private String ownerToken;
    private UUID enquiryId;

    @BeforeEach
    void seed() {
        ownerToken = tokens.as(tenantId, ownerId, "OWNER");
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, ownerId, "OWNER"));
        tx.executeWithoutResult(s -> enquiryId = enquiries.saveAndFlush(new Enquiry(
            null, "Ramesh", "9876544001", "9876544001", null,
            EnquirySource.MANUAL, "needs bags", ownerId, null)).getId());
        TenantContext.clear();
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void createsAPendingFollowUp() throws Exception {
        mvc.perform(create(Instant.now().plusSeconds(172_800), "ring back Tuesday"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.note").value("ring back Tuesday"))
            .andExpect(jsonPath("$.assignedTo").value(ownerId.toString()))
            .andExpect(jsonPath("$.overdue").value(false));
    }

    @Test
    void aPastDueDateIsAcceptedAndReportsAsOverdue() throws Exception {
        mvc.perform(create(Instant.now().minusSeconds(172_800), "should have rung"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.overdue").value(true));
    }

    @Test
    void anInvisibleSubjectIs404() throws Exception {
        mvc.perform(post("/api/v1/follow-ups").header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","dueAt":"%s",
                     "assignedTo":"%s","note":"x"}
                    """.formatted(UUID.randomUUID(), Instant.now().plusSeconds(3600), ownerId)))
            .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownAssigneeIs422() throws Exception {
        mvc.perform(post("/api/v1/follow-ups").header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","dueAt":"%s",
                     "assignedTo":"%s","note":"x"}
                    """.formatted(enquiryId, Instant.now().plusSeconds(3600), UUID.randomUUID())))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.fields.assignedTo").exists());
    }

    @Test
    void listsOverdueSeparatelyFromUpcoming() throws Exception {
        mvc.perform(create(Instant.now().minusSeconds(172_800), "past")).andExpect(status().isCreated());
        mvc.perform(create(Instant.now().plusSeconds(172_800), "future")).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/follow-ups").param("scope", "OVERDUE")
                .header(AUTH, "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].note").value("past"))
            .andExpect(jsonPath("$.content.length()").value(1));

        mvc.perform(get("/api/v1/follow-ups").param("scope", "UPCOMING")
                .header(AUTH, "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].note").value("future"))
            .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void theSummaryCountsSumToThePendingTotal() throws Exception {
        mvc.perform(create(Instant.now().minusSeconds(172_800), "past")).andExpect(status().isCreated());
        mvc.perform(create(Instant.now().plusSeconds(172_800), "future")).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/follow-ups/summary").header(AUTH, "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.overdue").value(1))
            .andExpect(jsonPath("$.upcoming").value(1))
            .andExpect(jsonPath("$.dueToday").value(0));
    }

    @Test
    void filtersBySubject() throws Exception {
        mvc.perform(create(Instant.now().plusSeconds(3600), "on this enquiry"))
            .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/follow-ups")
                .param("subjectType", "ENQUIRY").param("subjectId", enquiryId.toString())
                .header(AUTH, "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1));
    }

    private org.springframework.test.web.servlet.RequestBuilder create(Instant dueAt, String note) {
        return post("/api/v1/follow-ups").header(AUTH, "Bearer " + ownerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"subjectType":"ENQUIRY","subjectId":"%s","dueAt":"%s",
                 "assignedTo":"%s","note":"%s"}
                """.formatted(enquiryId, dueAt, ownerId, note));
    }
}
```

The ±2-day offsets are deliberate: they are unambiguous no matter what time of day CI runs, which is the whole reason the midnight edges are tested in `DueWindowTest` instead of here (spec §7.3).

- [ ] **Step 2: Run it to verify it fails**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.web.FollowUpEndpointTest'
```

Expected: FAIL — no such route.

- [ ] **Step 3: Create the DTOs**

`backend/src/main/java/com/easycrm/sales/web/dto/FollowUpCreateRequest.java`:

```java
package com.easycrm.sales.web.dto;

import com.easycrm.platform.visibility.SubjectType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * dueAt carries no @Future constraint on purpose: "I should have called them yesterday"
 * is real and useful to record, and it lands in scope=OVERDUE where it belongs. Rejecting
 * it would only push users into entering a fake date, which is worse data (spec §7.2).
 */
public record FollowUpCreateRequest(
    @NotNull SubjectType subjectType,
    @NotNull UUID subjectId,
    @NotNull Instant dueAt,
    @NotNull UUID assignedTo,
    @Size(max = 500) String note) {}
```

`backend/src/main/java/com/easycrm/sales/web/dto/FollowUpResponse.java`:

```java
package com.easycrm.sales.web.dto;

import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.sales.FollowUp;
import com.easycrm.sales.FollowUpStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code overdue} is DERIVED at render time, not stored — see spec §3. That is the whole
 * reason this slice ships no scheduler: there is no column to fall out of date.
 */
public record FollowUpResponse(
    UUID id, SubjectType subjectType, UUID subjectId, Instant dueAt, UUID assignedTo,
    FollowUpStatus status, String note, boolean overdue, Instant completedAt,
    String completionNote, UUID createdBy, Instant createdAt) {

    public static FollowUpResponse of(FollowUp f, Instant now) {
        boolean overdue = f.getStatus() == FollowUpStatus.PENDING && f.getDueAt().isBefore(now);
        return new FollowUpResponse(f.getId(), f.getSubjectType(), f.getSubjectId(),
            f.getDueAt(), f.getAssignedTo(), f.getStatus(), f.getNote(), overdue,
            f.getCompletedAt(), f.getCompletionNote(), f.getCreatedBy(), f.getCreatedAt());
    }
}
```

`backend/src/main/java/com/easycrm/sales/web/dto/FollowUpSummaryResponse.java`:

```java
package com.easycrm.sales.web.dto;

/**
 * The dashboard tile. These three ALWAYS sum to the caller's total pending follow-ups,
 * because FollowUpScope's three due scopes partition PENDING exactly (spec §9).
 */
public record FollowUpSummaryResponse(long overdue, long dueToday, long upcoming) {}
```

- [ ] **Step 4: Create `FollowUpService`**

`backend/src/main/java/com/easycrm/sales/FollowUpService.java`:

```java
package com.easycrm.sales;

import com.easycrm.iam.AssignableUsers;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.time.DueWindow;
import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.platform.visibility.VisibleFinder;
import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.web.dto.FollowUpCreateRequest;
import com.easycrm.sales.web.dto.FollowUpResponse;
import com.easycrm.sales.web.dto.FollowUpSummaryResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class FollowUpService {

    private final FollowUpRepository followUps;
    private final VisibleFinder finder;
    private final AssignableUsers assignableUsers;
    private final Clock clock;

    public FollowUpService(FollowUpRepository followUps, VisibleFinder finder,
                           AssignableUsers assignableUsers, Clock clock) {
        this.followUps = followUps;
        this.finder = finder;
        this.assignableUsers = assignableUsers;
        this.clock = clock;
    }

    @Transactional
    public FollowUpResponse create(FollowUpCreateRequest req) {
        finder.requireVisibleSubject(req.subjectType(), req.subjectId());
        assignableUsers.require(req.assignedTo());
        FollowUp saved = followUps.save(new FollowUp(req.subjectType(), req.subjectId(),
            req.dueAt(), req.assignedTo(), req.note(), currentUserId()));
        return FollowUpResponse.of(saved, clock.instant());
    }

    @Transactional(readOnly = true)
    public FollowUpResponse get(UUID id) {
        return FollowUpResponse.of(find(id), clock.instant());
    }

    @Transactional(readOnly = true)
    public PageResponse<FollowUpResponse> list(FollowUpScope scope, FollowUpStatus status,
                                               UUID assignedTo, SubjectType subjectType,
                                               UUID subjectId, Pageable pageable) {
        Instant now = clock.instant();
        Instant endOfToday = DueWindow.today(now).endOfToday();
        return PageResponse.of(finder.pageFollowUps(
                FollowUpSpecifications.filter(scope, status, assignedTo, subjectType,
                    subjectId, now, endOfToday),
                pageable)
            .map(f -> FollowUpResponse.of(f, now)));
    }

    /**
     * The dashboard tile. Counts are taken through pageFollowUps rather than a count query
     * so they pass through exactly the same visibility filter as the lists they summarise
     * — a summary that counted rows the list would not show is worse than no summary.
     */
    @Transactional(readOnly = true)
    public FollowUpSummaryResponse summary() {
        Instant now = clock.instant();
        Instant endOfToday = DueWindow.today(now).endOfToday();
        return new FollowUpSummaryResponse(
            countIn(FollowUpScope.OVERDUE, now, endOfToday),
            countIn(FollowUpScope.DUE_TODAY, now, endOfToday),
            countIn(FollowUpScope.UPCOMING, now, endOfToday));
    }

    private long countIn(FollowUpScope scope, Instant now, Instant endOfToday) {
        return finder.pageFollowUps(
            FollowUpSpecifications.filter(scope, null, null, null, null, now, endOfToday),
            PageRequest.of(0, 1)).getTotalElements();
    }

    /** Visibility-filtered load; 404 when the caller may not see it. Used by transitions. */
    FollowUp find(UUID id) {
        return finder.findFollowUp(id)
            .orElseThrow(() -> new NotFoundException("follow-up " + id + " was not found"));
    }

    private static UUID currentUserId() {
        return TenantContext.get().map(TenantContext.TenantPrincipal::userId).orElse(null);
    }
}
```

- [ ] **Step 5: Create `FollowUpController`**

`backend/src/main/java/com/easycrm/sales/web/FollowUpController.java`:

```java
package com.easycrm.sales.web;

import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.FollowUpScope;
import com.easycrm.sales.FollowUpService;
import com.easycrm.sales.FollowUpStatus;
import com.easycrm.sales.web.dto.FollowUpCreateRequest;
import com.easycrm.sales.web.dto.FollowUpResponse;
import com.easycrm.sales.web.dto.FollowUpSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/follow-ups")
public class FollowUpController {

    private final FollowUpService service;

    public FollowUpController(FollowUpService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<FollowUpResponse> create(
            @Valid @RequestBody FollowUpCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    public FollowUpResponse get(@PathVariable UUID id) { return service.get(id); }

    /** Unlike activities, this list needs no subject: a follow-up is filtered by owner. */
    @GetMapping
    public PageResponse<FollowUpResponse> list(
            @RequestParam(required = false) FollowUpScope scope,
            @RequestParam(required = false) FollowUpStatus status,
            @RequestParam(required = false) UUID assignedTo,
            @RequestParam(required = false) SubjectType subjectType,
            @RequestParam(required = false) UUID subjectId,
            Pageable pageable) {
        return service.list(scope, status, assignedTo, subjectType, subjectId, pageable);
    }

    @GetMapping("/summary")
    public FollowUpSummaryResponse summary() { return service.summary(); }
}
```

> **Route ordering:** `/summary` and `/{id}` both match `GET /api/v1/follow-ups/summary`. Spring's `PathPattern` matching prefers the literal segment over the template, so `/summary` wins — but the endpoint test's `theSummaryCountsSumToThePendingTotal` case is what proves it. If that test returns 400 (failed `UUID` conversion of `"summary"`), the ordering assumption is wrong; fix it by moving `summary` to a distinct path rather than by reordering methods.

- [ ] **Step 6: Run the endpoint test**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.web.FollowUpEndpointTest'
```

Expected: PASS, 7 tests.

- [ ] **Step 7: Write the visibility test**

`backend/src/test/java/com/easycrm/sales/FollowUpVisibilityTest.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A follow-up assigned to someone else must be invisible to a SALES_EXEC. Spec §4.1. */
@SpringBootTest
@AutoConfigureMockMvc
class FollowUpVisibilityTest extends IntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired FollowUpRepository followUps;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID execAId = UUID.randomUUID();
    private final UUID execBId = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();

    private String execAToken, ownerToken;
    private UUID mine, theirs;

    @BeforeEach
    void seed() {
        execAToken = tokens.as(tenantId, execAId, "SALES_EXEC");
        ownerToken = tokens.as(tenantId, UUID.randomUUID(), "OWNER");
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, execAId, "OWNER"),
            () -> tx.executeWithoutResult(s -> {
                mine = followUps.saveAndFlush(new FollowUp(SubjectType.ENQUIRY, subject,
                    Instant.now().plusSeconds(3600), execAId, "mine", execAId)).getId();
                theirs = followUps.saveAndFlush(new FollowUp(SubjectType.ENQUIRY, subject,
                    Instant.now().plusSeconds(3600), execBId, "theirs", execBId)).getId();
            }));
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void execSeesTheirOwnFollowUp() throws Exception {
        mvc.perform(get("/api/v1/follow-ups/" + mine).header(AUTH, "Bearer " + execAToken))
            .andExpect(status().isOk());
    }

    @Test
    void execCannotSeeAnotherExecsFollowUp() throws Exception {
        mvc.perform(get("/api/v1/follow-ups/" + theirs).header(AUTH, "Bearer " + execAToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void execsListOmitsAnotherExecsFollowUp() throws Exception {
        mvc.perform(get("/api/v1/follow-ups").header(AUTH, "Bearer " + execAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].note").value("mine"));
    }

    @Test
    void ownerSeesBoth() throws Exception {
        mvc.perform(get("/api/v1/follow-ups").header(AUTH, "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void execsSummaryCountsOnlyTheirOwn() throws Exception {
        mvc.perform(get("/api/v1/follow-ups/summary").header(AUTH, "Bearer " + execAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.upcoming").value(1));
    }
}
```

`execsSummaryCountsOnlyTheirOwn` is the one that would catch a summary implemented with a raw count query that skipped `VisibleFinder`.

- [ ] **Step 8: Run it**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.FollowUpVisibilityTest'
```

Expected: PASS, 5 tests.

- [ ] **Step 9: Run the full suite**

```bash
cd backend && ./gradlew test
```

Expected: green.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/FollowUpService.java \
        backend/src/main/java/com/easycrm/sales/web/FollowUpController.java \
        backend/src/main/java/com/easycrm/sales/web/dto/FollowUpCreateRequest.java \
        backend/src/main/java/com/easycrm/sales/web/dto/FollowUpResponse.java \
        backend/src/main/java/com/easycrm/sales/web/dto/FollowUpSummaryResponse.java \
        backend/src/test/java/com/easycrm/sales/web/FollowUpEndpointTest.java \
        backend/src/test/java/com/easycrm/sales/FollowUpVisibilityTest.java
git commit -m "feat: create and read follow-ups, with a dashboard summary

The overdue flag is derived per response rather than stored, so the read
path is the reminder — no scheduler, and nothing that can go stale.

Summary counts run through pageFollowUps rather than a count query so they
pass the same visibility filter as the lists they summarise; a tile
counting rows the list will not show is worse than no tile, and a test
asserts a sales exec's summary counts only their own."
```

---

## Task 11: Follow-up transitions — complete, cancel, reschedule

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/FollowUpUpdateRequest.java`, `FollowUpCompleteRequest.java`, `FollowUpCancelRequest.java`
- Modify: `backend/src/main/java/com/easycrm/sales/FollowUpService.java`, `backend/src/main/java/com/easycrm/sales/web/FollowUpController.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/FollowUpTransitionEndpointTest.java`

**Interfaces:**
- Consumes: `FollowUp.complete/cancel/reschedule` (Task 6), `FollowUpService.find` (Task 10), `ActivityService` (Task 4), `AssignableUsers` (Task 8).
- Produces: `FollowUpService.update(UUID, FollowUpUpdateRequest)`, `.complete(UUID, FollowUpCompleteRequest)`, `.cancel(UUID, String reason)`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/easycrm/sales/web/FollowUpTransitionEndpointTest.java`:

```java
package com.easycrm.sales.web;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.Enquiry;
import com.easycrm.sales.EnquiryRepository;
import com.easycrm.sales.EnquirySource;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §6.2 (complete-and-log) and §7.2 (transition guards). */
@SpringBootTest
@AutoConfigureMockMvc
class FollowUpTransitionEndpointTest extends IntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired EnquiryRepository enquiries;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private String ownerToken;
    private UUID enquiryId;

    @BeforeEach
    void seed() {
        ownerToken = tokens.as(tenantId, ownerId, "OWNER");
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, ownerId, "OWNER"));
        tx.executeWithoutResult(s -> enquiryId = enquiries.saveAndFlush(new Enquiry(
            null, "Ramesh", "9876555001", "9876555001", null,
            EnquirySource.MANUAL, "needs bags", ownerId, null)).getId());
        TenantContext.clear();
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void completingMarksItDone() throws Exception {
        UUID id = newFollowUp();

        mvc.perform(post("/api/v1/follow-ups/" + id + "/complete")
                .header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"note":"rang, sending a revised quote"}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DONE"))
            .andExpect(jsonPath("$.completionNote").value("rang, sending a revised quote"))
            .andExpect(jsonPath("$.overdue").value(false))
            .andExpect(jsonPath("$.completedAt").exists());
    }

    @Test
    void completingTwiceIs422() throws Exception {
        UUID id = newFollowUp();
        complete(id, "done").andExpect(status().isOk());

        complete(id, "again")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.fields.status").exists());
    }

    @Test
    void completingWithAnActivityWritesItToTheSameSubject() throws Exception {
        UUID id = newFollowUp();

        mvc.perform(post("/api/v1/follow-ups/" + id + "/complete")
                .header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"note":"closed it","type":"CALL","body":"rang them back",
                     "outcome":"agreed on price"}
                    """))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/activities")
                .param("subjectType", "ENQUIRY").param("subjectId", enquiryId.toString())
                .header(AUTH, "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].body").value("rang them back"))
            .andExpect(jsonPath("$.content[0].type").value("CALL"));
    }

    @Test
    void completingWithoutAnActivityWritesNone() throws Exception {
        UUID id = newFollowUp();
        complete(id, "just closing it").andExpect(status().isOk());

        mvc.perform(get("/api/v1/activities")
                .param("subjectType", "ENQUIRY").param("subjectId", enquiryId.toString())
                .header(AUTH, "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void cancellingRequiresAReason() throws Exception {
        UUID id = newFollowUp();

        mvc.perform(post("/api/v1/follow-ups/" + id + "/cancel")
                .header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"   "}"""))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.fields.reason").exists());
    }

    @Test
    void cancellingWithAReasonWorks() throws Exception {
        UUID id = newFollowUp();

        mvc.perform(post("/api/v1/follow-ups/" + id + "/cancel")
                .header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"went with a competitor"}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.completionNote").value("went with a competitor"));
    }

    @Test
    void reschedulingMovesTheDueDate() throws Exception {
        UUID id = newFollowUp();
        Instant newDue = Instant.now().plusSeconds(432_000);

        mvc.perform(patch("/api/v1/follow-ups/" + id)
                .header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"dueAt":"%s","assignedTo":"%s","note":"pushed a week"}
                    """.formatted(newDue, ownerId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.note").value("pushed a week"));
    }

    @Test
    void reschedulingACompletedFollowUpIs422() throws Exception {
        UUID id = newFollowUp();
        complete(id, "done").andExpect(status().isOk());

        mvc.perform(patch("/api/v1/follow-ups/" + id)
                .header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"dueAt":"%s","assignedTo":"%s","note":"revived"}
                    """.formatted(Instant.now().plusSeconds(3600), ownerId)))
            .andExpect(status().isUnprocessableEntity());
    }

    private org.springframework.test.web.servlet.ResultActions complete(UUID id, String note)
            throws Exception {
        return mvc.perform(post("/api/v1/follow-ups/" + id + "/complete")
            .header(AUTH, "Bearer " + ownerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"" + note + "\"}"));
    }

    private UUID newFollowUp() throws Exception {
        String body = mvc.perform(post("/api/v1/follow-ups")
                .header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","dueAt":"%s",
                     "assignedTo":"%s","note":"ring back"}
                    """.formatted(enquiryId, Instant.now().plusSeconds(172_800), ownerId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asText());
    }
}
```

`completingWithoutAnActivityWritesNone` is the negative control for the optional activity — without it, `completingWithAnActivityWritesItToTheSameSubject` would also pass an implementation that logged unconditionally.

- [ ] **Step 2: Run it to verify it fails**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.web.FollowUpTransitionEndpointTest'
```

Expected: FAIL — no such routes.

- [ ] **Step 3: Create the three DTOs**

`backend/src/main/java/com/easycrm/sales/web/dto/FollowUpUpdateRequest.java`:

```java
package com.easycrm.sales.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Full-header-replace, per the house convention shared with EnquiryController.patch and
 * QuotationController.patch: an omitted nullable field is CLEARED, not preserved. The
 * subject is not editable — moving a follow-up to a different enquiry is a new follow-up.
 */
public record FollowUpUpdateRequest(
    @NotNull Instant dueAt,
    @NotNull UUID assignedTo,
    @Size(max = 500) String note) {}
```

`backend/src/main/java/com/easycrm/sales/web/dto/FollowUpCompleteRequest.java`:

```java
package com.easycrm.sales.web.dto;

import com.easycrm.sales.ActivityType;
import jakarta.validation.constraints.Size;

/**
 * The optional activity is the mirror of the log-and-schedule flow: closing a task and
 * recording what happened are one user intention (spec §6.2). An activity is written only
 * when {@code type} is present.
 */
public record FollowUpCompleteRequest(
    @Size(max = 500) String note,
    ActivityType type,
    @Size(max = 2000) String body,
    @Size(max = 200) String outcome) {}
```

`backend/src/main/java/com/easycrm/sales/web/dto/FollowUpCancelRequest.java`:

```java
package com.easycrm.sales.web.dto;

import jakarta.validation.constraints.Size;

/**
 * The reason is NOT annotated @NotBlank here: the aggregate rejects a blank one, and
 * letting it do so keeps the invariant in one place and yields the same 422 with the same
 * field key. Mirrors how Order.cancel handles its own reason.
 */
public record FollowUpCancelRequest(@Size(max = 500) String reason) {}
```

- [ ] **Step 4: Add the transitions to `FollowUpService`**

Add an `ActivityService activities` dependency to the constructor, then append:

```java
    @Transactional
    public FollowUpResponse update(UUID id, FollowUpUpdateRequest req) {
        FollowUp f = find(id);
        assignableUsers.require(req.assignedTo());
        f.reschedule(req.dueAt(), req.assignedTo(), req.note());
        return FollowUpResponse.of(f, clock.instant());
    }

    /**
     * Completes the follow-up and, when the request carries an activity type, logs that
     * activity against the follow-up's OWN subject in the same transaction (spec §6.2).
     *
     * <p>The activity is MANUAL, not SYSTEM: a user typed that body, so they must be able
     * to correct it later, and a SYSTEM row is permanently uneditable. It goes through
     * logManualForGatedCaller rather than the normal create path because the subject does
     * not need re-resolving — find(id) above already loaded this row through VisibleFinder,
     * and the follow-up's subject was itself gated when the row was created.
     */
    @Transactional
    public FollowUpResponse complete(UUID id, FollowUpCompleteRequest req) {
        FollowUp f = find(id);
        Instant now = clock.instant();
        f.complete(req.note(), now);
        if (req.type() != null) {
            activities.logManualForGatedCaller(f.getSubjectType(), f.getSubjectId(),
                req.type(), req.body(), req.outcome());
        }
        return FollowUpResponse.of(f, now);
    }

    @Transactional
    public FollowUpResponse cancel(UUID id, String reason) {
        FollowUp f = find(id);
        Instant now = clock.instant();
        f.cancel(reason, now);
        return FollowUpResponse.of(f, now);
    }
```

Add imports for the three DTOs.

- [ ] **Step 4b: Add `logManualForGatedCaller` to `ActivityService`**

`complete` must NOT use `logSystem`. A completion body is something a user typed, and a `SYSTEM` row is permanently uneditable (`Activity.edit` rejects it outright) — so a user could never fix a typo in their own note. The spec left this implicit; resolve it as MANUAL. Append to `ActivityService`:

```java
    /**
     * A MANUAL activity written on behalf of a caller that has ALREADY passed the subject
     * gate — currently only FollowUpService.complete, which loaded its follow-up through
     * VisibleFinder, whose subject was gated when that row was created.
     *
     * <p>Distinct from logSystem in exactly one way that matters: these rows are editable,
     * because a human wrote them. Any new caller must be able to make the same
     * already-gated claim; if it cannot, it wants create() and the full gate.
     */
    @Transactional
    public void logManualForGatedCaller(SubjectType subjectType, UUID subjectId,
                                        ActivityType type, String body, String outcome) {
        Instant now = clock.instant();
        activities.save(Activity.manual(subjectType, subjectId, type, body, outcome,
            now, currentUserId(), now));
    }
```

The endpoint test above asserts `type` and `body` only, so it would pass against `logSystem` too. The reason to get this right is not the test — it is that a `SYSTEM` row cannot be corrected, and the user typed it.

- [ ] **Step 5: Add the routes to `FollowUpController`**

```java
    /** Full-header-replace; an omitted nullable field is cleared. Pending only. */
    @PatchMapping("/{id}")
    public FollowUpResponse update(@PathVariable UUID id,
                                   @Valid @RequestBody FollowUpUpdateRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/complete")
    public FollowUpResponse complete(@PathVariable UUID id,
                                     @Valid @RequestBody FollowUpCompleteRequest req) {
        return service.complete(id, req);
    }

    @PostMapping("/{id}/cancel")
    public FollowUpResponse cancel(@PathVariable UUID id,
                                   @Valid @RequestBody FollowUpCancelRequest req) {
        return service.cancel(id, req.reason());
    }
```

Add imports: `PatchMapping`, and the three DTO types.

- [ ] **Step 6: Run the transition test**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.web.FollowUpTransitionEndpointTest'
```

Expected: PASS, 8 tests.

- [ ] **Step 7: Run the full suite**

```bash
cd backend && ./gradlew test
```

Expected: green.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/FollowUpService.java \
        backend/src/main/java/com/easycrm/sales/ActivityService.java \
        backend/src/main/java/com/easycrm/sales/web/FollowUpController.java \
        backend/src/main/java/com/easycrm/sales/web/dto/FollowUpUpdateRequest.java \
        backend/src/main/java/com/easycrm/sales/web/dto/FollowUpCompleteRequest.java \
        backend/src/main/java/com/easycrm/sales/web/dto/FollowUpCancelRequest.java \
        backend/src/test/java/com/easycrm/sales/web/FollowUpTransitionEndpointTest.java
git commit -m "feat: complete, cancel and reschedule a follow-up

Completing optionally logs an activity against the follow-up's own subject
in the same transaction, because closing a task and recording what happened
are one intention. That activity is MANUAL, not SYSTEM — a user typed it,
so they must be able to correct it.

Every guard runs before any assignment, and the tests assert the row is
left unmutated after a rejected transition rather than only that it threw."
```

---

## Task 12: Log-and-schedule in one request

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/NextFollowUpRequest.java`
- Modify: `backend/src/main/java/com/easycrm/sales/web/dto/ActivityCreateRequest.java`, `backend/src/main/java/com/easycrm/sales/ActivityService.java`, `ActivityResponse.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/LogAndScheduleEndpointTest.java`

**Interfaces:**
- Consumes: `ActivityService.create` (Task 4), `FollowUp` + `FollowUpRepository` (Task 6), `AssignableUsers` (Task 8).
- Produces: `ActivityCreateRequest.nextFollowUp()` and `ActivityResponse.followUpId()` (nullable).

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/easycrm/sales/web/LogAndScheduleEndpointTest.java`:

```java
package com.easycrm.sales.web;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.Enquiry;
import com.easycrm.sales.EnquiryRepository;
import com.easycrm.sales.EnquirySource;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec §6.1 — the product's actual moment: a trader ends a call and needs "logged it,
 * ringing them Tuesday" to be one tap on patchy 4G, not two round-trips of which the
 * second can fail.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LogAndScheduleEndpointTest extends IntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired EnquiryRepository enquiries;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private String ownerToken;
    private UUID enquiryId;

    @BeforeEach
    void seed() {
        ownerToken = tokens.as(tenantId, ownerId, "OWNER");
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, ownerId, "OWNER"));
        tx.executeWithoutResult(s -> enquiryId = enquiries.saveAndFlush(new Enquiry(
            null, "Ramesh", "9876566001", "9876566001", null,
            EnquirySource.MANUAL, "needs bags", ownerId, null)).getId());
        TenantContext.clear();
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void oneRequestWritesBothTheActivityAndTheFollowUp() throws Exception {
        mvc.perform(post("/api/v1/activities").header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","type":"CALL",
                     "body":"rang them","outcome":"wants a revised rate",
                     "nextFollowUp":{"dueAt":"%s","assignedTo":"%s","note":"ring Tuesday"}}
                    """.formatted(enquiryId, Instant.now().plusSeconds(172_800), ownerId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.followUpId").exists());

        mvc.perform(get("/api/v1/activities")
                .param("subjectType", "ENQUIRY").param("subjectId", enquiryId.toString())
                .header(AUTH, "Bearer " + ownerToken))
            .andExpect(jsonPath("$.content.length()").value(1));

        mvc.perform(get("/api/v1/follow-ups")
                .param("subjectType", "ENQUIRY").param("subjectId", enquiryId.toString())
                .header(AUTH, "Bearer " + ownerToken))
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].note").value("ring Tuesday"));
    }

    @Test
    void omittingNextFollowUpWritesOnlyTheActivity() throws Exception {
        mvc.perform(post("/api/v1/activities").header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","type":"CALL","body":"rang"}
                    """.formatted(enquiryId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.followUpId").doesNotExist());

        mvc.perform(get("/api/v1/follow-ups")
                .param("subjectType", "ENQUIRY").param("subjectId", enquiryId.toString())
                .header(AUTH, "Bearer " + ownerToken))
            .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void aBadAssigneeRollsBackTheActivityToo() throws Exception {
        mvc.perform(post("/api/v1/activities").header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","type":"CALL","body":"rang",
                     "nextFollowUp":{"dueAt":"%s","assignedTo":"%s","note":"x"}}
                    """.formatted(enquiryId, Instant.now().plusSeconds(3600), UUID.randomUUID())))
            .andExpect(status().isUnprocessableEntity());

        // Atomicity: the activity must NOT have been written. This is the whole point of
        // doing both in one transaction (spec §6.1).
        mvc.perform(get("/api/v1/activities")
                .param("subjectType", "ENQUIRY").param("subjectId", enquiryId.toString())
                .header(AUTH, "Bearer " + ownerToken))
            .andExpect(jsonPath("$.content.length()").value(0));
    }
}
```

`aBadAssigneeRollsBackTheActivityToo` is the load-bearing test — it is what proves "one transaction" rather than "two writes in a row".

- [ ] **Step 2: Run it to verify it fails**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.web.LogAndScheduleEndpointTest'
```

Expected: FAIL — `nextFollowUp` is ignored, `followUpId` absent, no follow-up written.

- [ ] **Step 3: Create `NextFollowUpRequest`**

`backend/src/main/java/com/easycrm/sales/web/dto/NextFollowUpRequest.java`:

```java
package com.easycrm.sales.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * The nested half of the log-and-schedule flow (spec §6.1). No subject: it is always the
 * enclosing activity's subject, which has already been resolved once — one gate, one 404
 * decision, and no window in which the two rows could disagree about what they hang off.
 */
public record NextFollowUpRequest(
    @NotNull Instant dueAt,
    @NotNull UUID assignedTo,
    @Size(max = 500) String note) {}
```

- [ ] **Step 4: Add the field to `ActivityCreateRequest`**

Add `@Valid NextFollowUpRequest nextFollowUp` as the final component, and `import jakarta.validation.Valid;`. `@Valid` on a nested record is required or its constraints are not evaluated.

- [ ] **Step 5: Add `followUpId` to `ActivityResponse`**

Add a `UUID followUpId` component and a second factory, keeping the existing one delegating:

```java
    public static ActivityResponse of(Activity a) { return of(a, null); }

    public static ActivityResponse of(Activity a, UUID followUpId) {
        return new ActivityResponse(a.getId(), a.getSubjectType(), a.getSubjectId(),
            a.getType(), a.getBody(), a.getOutcome(), a.getOccurredAt(),
            a.getLoggedBy(), a.getSource(), a.getCreatedAt(), followUpId);
    }
```

Annotate the record with `@JsonInclude(JsonInclude.Include.NON_NULL)` so `followUpId` is absent rather than `null` when unused — the test asserts `doesNotExist()`. Import `com.fasterxml.jackson.annotation.JsonInclude`.

> **Check this does not break Task 4's and Task 5's assertions.** `NON_NULL` also hides a null `outcome`. `ActivityEndpointTest` never asserts `$.outcome` is null, and `ActivityEditEndpointTest` asserts it only when set — verified against those tests as written. If a future assertion needs an explicit null, scope the annotation to the field instead of the record.

- [ ] **Step 6: Extend `ActivityService.create`**

Inject `FollowUpRepository followUps` and `AssignableUsers assignableUsers`, then:

```java
    /**
     * The subject is resolved ONCE and reused for both rows, and both are written in one
     * transaction: two round-trips means the second can fail somewhere and the follow-up —
     * the half this whole feature exists to protect — is what goes missing (spec §6.1).
     */
    @Transactional
    public ActivityResponse create(ActivityCreateRequest req) {
        finder.requireVisibleSubject(req.subjectType(), req.subjectId());
        Instant now = clock.instant();
        Instant occurredAt = req.occurredAt() == null ? now : req.occurredAt();
        Activity saved = activities.save(Activity.manual(
            req.subjectType(), req.subjectId(), req.type(), req.body(), req.outcome(),
            occurredAt, currentUserId(), now));

        UUID followUpId = null;
        NextFollowUpRequest next = req.nextFollowUp();
        if (next != null) {
            assignableUsers.require(next.assignedTo());
            followUpId = followUps.save(new FollowUp(req.subjectType(), req.subjectId(),
                next.dueAt(), next.assignedTo(), next.note(), currentUserId())).getId();
        }
        return ActivityResponse.of(saved, followUpId);
    }
```

> `ActivityService` now writes through `FollowUpRepository`, which Task 9 added to `GUARDED_REPOSITORIES`. `save` is already in `ALLOWED_METHODS`, so this is permitted and no allowlist change is needed. If the ArchUnit test fails here, something other than `save` is being called — fix the call, not the allowlist.

- [ ] **Step 7: Run the test**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.web.LogAndScheduleEndpointTest'
```

Expected: PASS, 3 tests.

- [ ] **Step 8: Run the full suite**

```bash
cd backend && ./gradlew test
```

Expected: green — pay attention to `ActivityEndpointTest` and `ActivityEditEndpointTest`, which the `ActivityResponse` change touches.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/ActivityService.java \
        backend/src/main/java/com/easycrm/sales/web/dto/ActivityCreateRequest.java \
        backend/src/main/java/com/easycrm/sales/web/dto/ActivityResponse.java \
        backend/src/main/java/com/easycrm/sales/web/dto/NextFollowUpRequest.java \
        backend/src/test/java/com/easycrm/sales/web/LogAndScheduleEndpointTest.java
git commit -m "feat: log a call and schedule the next follow-up in one request

The product's actual moment: a trader ends a call on a cheap Android over
patchy 4G, and 'logged it, ringing them Tuesday' has to be one tap. Two
round-trips means the second one fails somewhere and the follow-up is the
half that goes missing.

Both rows are written in one transaction against a subject resolved once,
and the test that matters is the rollback one — a bad assignee must take
the activity down with it, or this is just two writes in a row."
```

---

## Task 13: A `SYSTEM` activity on quotation acceptance

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/QuotationAcceptedActivityListener.java`
- Test: `backend/src/test/java/com/easycrm/sales/QuotationAcceptedActivityTest.java`

**Interfaces:**
- Consumes: `QuotationAcceptedEvent` (existing), `ActivityService.logSystem` (Task 4).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Read the existing listener and event to match their shape**

```bash
cd backend && cat src/main/java/com/easycrm/sales/OrderAcceptedAuditListener.java \
                  src/main/java/com/easycrm/sales/QuotationAcceptedEvent.java
```

Confirm the event's components: `quotationId`, `orderId`, `quotationVersionId`, `grandTotal`, `orderNo`, `actorUserId`.

- [ ] **Step 2: Write the failing test**

`backend/src/test/java/com/easycrm/sales/QuotationAcceptedActivityTest.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §6.3. The parent design spec's claim about the event seam is that new behaviour
 * arrives as a new SUBSCRIBER rather than an edit to QuotationService — this is the first
 * time that claim is tested by someone other than its author.
 */
@SpringBootTest
class QuotationAcceptedActivityTest extends IntegrationTest {

    @Autowired ApplicationEventPublisher events;
    @Autowired ActivityRepository activities;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID quotationId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void acceptingAQuotationLogsExactlyOneSystemActivityAgainstIt() {
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, actorId, "OWNER"),
            () -> tx.executeWithoutResult(s -> events.publishEvent(new QuotationAcceptedEvent(
                quotationId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("11800.00"), "SO/25-26/0007", actorId))));

        var logged = TenantContext.runAs(
            new TenantContext.TenantPrincipal(tenantId, actorId, "OWNER"),
            () -> tx.execute(s -> activities
                .findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                    SubjectType.QUOTATION, quotationId, PageRequest.of(0, 10))
                .getContent()));

        assertThat(logged).hasSize(1);
        assertThat(logged.get(0).getSource()).isEqualTo(ActivitySource.SYSTEM);
        assertThat(logged.get(0).getType()).isEqualTo(ActivityType.NOTE);
        assertThat(logged.get(0).getLoggedBy()).isEqualTo(actorId);
        assertThat(logged.get(0).getBody()).contains("SO/25-26/0007");
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.QuotationAcceptedActivityTest'
```

Expected: FAIL — `hasSize(1)` sees 0; no listener exists.

- [ ] **Step 4: Create the listener**

`backend/src/main/java/com/easycrm/sales/QuotationAcceptedActivityListener.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.visibility.SubjectType;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Records quotation acceptance on the quotation's own timeline. Sits beside
 * OrderAcceptedAuditListener and is wired the same way — synchronous, in the publisher's
 * transaction (Spring's default), so the activity commits or rolls back with the order
 * exactly as the audit row does (challenge #3).
 *
 * <p>The parent design spec promised that new behaviour on acceptance arrives as a new
 * subscriber rather than an edit to QuotationService. This class is that promise being
 * collected: QuotationService is not touched. See spec
 * 2026-08-30-activity-follow-up-design.md §6.3.
 */
@Component
public class QuotationAcceptedActivityListener {

    private final ActivityService activities;

    public QuotationAcceptedActivityListener(ActivityService activities) {
        this.activities = activities;
    }

    @EventListener
    public void on(QuotationAcceptedEvent e) {
        activities.logSystem(SubjectType.QUOTATION, e.quotationId(), ActivityType.NOTE,
            "Quotation accepted — order " + e.orderNo() + " created for Rs. "
                + e.grandTotal().toPlainString(),
            e.actorUserId());
    }
}
```

- [ ] **Step 5: Run the test**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.sales.QuotationAcceptedActivityTest'
```

Expected: PASS, 1 test.

- [ ] **Step 6: Confirm `QuotationService` was not modified**

```bash
cd backend && git diff main --stat -- src/main/java/com/easycrm/sales/QuotationService.java
```

Expected: **no output.** If `QuotationService` appears, the event seam was bypassed — revert and use the listener.

- [ ] **Step 7: Run the full suite**

```bash
cd backend && ./gradlew test
```

Expected: green. The existing quotation-accept tests now also produce an activity row; confirm none of them assert on an empty activity table (none should — the table is new).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/QuotationAcceptedActivityListener.java \
        backend/src/test/java/com/easycrm/sales/QuotationAcceptedActivityTest.java
git commit -m "feat: log a system activity when a quotation is accepted

Sits beside OrderAcceptedAuditListener on the same event, synchronous and
in the publisher's transaction. The design spec promised that behaviour
added on acceptance would arrive as a new subscriber rather than an edit to
QuotationService; this is the first time that claim has been collected by
someone other than its author, and it holds — QuotationService is untouched,
which a step in this task checks explicitly."
```

---

## Task 14: Documentation obligations

**Files:**
- Modify: `docs/superpowers/engineering-challenges.md`, `docs/superpowers/annotations-reference.md`, `docs/superpowers/HANDOFF.md`

**Interfaces:** none — this is the `CLAUDE.md`-mandated docs pass, done as part of the slice rather than "later".

- [ ] **Step 1: Read the challenge-log template and the last two entries**

```bash
cd /Users/divyam/Documents/easy-crm && tail -60 docs/superpowers/engineering-challenges.md
```

Match the existing Problem → why it's hard → Solution → Lesson shape and the numbering.

- [ ] **Step 2: Append challenge entry — the polymorphic-subject visibility gate**

Cover, in the template's shape:
- **Problem:** two new tables pointing polymorphically at four aggregates, each of which is already visibility-filtered. An activity on an enquiry I cannot see must be unreachable.
- **Why it's hard:** the two tables look symmetrical but are not — `follow_up` has an owner and `activity` does not, so one has intrinsic visibility and one has derived visibility. And the obvious guard does not work: a rule over *declared* repository methods is silently defeated by `JpaRepository`'s inherited `findById`/`findAll`, which is the same class of finding `VisibilityScopingArchTest` already records from the other side (a method *reference* to an inherited method resolves its owner to the Spring Data supertype and escapes owner-name checks).
- **Solution:** `follow_up` joins the guarded set with a plain `assigned_to` equality; `activity` is gated at its subject through `VisibleFinder.requireVisibleSubject`, and the gate is made unbypassable by giving `ActivityRepository` nothing to inherit — `extends Repository<T, ID>`, three declared methods, no by-id-alone lookup. The ArchUnit test asserts the supertype *first*, because the declared-method assertion is worthless without it.
- **Lesson:** when a guard has to hold a property, prefer removing the capability over policing its use. And check what a rule can actually *see*: an ArchUnit assertion over declared members is blind to everything inherited, which is exactly where the dangerous methods live.

- [ ] **Step 3: Append challenge entry — `OVERDUE` as a predicate**

- **Problem:** the parent spec promises a reminder scheduler; there is no channel for a reminder to fire into (WhatsApp is a `wa.me` deep link, email is unread and needs dedupe, no frontend exists for in-app).
- **Why it's hard:** the tempting move is to build the machinery anyway — an `OVERDUE` status column and a job that flips it — so the feature "looks complete". That is strictly more moving parts for strictly less truth.
- **Solution:** overdue is `status = PENDING AND due_at < now()`, computed at read time, with the three scopes defined as disjoint and exhaustive over `PENDING` so the dashboard's counts sum to their own total. No scheduler, no column, no job.
- **Lesson:** a denormalised flag maintained by a job is a row that can lie about itself; if the derived form is fast enough (and one index made it so), the flag is a liability, not an optimisation. Also: three scopes that individually seem obvious can silently overlap — check that a partition actually partitions.

- [ ] **Step 4: Update `annotations-reference.md`**

Check which of these already have rows and add only the missing ones:

```bash
cd /Users/divyam/Documents/easy-crm && grep -n '@EventListener\|@Enumerated\|@Configuration\|@Bean\|JsonInclude' docs/superpowers/annotations-reference.md
```

Candidates introduced or first-used by this slice: `@EventListener` (likely already present from the order-accept slice), `@Enumerated`, `@Configuration`, `@Bean`, `@JsonInclude`. Add rows in the file's existing format (origin, purpose, meta-annotation composition) for any that are absent. Do not add rows for annotations already documented.

- [ ] **Step 5: Update `HANDOFF.md`**

Four edits:

1. **Header block** — replace the record-visibility framing with this slice: what merged, the new test total, and that `main` is the baseline.
2. **§3 inventory** — add the activity/follow-up entry: two aggregates, the two visibility strategies, the four new migrations (`V27`–`V30`), the endpoints, and the `AssignableUsers` extraction.
3. **§8** — mark backlog item **#1 (activity/follow-up) as DONE**, and annotate the parent spec's *"with its own reminder scheduler"* clause as **deliberately not implemented**, citing spec §3 as the standing reason, so a future reader records it as a decision rather than an oversight. Note that item **#2 (scheduled auto-expiry) is still the first scheduled job**, and point at spec §3's tenant-iteration note as its head start.
4. **Deferred-Minor backlog** — append any `minor (deferred)` findings this slice's reviews produced, continuing the numbering from item 41. Also note that item 9 (string-keyed specifications) now covers **five** classes, not four, since `FollowUpSpecifications` joined them.

- [ ] **Step 6: Verify no doc references a file this slice did not create**

```bash
cd /Users/divyam/Documents/easy-crm && grep -o 'V2[7-9]__[a-z_]*\.sql\|V30__[a-z_]*\.sql' docs/superpowers/HANDOFF.md docs/superpowers/specs/2026-08-30-activity-follow-up-design.md | sort -u
ls backend/src/main/resources/db/migration/ | grep -E 'V2[7-9]|V30'
```

Expected: the two lists agree. **The spec still describes a single `V29__rls_activity_follow_up.sql`** — update spec §5.3 to the four-file split this plan uses, with the one-line reason (each table ships RLS in its own task, or `RlsCoverageIntegrationTest` is red between Task 2 and Task 6).

- [ ] **Step 7: Run the full suite one last time and count**

```bash
cd backend && ./gradlew clean test
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'errors="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "errors:", s}'
```

Expected: **0 failures, 0 errors**, and roughly 400–405 tests (352 baseline + ~50). Record the exact number in `HANDOFF.md` — the next session's first action is to reproduce it.

- [ ] **Step 8: Commit**

```bash
git add docs/superpowers/engineering-challenges.md \
        docs/superpowers/annotations-reference.md \
        docs/superpowers/HANDOFF.md \
        docs/superpowers/specs/2026-08-30-activity-follow-up-design.md
git commit -m "docs: record the activity and follow-up slice

Two challenge entries: the polymorphic-subject visibility gate, whose real
lesson is that an ArchUnit rule over declared members cannot see the
inherited methods that are exactly the dangerous ones; and overdue as a
predicate rather than a status column with a job behind it.

Handoff marks backlog item 1 done and annotates the parent spec's reminder
scheduler as deliberately unbuilt, with the reason, so it reads as a
decision rather than an oversight."
```

---

## After the plan

Run `superpowers:finishing-a-development-branch` to decide how `activity-follow-up` integrates. Before that, the house sequence is a whole-branch review (`superpowers:requesting-code-review`), a fix wave if it returns anything, and a verified-green run on the merged result — that is what the previous three slices did and what `HANDOFF.md` expects to be able to say.
