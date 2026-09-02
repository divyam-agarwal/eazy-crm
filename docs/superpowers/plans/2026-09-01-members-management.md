# Members Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an owner list the members of a workspace, change a member's role, and disable or re-enable a member — with a disable that actually ends the member's access and cannot strand the workspace or orphan assigned work.

**Architecture:** A new owner-only `MemberService`/`MemberController` pair in `com.easycrm.iam`, alongside the invitations surface it completes. Two structural pieces carry the design: an `AssignedWorkload` port declared in `iam` and implemented in `crm`/`sales`, which lets the reassign-first gate count a member's open work without `iam` ever importing either package; and a `PESSIMISTIC_WRITE` lock on the tenant row, which materialises the write-skew conflict that a plain last-owner count cannot see. Disable is made real by revoking the member's refresh tokens and closing the missing `UserStatus` check in `AuthService.refresh`.

**Tech Stack:** Java 25, Spring Boot 4.1, Hibernate 7 (`@TenantId`), PostgreSQL 16 + RLS, Flyway, JUnit 5 + Testcontainers, ArchUnit, Spotless (palantir-java-format), SpotBugs, JaCoCo.

**Spec:** `docs/superpowers/specs/2026-09-01-members-management-design.md` (commits `6985c7e`, `7011b71`)

## Global Constraints

- **Baseline command is `./gradlew clean check`, not `clean test`.** It adds `spotlessCheck`, `spotbugsMain` and `jacocoTestCoverageVerification` across both Gradle projects and is exactly what CI runs. Baseline on this branch: **519 tests, 0 failures, 0 errors** (496 root + 23 `platform-primitives`).
- **A filtered test run must be project-qualified.** `./gradlew :test --tests '<filter>'` for a root-project test; `./gradlew :platform:platform-primitives:test --tests '<filter>'` for a module test. An unqualified `./gradlew test --tests '…'` applies the filter to *every* project and fails on whichever has no match.
- **Never hand-write `WHERE tenant_id = ?`.** `app_user` is `@TenantId` + RLS; `users.findById` on another tenant's id returns empty structurally. Do not copy `InvitationService.revoke`'s filter — that exists only because `invitation` is a global table.
- **`platform` must not depend on `iam`.** This is why `RoleGuard` compares the literal `"OWNER"` rather than `Role.OWNER.name()`. The `AssignedWorkload` port exists to respect this.
- **`platform-primitives` JaCoCo floors are LINE `0.83` / BRANCH `0.99`** against roughly 22 branches. One new untested branch in that module drops branch coverage to ~0.956 and reddens the build. Any change there ships with its own test.
- **Commits are authored as `divyam <divyam.0444@gmail.com>`** with plain `git commit`. Do **not** add a `Co-Authored-By: Claude` trailer or mention Claude/AI anywhere in a commit message.
- **Log engineering challenges** in `docs/superpowers/engineering-challenges.md` as part of the same change, using the template at the bottom of that file. Task 9 covers the two this slice earns.
- **Keep `docs/superpowers/annotations-reference.md` current** for any annotation this slice introduces that is not already documented.
- Docker must be running for the Testcontainers suite (`docker info` succeeds).

---

## File Structure

| File | Responsibility |
|---|---|
| `platform/platform-primitives/src/main/java/com/easycrm/platform/error/ConflictException.java` | **Modify** — gains an optional structured-detail map so a 409 can be machine-readable |
| `src/main/java/com/easycrm/platform/error/ApiExceptionHandler.java` | **Modify** — the 409 handler passes the exception's fields through |
| `src/main/java/com/easycrm/iam/User.java` | **Modify** — first mutators: `changeRole`, `disable`, `enable`, with already-in-state guards |
| `src/main/java/com/easycrm/iam/AssignedWorkload.java` | **Create** — the port; one kind of work a member can still hold |
| `src/main/java/com/easycrm/crm/CustomerWorkload.java` | **Create** — active customers assigned to a member |
| `src/main/java/com/easycrm/sales/EnquiryWorkload.java` | **Create** — non-terminal enquiries assigned to a member |
| `src/main/java/com/easycrm/sales/FollowUpWorkload.java` | **Create** — `PENDING` follow-ups assigned to a member |
| `src/main/java/com/easycrm/crm/CustomerRepository.java` | **Modify** — one count method |
| `src/main/java/com/easycrm/sales/EnquiryRepository.java` | **Modify** — one count method |
| `src/main/java/com/easycrm/sales/FollowUpRepository.java` | **Modify** — one count method; its "declare no custom finders" javadoc needs updating too |
| `src/main/java/com/easycrm/iam/UserRepository.java` | **Modify** — active-owner count |
| `src/main/java/com/easycrm/iam/RefreshTokenRepository.java` | **Modify** — find a member's live sessions |
| `src/main/java/com/easycrm/iam/RefreshTokenService.java` | **Modify** — `revokeAllForUser` |
| `src/main/java/com/easycrm/iam/AuthService.java` | **Modify** — `refresh` gains the `UserStatus` gate |
| `src/main/java/com/easycrm/tenant/TenantRepository.java` | **Modify** — `findForUpdate` |
| `src/main/java/com/easycrm/iam/MemberService.java` | **Create** — the four operations and both invariants |
| `src/main/java/com/easycrm/iam/web/MemberController.java` | **Create** — four routes |
| `src/main/java/com/easycrm/iam/web/dto/MemberResponse.java` | **Create** |
| `src/main/java/com/easycrm/iam/web/dto/ChangeRoleRequest.java` | **Create** |
| `src/main/resources/db/migration/V33__assigned_to_indexes.sql` | **Create** — two indexes, no schema change |
| `src/test/java/com/easycrm/arch/VisibilityScopingArchTest.java` | **Modify** — three allowlist entries |

---

### Task 1: A 409 that can carry structured detail

`ConflictException` carries only a message today, and `ApiExceptionHandler.conflict(...)` passes `null` where the envelope's `fields` would go — only `ValidationException` (422) has ever carried field detail. Task 6's reassign-first gate needs the blocker counts to be machine-readable so a frontend can route to the right reassign screen instead of parsing prose.

**The single-argument constructor must keep producing a `fields`-free body**, or every existing 409 in the codebase changes shape.

**Files:**
- Modify: `backend/platform/platform-primitives/src/main/java/com/easycrm/platform/error/ConflictException.java`
- Modify: `backend/src/main/java/com/easycrm/platform/error/ApiExceptionHandler.java:31-34`
- Test: `backend/platform/platform-primitives/src/test/java/com/easycrm/platform/error/ConflictExceptionTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `ConflictException(String message, Map<String, Object> fields)` and `Map<String, Object> getFields()` (null when absent). Task 6 throws it; Task 7 asserts the wire shape.

> **Merge-conflict note.** `ApiExceptionHandler` is being refactored concurrently by the `openapi-contract` slice, which replaces the inline `Map` envelope with typed `ApiError`/`ApiErrorResponse` records. A conflict in `conflict(...)` is expected and mechanical: whichever slice lands second re-applies "pass the exception's fields through" onto the other's shape. Do not try to pre-empt it.

- [ ] **Step 1: Write the failing test**

Create `backend/platform/platform-primitives/src/test/java/com/easycrm/platform/error/ConflictExceptionTest.java`:

```java
package com.easycrm.platform.error;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConflictExceptionTest {

    @Test
    void messageOnlyConflictCarriesNoFields() {
        ConflictException ex = new ConflictException("already disabled");
        assertEquals("already disabled", ex.getMessage());
        assertNull(ex.getFields(), "a plain conflict must serialize without a fields key");
    }

    @Test
    void structuredConflictExposesItsFields() {
        ConflictException ex = new ConflictException("still holds work", Map.of("customers", 3L));
        assertEquals(3L, ex.getFields().get("customers"));
    }

    @Test
    void fieldsAreDefensivelyCopiedAndUnmodifiable() {
        Map<String, Object> source = new HashMap<>();
        source.put("customers", 1L);
        ConflictException ex = new ConflictException("still holds work", source);

        source.put("enquiries", 9L);
        assertNull(ex.getFields().get("enquiries"), "must not retain the caller's map");
        assertThrows(UnsupportedOperationException.class, () -> ex.getFields().put("orders", 1L));
    }

    @Test
    void fieldsPreserveInsertionOrder() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("customers", 1L);
        source.put("enquiries", 2L);
        source.put("follow-ups", 3L);
        assertEquals(
                "[customers, enquiries, follow-ups]",
                new ConflictException("m", source).getFields().keySet().toString());
    }

    @Test
    void nullFieldsAreTolerated() {
        assertNull(new ConflictException("m", null).getFields());
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd backend && ./gradlew :platform:platform-primitives:test --tests 'com.easycrm.platform.error.ConflictExceptionTest'
```

Expected: compilation failure — no two-argument constructor, no `getFields()`.

- [ ] **Step 3: Add the constructor**

Replace the body of `ConflictException`:

```java
package com.easycrm.platform.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The request conflicts with current state. Mapped to HTTP 409 by ApiExceptionHandler.
 *
 * <p>{@code fields} is optional and null for almost every conflict in this codebase — the
 * message alone is the contract. It exists for a conflict a client must act on
 * programmatically rather than merely display: the members-management reassign-first gate
 * returns the count of open work per aggregate so a frontend can route the owner to the
 * right screen instead of parsing prose. See spec 2026-09-01-members-management-design.md §4.4.
 *
 * <p>LinkedHashMap, not Map.copyOf: an immutable map's iteration order is salt-randomized
 * per JVM boot, so the same multi-key conflict would serialize its keys in a different order
 * from one deploy to the next. The copy is also what stops SpotBugs flagging EI_EXPOSE_REP2.
 */
public class ConflictException extends RuntimeException {

    private final Map<String, Object> fields;

    public ConflictException(String message) {
        this(message, null);
    }

    public ConflictException(String message, Map<String, Object> fields) {
        super(message);
        this.fields = fields == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /** Null when this conflict carries no structured detail — the common case. */
    public Map<String, Object> getFields() {
        return fields;
    }
}
```

- [ ] **Step 4: Run the test again**

```bash
cd backend && ./gradlew :platform:platform-primitives:test --tests 'com.easycrm.platform.error.ConflictExceptionTest'
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Pass the fields through the handler**

In `ApiExceptionHandler`, change only the `conflict` handler — leave the `DataIntegrityViolationException` and `OptimisticLockingFailureException` handlers passing `null`:

```java
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(ConflictException ex) {
        // body() omits the key entirely when fields is null, so every existing 409 in the
        // codebase stays byte-identical — only a conflict that opts in gains a fields object.
        return body(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), ex.getFields());
    }
```

- [ ] **Step 6: Run the full check**

```bash
cd backend && ./gradlew clean check
```

Expected: BUILD SUCCESSFUL, 524 tests (519 + 5). If `jacocoTestCoverageVerification` fails on `platform-primitives`, the new constructor has an untested branch — re-read the module floor in Global Constraints.

- [ ] **Step 7: Commit**

```bash
git add backend/platform/platform-primitives backend/src/main/java/com/easycrm/platform/error/ApiExceptionHandler.java
git commit -m "feat: let a 409 carry structured detail

ConflictException gains an optional fields map and the 409 handler passes
it through. The single-argument constructor still produces a fields-free
body, so every existing 409 is byte-identical.

Needed by the members-management reassign-first gate, which must tell a
client how much open work blocks a disable, per aggregate, without the
client parsing prose."
```

---

### Task 2: `User` learns to change

`User` is construct-only today. This slice needs three mutators, with the already-in-that-state guards on the entity — the pattern `Quotation.expire()` and `Invitation.revoke()` set.

`changeRole` carries **no** guard: assigning the role a member already holds is harmless and idempotent, and rejecting it would fail a retried request for no reason.

**Files:**
- Modify: `backend/src/main/java/com/easycrm/iam/User.java`
- Test: `backend/src/test/java/com/easycrm/iam/UserTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `User.changeRole(Role)`, `User.disable()`, `User.enable()`, all `void`. Task 5 and Task 6 call them.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/iam/UserTest.java`:

```java
package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.platform.error.ConflictException;
import org.junit.jupiter.api.Test;

class UserTest {

    private User active(Role role) {
        return new User("a@b.test", null, "hash", role, UserStatus.ACTIVE);
    }

    @Test
    void changeRoleReplacesTheRole() {
        User u = active(Role.SALES_EXEC);
        u.changeRole(Role.OWNER);
        assertEquals(Role.OWNER, u.getRole());
    }

    @Test
    void changingToTheSameRoleIsIdempotentRatherThanAConflict() {
        User u = active(Role.OWNER);
        assertDoesNotThrow(() -> u.changeRole(Role.OWNER));
        assertEquals(Role.OWNER, u.getRole());
    }

    @Test
    void disableFlipsStatus() {
        User u = active(Role.SALES_EXEC);
        u.disable();
        assertEquals(UserStatus.DISABLED, u.getStatus());
    }

    @Test
    void disablingATwiceDisabledMemberConflicts() {
        User u = active(Role.SALES_EXEC);
        u.disable();
        ConflictException ex = assertThrows(ConflictException.class, u::disable);
        assertEquals("member is already disabled", ex.getMessage());
    }

    @Test
    void enableFlipsStatusBack() {
        User u = active(Role.SALES_EXEC);
        u.disable();
        u.enable();
        assertEquals(UserStatus.ACTIVE, u.getStatus());
    }

    @Test
    void enablingAnActiveMemberConflicts() {
        User u = active(Role.SALES_EXEC);
        ConflictException ex = assertThrows(ConflictException.class, u::enable);
        assertEquals("member is already active", ex.getMessage());
    }

    @Test
    void aRejectedTransitionLeavesStatusUntouched() {
        User u = active(Role.SALES_EXEC);
        assertThrows(ConflictException.class, u::enable);
        assertEquals(UserStatus.ACTIVE, u.getStatus(), "the guard must run before any assignment");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.UserTest'
```

Expected: compilation failure — `changeRole`, `disable`, `enable` do not exist.

- [ ] **Step 3: Add the mutators**

Append to `User`, after `getStatus()`:

```java
    /**
     * No guard, deliberately: assigning the role a member already holds is harmless, and
     * rejecting it would fail a retried request for no reason. The invariant that a
     * workspace keeps at least one active owner is tenant-wide and lives in MemberService —
     * an entity cannot count its siblings.
     */
    public void changeRole(Role newRole) {
        this.role = newRole;
    }

    public void disable() {
        if (status == UserStatus.DISABLED) {
            throw new ConflictException("member is already disabled");
        }
        this.status = UserStatus.DISABLED;
    }

    public void enable() {
        if (status == UserStatus.ACTIVE) {
            throw new ConflictException("member is already active");
        }
        this.status = UserStatus.ACTIVE;
    }
```

Add the import `com.easycrm.platform.error.ConflictException`.

- [ ] **Step 4: Run the test**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.UserTest'
```

Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/easycrm/iam/User.java backend/src/test/java/com/easycrm/iam/UserTest.java
git commit -m "feat: give User its role and status transitions

changeRole, disable and enable, with the already-in-that-state guards on
the entity as Quotation.expire and Invitation.revoke do it. changeRole is
deliberately unguarded so a retried request cannot fail."
```

---

### Task 3: The `AssignedWorkload` port and its three implementations

The reassign-first gate needs to count a member's open work across three aggregates. Two constraints shape how:

1. **`VisibilityScopingArchTest`** — `CustomerRepository`, `EnquiryRepository` and `FollowUpRepository` are guarded. Only classes inside `com.easycrm.platform.visibility..` may read them, unless the method name is on the shared `ALLOWED_METHODS` allowlist.
2. **Package direction** — `crm` and `sales` already depend on `iam` (all four of `CustomerService`, `EnquiryService`, `ActivityService`, `FollowUpService` import `iam.AssignableUsers`). `iam` imports nothing from either, and must keep it that way.

So `iam` declares the port and `crm`/`sales` implement it: the arrow already exists, and the graph stays acyclic.

**Routing this through `VisibleFinder` would be wrong.** For an owner its filter is a no-op today, so it returns the right number — but an invariant check must *never* filter, and a gate that under-counts lets a disable through while work remains assigned. The allowlist is the correct mechanism; it already carries `findByGstin` and `findByNormalizedPhone` for exactly this "must see the whole tenant" reason.

**Files:**
- Create: `backend/src/main/java/com/easycrm/iam/AssignedWorkload.java`
- Create: `backend/src/main/java/com/easycrm/crm/CustomerWorkload.java`
- Create: `backend/src/main/java/com/easycrm/sales/EnquiryWorkload.java`
- Create: `backend/src/main/java/com/easycrm/sales/FollowUpWorkload.java`
- Modify: `backend/src/main/java/com/easycrm/crm/CustomerRepository.java`
- Modify: `backend/src/main/java/com/easycrm/sales/EnquiryRepository.java`
- Modify: `backend/src/main/java/com/easycrm/sales/FollowUpRepository.java`
- Modify: `backend/src/test/java/com/easycrm/arch/VisibilityScopingArchTest.java:37-46`
- Create: `backend/src/main/resources/db/migration/V33__assigned_to_indexes.sql`
- Test: `backend/src/test/java/com/easycrm/iam/AssignedWorkloadTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `interface com.easycrm.iam.AssignedWorkload` with `String label()` and `long countOpenFor(UUID userId)`; three `@Component` implementations discovered by Spring as `List<AssignedWorkload>`. Task 6 injects that list.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/iam/AssignedWorkloadTest.java`:

```java
package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.Enquiry;
import com.easycrm.sales.EnquiryRepository;
import com.easycrm.sales.EnquirySource;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class AssignedWorkloadTest extends IntegrationTest {

    @Autowired
    List<AssignedWorkload> workloads;

    @Autowired
    CustomerRepository customers;

    @Autowired
    EnquiryRepository enquiries;

    @Autowired
    TestTokens tokens;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private Map<String, AssignedWorkload> byLabel() {
        return workloads.stream().collect(Collectors.toMap(AssignedWorkload::label, Function.identity()));
    }

    @Test
    void everyAssignedAggregateIsRepresented() {
        assertEquals(
                java.util.Set.of("customers", "enquiries", "follow-ups"),
                byLabel().keySet(),
                "each aggregate carrying its own assigned_to must block a disable");
    }

    @Test
    void anActiveAssignedCustomerCounts() {
        var owner = tokens.provisionOwner("27");
        UUID member = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(owner.tenantId(), null, "SYSTEM"));

        // customer.source is NOT NULL; assignedTo is the 7th argument.
        tx.executeWithoutResult(s -> customers.save(
                new Customer("Shop A", null, "27", null, null, 0, member, null, CustomerSource.MANUAL)));

        assertEquals(1L, byLabel().get("customers").countOpenFor(member));
        assertEquals(0L, byLabel().get("customers").countOpenFor(UUID.randomUUID()), "scoped to the member");
    }

    @Test
    void aTerminalEnquiryDoesNotCount() {
        var owner = tokens.provisionOwner("27");
        UUID member = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(owner.tenantId(), null, "SYSTEM"));

        tx.executeWithoutResult(s -> {
            // (customerId, contactName, contactPhone, normalizedPhone, contactEmail,
            //  source, requirementText, assignedTo, expectedValue) — both phones are
            //  NOT NULL. A new Enquiry starts at stage NEW.
            Enquiry open = new Enquiry(
                    null, "Ravi", "9876543210", "9876543210", null,
                    EnquirySource.MANUAL, "need pipes", member, null);
            Enquiry done = new Enquiry(
                    null, "Sita", "9876543211", "9876543211", null,
                    EnquirySource.MANUAL, "need taps", member, null);
            // lose(reason), not advanceTo: advanceTo takes one argument and REFUSES a
            // terminal stage (`!target.isActive()` throws). LOST and CONVERTED have their
            // own methods, lose(String) and markConverted().
            done.lose("no budget");
            enquiries.save(open);
            enquiries.save(done);
        });

        assertEquals(1L, byLabel().get("enquiries").countOpenFor(member), "only non-terminal enquiries block a disable");
    }
}
```

> **One active enquiry per phone.** `uq_enquiry_tenant_active_phone` is a partial unique index over non-terminal enquiries, so the two fixtures above must use *different* phone numbers — they do. Reuse one and the insert fails with a constraint violation, not an assertion failure.

- [ ] **Step 2: Run it and watch it fail**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.AssignedWorkloadTest'
```

Expected: compilation failure — `AssignedWorkload` does not exist.

- [ ] **Step 3: Declare the port**

Create `backend/src/main/java/com/easycrm/iam/AssignedWorkload.java`:

```java
package com.easycrm.iam;

import java.util.UUID;

/**
 * One kind of open work a member can still hold, and therefore one thing that can block
 * disabling them. See spec 2026-09-01-members-management-design.md §4.
 *
 * <p>This port is declared in {@code iam} and implemented in {@code crm} and {@code sales}
 * so that iam never imports either. Those packages already depend on iam (all four of
 * CustomerService, EnquiryService, ActivityService and FollowUpService import
 * AssignableUsers), so implementing an iam interface adds no new edge and the package graph
 * stays acyclic. A MemberService that called those repositories directly would invert the
 * arrow and create this codebase's first iam-to-sales cycle.
 *
 * <p>Implementations MUST NOT apply visibility filtering. This is an invariant check, not a
 * user-facing read: a count that hides rows would let a disable through while work remains
 * assigned to the disabled member. That is why the count methods are on the shared
 * ALLOWED_METHODS list in VisibilityScopingArchTest rather than routed through VisibleFinder.
 *
 * <p>Quotations and orders are deliberately absent: they carry no assigned_to and derive
 * their visibility from their customer, so reassigning the customer carries them.
 */
public interface AssignedWorkload {

    /** Stable, human-meaningful plural used in the 409 message and as its field key. */
    String label();

    /** Open items assigned to this member within the current tenant. Never filtered. */
    long countOpenFor(UUID userId);
}
```

- [ ] **Step 4: Add the three count methods to the guarded repositories**

In `CustomerRepository`, after `findByGstin`:

```java
    /**
     * Tenant-wide, deliberately unfiltered — see AssignedWorkload. Allowlisted in
     * VisibilityScopingArchTest for the same reason findByGstin is: an invariant check that
     * cannot see the whole tenant is not an invariant check.
     */
    @Transactional(readOnly = true)
    long countByAssignedToAndActiveTrue(UUID assignedTo);
```

In `EnquiryRepository`, after `findByNormalizedPhone`:

```java
    /** Tenant-wide, deliberately unfiltered — see AssignedWorkload. */
    @Transactional(readOnly = true)
    long countByAssignedToAndStageIn(UUID assignedTo, Collection<EnquiryStage> stages);
```

Add `import java.util.Collection;`.

In `FollowUpRepository`, replace the body — and **update its class javadoc**, which currently says "Declare no custom finders":

```java
/**
 * Unlike ActivityRepository, this one extends JpaRepository normally: a follow-up has its
 * own assigned_to, so it is filtered by VisibilityPolicy through VisibleFinder rather than
 * gated at a subject.
 *
 * <p>Declare no custom READ finder here without adding its name to the shared
 * ALLOWED_METHODS set in VisibilityScopingArchTest — a visibility decision requiring the
 * same review as adding a table to TenantScopingArchTest.GLOBAL_TABLES. The one exception
 * on record is the count below, which is an invariant check and must not be filtered.
 */
public interface FollowUpRepository extends JpaRepository<FollowUp, UUID>, JpaSpecificationExecutor<FollowUp> {

    /** Tenant-wide, deliberately unfiltered — see AssignedWorkload. */
    @Transactional(readOnly = true)
    long countByAssignedToAndStatus(UUID assignedTo, FollowUpStatus status);
}
```

Add `import org.springframework.transaction.annotation.Transactional;`.

- [ ] **Step 5: Write the three implementations**

`backend/src/main/java/com/easycrm/crm/CustomerWorkload.java`:

```java
package com.easycrm.crm;

import com.easycrm.iam.AssignedWorkload;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Active customers assigned to a member. An inactive customer needs no owner. */
@Component
public class CustomerWorkload implements AssignedWorkload {

    private final CustomerRepository customers;

    public CustomerWorkload(CustomerRepository customers) {
        this.customers = customers;
    }

    @Override
    public String label() {
        return "customers";
    }

    @Override
    public long countOpenFor(UUID userId) {
        return customers.countByAssignedToAndActiveTrue(userId);
    }
}
```

`backend/src/main/java/com/easycrm/sales/EnquiryWorkload.java`:

```java
package com.easycrm.sales;

import com.easycrm.iam.AssignedWorkload;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Non-terminal enquiries assigned to a member — a dropped lead if it stays with someone who
 * cannot log in.
 *
 * <p>An enquiry carries its OWN assigned_to and its customer_id is nullable (an enquiry
 * precedes the customer in this wedge), so reassigning a customer does NOT carry it. That
 * asymmetry is the reason this implementation exists.
 */
@Component
public class EnquiryWorkload implements AssignedWorkload {

    // Derived from isTerminal() rather than listed literally, so a new stage joins the right
    // side automatically instead of silently defaulting to "does not block a disable".
    private static final List<EnquiryStage> ACTIVE_STAGES =
            Arrays.stream(EnquiryStage.values()).filter(EnquiryStage::isActive).toList();

    private final EnquiryRepository enquiries;

    public EnquiryWorkload(EnquiryRepository enquiries) {
        this.enquiries = enquiries;
    }

    @Override
    public String label() {
        return "enquiries";
    }

    @Override
    public long countOpenFor(UUID userId) {
        return enquiries.countByAssignedToAndStageIn(userId, ACTIVE_STAGES);
    }
}
```

`backend/src/main/java/com/easycrm/sales/FollowUpWorkload.java`:

```java
package com.easycrm.sales;

import com.easycrm.iam.AssignedWorkload;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * PENDING follow-ups assigned to a member. The sharpest of the three: follow_up.assigned_to
 * is NOT NULL and VisibilityPolicy filters on it intrinsically, so a PENDING follow-up left
 * with a disabled member is invisible to every other SALES_EXEC and will never be actioned —
 * exactly the failure the activity/follow-up feature exists to prevent.
 */
@Component
public class FollowUpWorkload implements AssignedWorkload {

    private final FollowUpRepository followUps;

    public FollowUpWorkload(FollowUpRepository followUps) {
        this.followUps = followUps;
    }

    @Override
    public String label() {
        return "follow-ups";
    }

    @Override
    public long countOpenFor(UUID userId) {
        return followUps.countByAssignedToAndStatus(userId, FollowUpStatus.PENDING);
    }
}
```

- [ ] **Step 6: Run the test and watch the ArchUnit guard fail**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.AssignedWorkloadTest' --tests 'com.easycrm.arch.VisibilityScopingArchTest'
```

Expected: `AssignedWorkloadTest` passes, and **`VisibilityScopingArchTest` FAILS** with three violations naming `CustomerWorkload`, `EnquiryWorkload` and `FollowUpWorkload`. That failure is the guard working — it is exactly what you want to see before allowlisting.

- [ ] **Step 7: Add the three allowlist entries**

In `VisibilityScopingArchTest.ALLOWED_METHODS`, append inside the `Set.of(...)`:

```java
            // Invariant checks, not user-facing reads: MemberService refuses to disable a
            // member who still holds open work, and a count that hid rows would let the
            // disable through while work remained assigned to them. Same "must see the whole
            // tenant" reasoning as findByGstin above. See AssignedWorkload and spec
            // 2026-09-01-members-management-design.md §4.
            "countByAssignedToAndActiveTrue",
            "countByAssignedToAndStageIn",
            "countByAssignedToAndStatus");
```

(Remove the closing paren from the previous entry.)

- [ ] **Step 8: Add the migration**

Create `backend/src/main/resources/db/migration/V33__assigned_to_indexes.sql`:

```sql
-- The record-visibility slice shipped the predicate
--     assigned_to = :me OR assigned_to IS NULL
-- on customer and enquiry with no index behind it, and members management now adds three
-- more reads keyed on assigned_to (the reassign-first gate). follow_up already ships its
-- equivalent, idx_follow_up_owner_due, from its own creating migration.
--
-- The house pattern is that the slice adding the query adds the index: one line here,
-- versus a migration against a live table later.
CREATE INDEX idx_customer_assigned ON customer (tenant_id, assigned_to);
CREATE INDEX idx_enquiry_assigned  ON enquiry  (tenant_id, assigned_to);
```

> Confirm `V33` is still free before writing it (`ls backend/src/main/resources/db/migration | sort -V | tail -3`). If another slice has taken it, use the next free number — Flyway fails on a duplicate version.

- [ ] **Step 9: Run the full check**

```bash
cd backend && ./gradlew clean check
```

Expected: BUILD SUCCESSFUL, 527 tests (524 + 3).

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/easycrm/iam/AssignedWorkload.java \
        backend/src/main/java/com/easycrm/crm backend/src/main/java/com/easycrm/sales \
        backend/src/main/resources/db/migration/V33__assigned_to_indexes.sql \
        backend/src/test/java/com/easycrm/iam/AssignedWorkloadTest.java \
        backend/src/test/java/com/easycrm/arch/VisibilityScopingArchTest.java
git commit -m "feat: count a member's open work through a port declared in iam

AssignedWorkload is declared in iam and implemented in crm and sales, so
the reassign-first gate can count open work without iam importing either
package. Those packages already depend on iam, so the graph stays acyclic.

The counts are deliberately unfiltered and allowlisted in
VisibilityScopingArchTest rather than routed through VisibleFinder: an
invariant check that hides rows would let a disable through while work
remained assigned.

Also ships the two assigned_to indexes record-visibility left out, since
this slice adds three more queries on that column."
```

---

### Task 4: Make a disabled member's session actually end

Flipping `status` does nothing on its own. `AuthService.refresh` rotates the token, loads the user, and mints a fresh access token **with no status check at all** — so without this task a disabled member refreshes indefinitely and never notices.

**Files:**
- Modify: `backend/src/main/java/com/easycrm/iam/RefreshTokenRepository.java`
- Modify: `backend/src/main/java/com/easycrm/iam/RefreshTokenService.java`
- Modify: `backend/src/main/java/com/easycrm/iam/AuthService.java` (the `refresh` method)
- Test: `backend/src/test/java/com/easycrm/iam/AuthServiceRefreshTest.java` (extend)
- Test: `backend/src/test/java/com/easycrm/iam/RefreshTokenServiceTest.java` (extend)

**Interfaces:**
- Consumes: nothing.
- Produces: `RefreshTokenService.revokeAllForUser(UUID userId, UUID tenantId)` returning `int` (the number revoked). Task 6 calls it and puts the count in the audit detail.

- [ ] **Step 1: Write the failing tests**

Append to `AuthServiceRefreshTest`, and add imports `com.easycrm.platform.tenancy.TenantContext` (already present), `org.springframework.transaction.support.TransactionTemplate`, `java.util.UUID`:

```java
    @Autowired
    UserRepository users;

    @Autowired
    TransactionTemplate tx;

    @Test
    void aDisabledMemberCannotRefresh() {
        AuthResponse signed = signup("refresh-disabled");

        TenantContext.set(new TenantContext.TenantPrincipal(signed.tenantId(), null, "SYSTEM"));
        tx.executeWithoutResult(s -> users.findById(signed.userId()).orElseThrow().disable());
        TenantContext.clear();

        // Same generic message as every other refresh failure: no new enumeration signal.
        UnauthorizedException ex =
                assertThrows(UnauthorizedException.class, () -> auth.refresh(signed.refreshToken()));
        assertEquals("invalid refresh token", ex.getMessage());
    }
```

Append to `RefreshTokenServiceTest`:

```java
    @Test
    void revokeAllForUserRevokesEveryLiveSessionAndReportsHowMany() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String first = refreshTokens.issue(userId, tenantId);
        String second = refreshTokens.issue(userId, tenantId);

        assertEquals(2, refreshTokens.revokeAllForUser(userId, tenantId));

        assertThrows(UnauthorizedException.class, () -> refreshTokens.rotate(first));
        assertThrows(UnauthorizedException.class, () -> refreshTokens.rotate(second));
    }

    @Test
    void revokeAllForUserLeavesAnotherMembersSessionsAlone() {
        UUID tenantId = UUID.randomUUID();
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        refreshTokens.issue(mine, tenantId);
        String theirToken = refreshTokens.issue(theirs, tenantId);

        refreshTokens.revokeAllForUser(mine, tenantId);

        assertDoesNotThrow(() -> refreshTokens.rotate(theirToken));
    }

    @Test
    void revokeAllForUserIsScopedToTheTenant() {
        UUID userId = UUID.randomUUID();
        String otherTenantToken = refreshTokens.issue(userId, UUID.randomUUID());

        assertEquals(0, refreshTokens.revokeAllForUser(userId, UUID.randomUUID()));
        assertDoesNotThrow(() -> refreshTokens.rotate(otherTenantToken));
    }
```

> Check `RefreshTokenServiceTest`'s existing field name for the injected service and reuse it verbatim; the snippets above assume `refreshTokens`.

- [ ] **Step 2: Run them and watch them fail**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.AuthServiceRefreshTest' --tests 'com.easycrm.iam.RefreshTokenServiceTest'
```

Expected: `RefreshTokenServiceTest` fails to compile (`revokeAllForUser` missing). `aDisabledMemberCannotRefresh` **fails on the assertion, not on compilation** — `auth.refresh` succeeds today. Confirm you see that specific failure: it is the proof the hole is real.

- [ ] **Step 3: Add the repository finder**

In `RefreshTokenRepository`:

```java
    /**
     * Every live session belonging to one member. refresh_token is a GLOBAL, RLS-exempt
     * table, so the tenantId term is load-bearing rather than belt-and-braces — the same
     * reasoning as InvitationService.revoke (challenge #54) — even though a userId UUID is
     * already globally unique.
     */
    List<RefreshToken> findByUserIdAndTenantIdAndRevokedAtIsNull(UUID userId, UUID tenantId);
```

Add `import java.util.List;`.

- [ ] **Step 4: Add `revokeAllForUser`**

In `RefreshTokenService`:

```java
    /**
     * Ends every live session a member has. Returns how many were revoked, which
     * MemberService records in the audit row — "disabled, 3 sessions killed" is a materially
     * different event from "disabled, was not logged in".
     */
    @Transactional
    public int revokeAllForUser(UUID userId, UUID tenantId) {
        List<RefreshToken> live = tokens.findByUserIdAndTenantIdAndRevokedAtIsNull(userId, tenantId);
        Instant now = Instant.now();
        live.forEach(t -> t.revoke(now, null));
        tokens.saveAll(live);
        return live.size();
    }
```

Add `import java.util.List;`.

- [ ] **Step 5: Close the refresh hole**

In `AuthService.refresh`, inside the `tx.execute` block, replace the user lookup:

```java
                User user = users.findById(rot.userId())
                        .orElseThrow(() -> new UnauthorizedException("invalid refresh token"));
                // Without this, disabling a member is decorative: refresh would keep minting
                // access tokens for them forever. The rotation above has already burned the
                // presented token, so a disabled member's session cannot be resumed at all.
                // Same generic message as every other failure here — no enumeration signal.
                if (user.getStatus() != UserStatus.ACTIVE) {
                    throw new UnauthorizedException("invalid refresh token");
                }
```

- [ ] **Step 6: Run the tests**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.AuthServiceRefreshTest' --tests 'com.easycrm.iam.RefreshTokenServiceTest'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/easycrm/iam backend/src/test/java/com/easycrm/iam
git commit -m "feat: end a disabled member's sessions

AuthService.refresh gains a UserStatus check. Without it disabling a
member is decorative: refresh rotates and mints today with no status
check at all, so a disabled member would refresh indefinitely. The
rejection reuses the existing generic 401, so the endpoint gains no
enumeration signal.

RefreshTokenService.revokeAllForUser ends every live session a member
holds and reports the count for the audit row. The finder is scoped by
tenant as well as user because refresh_token is a global, RLS-exempt
table."
```

---

### Task 5: `MemberService` — list, change role, and the last-owner invariant

The tenant lock is introduced here because `changeRole` is the first operation that needs it. Task 6 reuses it.

**Why the lock:** the last-active-owner check is check-then-act. Two owners demoting each other concurrently both count 2, both pass, and both commit — writing *disjoint* rows, so `@Version` sees no conflict, no constraint expresses "at least one", and Postgres REPEATABLE READ does not detect write skew. Taking a `PESSIMISTIC_WRITE` lock on the tenant row materialises the conflict onto a row both transactions must touch. Task 8 proves it.

**Files:**
- Modify: `backend/src/main/java/com/easycrm/tenant/TenantRepository.java`
- Modify: `backend/src/main/java/com/easycrm/iam/UserRepository.java`
- Create: `backend/src/main/java/com/easycrm/iam/MemberService.java`
- Create: `backend/src/main/java/com/easycrm/iam/web/dto/MemberResponse.java`
- Test: `backend/src/test/java/com/easycrm/iam/MemberServiceTest.java` (create)

**Interfaces:**
- Consumes: `User.changeRole` (Task 2).
- Produces: `MemberService.list()` → `List<MemberResponse>`; `MemberService.changeRole(UUID id, String role)` → `MemberResponse`; `TenantRepository.findForUpdate(UUID)`; `UserRepository.countByRoleAndStatus(Role, UserStatus)`; the record `MemberResponse(UUID id, String email, String phone, String role, String status, Instant createdAt)`. Tasks 6, 7 and 8 use all of these.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/iam/MemberServiceTest.java`:

```java
package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.iam.web.dto.MemberResponse;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.ForbiddenException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class MemberServiceTest extends IntegrationTest {

    @Autowired
    MemberService members;

    @Autowired
    UserRepository users;

    @Autowired
    TestTokens tokens;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** Provisions a tenant and returns its id, with an OWNER principal already bound. */
    private UUID tenantWithOwnerBound(UUID actingUserId) {
        var owner = tokens.provisionOwner("27");
        TenantContext.set(new TenantContext.TenantPrincipal(owner.tenantId(), actingUserId, "OWNER"));
        return owner.tenantId();
    }

    private UUID addUser(UUID tenantId, String email, Role role, UserStatus status) {
        TenantContext.TenantPrincipal caller = TenantContext.get().orElse(null);
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"));
        try {
            return tx.execute(s -> users.save(new User(email, null, "hash", role, status))
                    .getId());
        } finally {
            if (caller != null) TenantContext.set(caller);
            else TenantContext.clear();
        }
    }

    @Test
    void listReturnsActiveAndDisabledMembersWithoutPasswordHashes() {
        UUID me = UUID.randomUUID();
        UUID tenantId = tenantWithOwnerBound(me);
        addUser(tenantId, "owner@x.test", Role.OWNER, UserStatus.ACTIVE);
        addUser(tenantId, "gone@x.test", Role.SALES_EXEC, UserStatus.DISABLED);

        List<MemberResponse> list = members.list();

        assertEquals(2, list.size());
        assertTrue(list.stream().anyMatch(m -> m.status().equals("DISABLED")), "disabled members are listed");
    }

    @Test
    void onlyAnOwnerMayList() {
        var owner = tokens.provisionOwner("27");
        TenantContext.set(new TenantContext.TenantPrincipal(owner.tenantId(), UUID.randomUUID(), "SALES_EXEC"));
        assertThrows(ForbiddenException.class, () -> members.list());
    }

    @Test
    void anotherTenantsMemberIsNotFound() {
        UUID tenantId = tenantWithOwnerBound(UUID.randomUUID());
        var other = tokens.provisionOwner("27");
        UUID stranger = addUser(other.tenantId(), "stranger@y.test", Role.SALES_EXEC, UserStatus.ACTIVE);
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, UUID.randomUUID(), "OWNER"));

        // 404 rather than 403: RLS scopes the lookup structurally, no hand-written filter.
        assertThrows(NotFoundException.class, () -> members.changeRole(stranger, "SALES_EXEC"));
    }

    @Test
    void changeRoleUpdatesTheMember() {
        UUID tenantId = tenantWithOwnerBound(UUID.randomUUID());
        addUser(tenantId, "owner@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID exec = addUser(tenantId, "exec@x.test", Role.SALES_EXEC, UserStatus.ACTIVE);

        assertEquals("SALES_MANAGER", members.changeRole(exec, "SALES_MANAGER").role());
    }

    @Test
    void theLastActiveOwnerCannotBeDemoted() {
        UUID tenantId = tenantWithOwnerBound(UUID.randomUUID());
        UUID soleOwner = addUser(tenantId, "sole@x.test", Role.OWNER, UserStatus.ACTIVE);

        ConflictException ex =
                assertThrows(ConflictException.class, () -> members.changeRole(soleOwner, "SALES_EXEC"));
        assertEquals("a workspace must keep at least one active owner", ex.getMessage());
    }

    @Test
    void anOwnerMayBeDemotedWhileAnotherActiveOwnerRemains() {
        UUID tenantId = tenantWithOwnerBound(UUID.randomUUID());
        addUser(tenantId, "keeper@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID leaving = addUser(tenantId, "leaving@x.test", Role.OWNER, UserStatus.ACTIVE);

        assertEquals("SALES_EXEC", members.changeRole(leaving, "SALES_EXEC").role());
    }

    @Test
    void anOwnerMayDemoteThemselvesWhileAnotherActiveOwnerRemains() {
        UUID tenantId = tenantWithOwnerBound(UUID.randomUUID());
        addUser(tenantId, "keeper@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID me = addUser(tenantId, "me@x.test", Role.OWNER, UserStatus.ACTIVE);
        // Re-bind so the CALLER is the member being demoted. D7: self-targeting is allowed,
        // guarded only by the last-active-owner invariant.
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, me, "OWNER"));

        assertEquals("SALES_EXEC", members.changeRole(me, "SALES_EXEC").role());
    }

    @Test
    void theLastActiveOwnerCannotDemoteThemselvesEither() {
        UUID tenantId = tenantWithOwnerBound(UUID.randomUUID());
        UUID me = addUser(tenantId, "sole@x.test", Role.OWNER, UserStatus.ACTIVE);
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, me, "OWNER"));

        // The invariant is about the workspace, not about who is asking.
        assertThrows(ConflictException.class, () -> members.changeRole(me, "SALES_EXEC"));
    }

    @Test
    void aDisabledOwnerDoesNotCountTowardTheInvariant() {
        UUID tenantId = tenantWithOwnerBound(UUID.randomUUID());
        addUser(tenantId, "active@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID disabledOwner = addUser(tenantId, "dormant@x.test", Role.OWNER, UserStatus.DISABLED);

        // Demoting an already-disabled owner cannot reduce the ACTIVE owner count, so the
        // invariant must not block it.
        assertEquals("SALES_EXEC", members.changeRole(disabledOwner, "SALES_EXEC").role());
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.MemberServiceTest'
```

Expected: compilation failure — `MemberService` and `MemberResponse` do not exist.

- [ ] **Step 3: Add the repository methods**

In `TenantRepository`:

```java
    /**
     * PESSIMISTIC_WRITE -> SELECT ... FOR UPDATE on one tenant row. Member-admin writes take
     * it so the last-active-owner check cannot lose to write skew: two owners demoting each
     * other concurrently both pass a plain count, write DISJOINT rows (so @Version sees no
     * conflict), and strand the workspace at zero owners. Postgres REPEATABLE READ does not
     * detect write skew; only SERIALIZABLE does, and that would mean retry handling
     * everywhere. Locking the row both transactions must touch materialises the conflict
     * instead. Same idiom as DocumentCounterRepository.findForUpdate.
     *
     * <p>tenant is a GLOBAL table, so this needs no RLS context of its own.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tenant t where t.id = :id")
    Optional<Tenant> findForUpdate(@Param("id") UUID id);
```

Add imports `jakarta.persistence.LockModeType`, `org.springframework.data.jpa.repository.Lock`, `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`.

In `UserRepository`:

```java
    /** Active-owner census for the last-owner invariant. RLS scopes it to the tenant. */
    @Transactional(readOnly = true)
    long countByRoleAndStatus(Role role, UserStatus status);
```

- [ ] **Step 4: Add the DTO**

Create `backend/src/main/java/com/easycrm/iam/web/dto/MemberResponse.java`:

```java
package com.easycrm.iam.web.dto;

import java.time.Instant;
import java.util.UUID;

/** A workspace member as an owner sees them. Never carries passwordHash. */
public record MemberResponse(
        UUID id, String email, String phone, String role, String status, Instant createdAt) {}
```

- [ ] **Step 5: Write `MemberService`**

Create `backend/src/main/java/com/easycrm/iam/MemberService.java`:

```java
package com.easycrm.iam;

import com.easycrm.iam.web.dto.MemberResponse;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.security.RoleGuard;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.tenant.TenantRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-only administration of the people in a workspace. The sequel to InvitationService:
 * that one gets a member in, this one manages them afterwards. See spec
 * 2026-09-01-members-management-design.md.
 */
@Service
public class MemberService {

    private final UserRepository users;
    private final TenantRepository tenants;
    private final RoleGuard roleGuard;
    private final AuditService audit;

    public MemberService(UserRepository users, TenantRepository tenants, RoleGuard roleGuard, AuditService audit) {
        this.users = users;
        this.tenants = tenants;
        this.roleGuard = roleGuard;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> list() {
        roleGuard.requireOwner("only an owner may view members");
        // findAll is @TenantId + RLS scoped; DISABLED members are included on purpose, since
        // the point of the list is to administer them.
        return users.findAll(Sort.by("email")).stream().map(MemberService::toResponse).toList();
    }

    @Transactional
    public MemberResponse changeRole(UUID id, String role) {
        roleGuard.requireOwner("only an owner may change a member's role");
        lockTenant();
        User member = requireMember(id);
        // Already @Pattern-validated at the edge, so valueOf cannot throw here.
        Role target = Role.valueOf(role);
        Role previous = member.getRole();

        if (previous == Role.OWNER && target != Role.OWNER) {
            requireAnotherActiveOwner(member);
        }
        member.changeRole(target);
        users.save(member);

        audit.record(
                "MEMBER_ROLE_CHANGED",
                actorUserId(),
                Map.of("email", member.getEmail(), "from", previous.name(), "to", target.name()));
        return toResponse(member);
    }

    /**
     * Serializes member-admin writes within one tenant. MUST be called before any invariant
     * count, or the count is check-then-act again. See TenantRepository.findForUpdate.
     */
    void lockTenant() {
        tenants.findForUpdate(TenantContext.tenantId())
                .orElseThrow(() -> new IllegalStateException("no tenant row for the authenticated tenant"));
    }

    /**
     * A workspace with no active owner can never invite, promote or re-enable anyone again,
     * and this product has no support surface — recovery would be a manual production UPDATE.
     *
     * <p>Skipped when the member is already disabled: they are not holding the workspace up,
     * so changing their role cannot reduce the active-owner count.
     */
    void requireAnotherActiveOwner(User member) {
        if (member.getStatus() != UserStatus.ACTIVE) return;
        if (users.countByRoleAndStatus(Role.OWNER, UserStatus.ACTIVE) <= 1) {
            throw new ConflictException("a workspace must keep at least one active owner");
        }
    }

    /**
     * No hand-written tenant filter: app_user is @TenantId + RLS, so another tenant's id
     * simply does not resolve. This is the deliberate contrast with
     * InvitationService.revoke, whose filter is load-bearing only because invitation is a
     * global table (challenge #54).
     */
    User requireMember(UUID id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("member not found"));
    }

    static UUID actorUserId() {
        return TenantContext.get().map(TenantContext.TenantPrincipal::userId).orElse(null);
    }

    static MemberResponse toResponse(User u) {
        return new MemberResponse(
                u.getId(),
                u.getEmail(),
                u.getPhone(),
                u.getRole().name(),
                u.getStatus().name(),
                u.getCreatedAt());
    }
}
```

- [ ] **Step 6: Run the test**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.MemberServiceTest'
```

Expected: PASS, 9 tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/easycrm/iam backend/src/main/java/com/easycrm/tenant/TenantRepository.java \
        backend/src/test/java/com/easycrm/iam/MemberServiceTest.java
git commit -m "feat: list members and change a member's role

Owner-only, with the invariant that a workspace keeps at least one active
owner. The check runs under a PESSIMISTIC_WRITE lock on the tenant row:
two owners demoting each other concurrently write disjoint rows, so
@Version sees no conflict and no constraint expresses at-least-one, and
Postgres REPEATABLE READ does not detect write skew. Locking the row both
transactions must touch materialises the conflict.

An already-disabled owner is exempt from the invariant: demoting them
cannot reduce the active-owner count."
```

---

### Task 6: Disable and enable, with the reassign-first gate

**Files:**
- Modify: `backend/src/main/java/com/easycrm/iam/MemberService.java`
- Test: `backend/src/test/java/com/easycrm/iam/MemberDisableTest.java` (create)

**Interfaces:**
- Consumes: `AssignedWorkload` (Task 3), `RefreshTokenService.revokeAllForUser` (Task 4), `User.disable`/`enable` (Task 2), `lockTenant`/`requireMember`/`requireAnotherActiveOwner` (Task 5), `ConflictException(String, Map)` (Task 1).
- Produces: `MemberService.disable(UUID)` and `MemberService.enable(UUID)`, both → `MemberResponse`. Task 7 calls them.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/iam/MemberDisableTest.java`:

```java
package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class MemberDisableTest extends IntegrationTest {

    @Autowired
    MemberService members;

    @Autowired
    UserRepository users;

    @Autowired
    CustomerRepository customers;

    @Autowired
    RefreshTokenService refreshTokens;

    @Autowired
    TestTokens tokens;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private UUID tenantWithOwnerBound() {
        var owner = tokens.provisionOwner("27");
        TenantContext.set(new TenantContext.TenantPrincipal(owner.tenantId(), UUID.randomUUID(), "OWNER"));
        return owner.tenantId();
    }

    private UUID addUser(UUID tenantId, String email, Role role, UserStatus status) {
        TenantContext.TenantPrincipal caller = TenantContext.get().orElse(null);
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"));
        try {
            return tx.execute(s -> users.save(new User(email, null, "hash", role, status))
                    .getId());
        } finally {
            if (caller != null) TenantContext.set(caller);
            else TenantContext.clear();
        }
    }

    @Test
    void disableFlipsStatusAndKillsLiveSessions() {
        UUID tenantId = tenantWithOwnerBound();
        addUser(tenantId, "owner@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID exec = addUser(tenantId, "exec@x.test", Role.SALES_EXEC, UserStatus.ACTIVE);
        String session = refreshTokens.issue(exec, tenantId);

        assertEquals("DISABLED", members.disable(exec).status());

        assertThrows(
                com.easycrm.platform.error.UnauthorizedException.class,
                () -> refreshTokens.rotate(session),
                "disable must revoke the member's live sessions");
    }

    @Test
    void openWorkBlocksDisableAndTheConflictNamesTheCounts() {
        UUID tenantId = tenantWithOwnerBound();
        addUser(tenantId, "owner@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID exec = addUser(tenantId, "exec@x.test", Role.SALES_EXEC, UserStatus.ACTIVE);

        TenantContext.TenantPrincipal caller = TenantContext.get().orElseThrow();
        tx.executeWithoutResult(s -> customers.save(
                new Customer("Shop A", null, "27", null, null, 0, exec, null, CustomerSource.MANUAL)));
        TenantContext.set(caller);

        ConflictException ex = assertThrows(ConflictException.class, () -> members.disable(exec));
        assertTrue(ex.getMessage().contains("1 customers"), "the message names what blocks it: " + ex.getMessage());
        assertEquals(1L, ex.getFields().get("customers"), "counts are machine-readable");
        assertFalse(ex.getFields().containsKey("enquiries"), "only non-zero blockers are reported");

        TenantContext.set(caller);
        assertEquals(UserStatus.ACTIVE, users.findById(exec).orElseThrow().getStatus(), "no partial mutation");
    }

    @Test
    void theLastActiveOwnerCannotBeDisabled() {
        UUID tenantId = tenantWithOwnerBound();
        UUID soleOwner = addUser(tenantId, "sole@x.test", Role.OWNER, UserStatus.ACTIVE);

        ConflictException ex = assertThrows(ConflictException.class, () -> members.disable(soleOwner));
        assertEquals("a workspace must keep at least one active owner", ex.getMessage());
    }

    @Test
    void enableRestoresADisabledMember() {
        UUID tenantId = tenantWithOwnerBound();
        addUser(tenantId, "owner@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID exec = addUser(tenantId, "exec@x.test", Role.SALES_EXEC, UserStatus.DISABLED);

        assertEquals("ACTIVE", members.enable(exec).status());
    }

    @Test
    void enablingAnActiveMemberConflicts() {
        UUID tenantId = tenantWithOwnerBound();
        addUser(tenantId, "owner@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID exec = addUser(tenantId, "exec@x.test", Role.SALES_EXEC, UserStatus.ACTIVE);

        assertThrows(ConflictException.class, () -> members.enable(exec));
    }
}
```

> Copy the real `Customer` constructor argument list from `Customer.java` as in Task 3.

- [ ] **Step 2: Run it and watch it fail**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.MemberDisableTest'
```

Expected: compilation failure — `disable` and `enable` do not exist on `MemberService`.

- [ ] **Step 3: Extend the constructor**

Add two dependencies to `MemberService`:

```java
    private final RefreshTokenService refreshTokens;
    private final List<AssignedWorkload> workloads;
```

and the matching constructor parameters, assigned in order. Spring injects every `AssignedWorkload` bean into the list.

- [ ] **Step 4: Add `disable`, `enable` and the gate**

```java
    @Transactional
    public MemberResponse disable(UUID id) {
        roleGuard.requireOwner("only an owner may disable a member");
        lockTenant();
        User member = requireMember(id);

        // Both invariants BEFORE any mutation, so a refusal leaves no partial state.
        if (member.getRole() == Role.OWNER) {
            requireAnotherActiveOwner(member);
        }
        requireNoOpenWork(member);

        member.disable(); // ConflictException if already disabled
        users.save(member);
        int revoked = refreshTokens.revokeAllForUser(member.getId(), TenantContext.tenantId());

        audit.record(
                "MEMBER_DISABLED",
                actorUserId(),
                Map.of("email", member.getEmail(), "sessionsRevoked", revoked));
        return toResponse(member);
    }

    @Transactional
    public MemberResponse enable(UUID id) {
        roleGuard.requireOwner("only an owner may enable a member");
        // Cannot breach the owner invariant (it only ever adds an active owner), but takes
        // the lock anyway: uniform is cheaper to reason about than per-path, and enable is
        // rare enough that the extra row lock costs nothing.
        lockTenant();
        User member = requireMember(id);

        member.enable(); // ConflictException if already active
        users.save(member);

        audit.record("MEMBER_ENABLED", actorUserId(), Map.of("email", member.getEmail()));
        return toResponse(member);
    }

    /**
     * A member who cannot log in cannot action their work, so disabling them while they hold
     * any would strand it. Refuses with a 409 naming every blocker at once — an owner who
     * clears customers, retries, then discovers follow-ups has been made to do the job twice.
     *
     * <p>Sorted by label so the message and the field order are deterministic: the injected
     * List's order is Spring's bean-definition order, which is not a contract.
     */
    private void requireNoOpenWork(User member) {
        Map<String, Object> blockers = new LinkedHashMap<>();
        workloads.stream().sorted(Comparator.comparing(AssignedWorkload::label)).forEach(w -> {
            long open = w.countOpenFor(member.getId());
            if (open > 0) blockers.put(w.label(), open);
        });
        if (blockers.isEmpty()) return;

        String detail = blockers.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey())
                .collect(Collectors.joining(", "));
        throw new ConflictException(
                "member still holds open work and cannot be disabled: " + detail + "; reassign it first", blockers);
    }
```

Add imports `java.util.Comparator`, `java.util.LinkedHashMap`, `java.util.stream.Collectors`.

- [ ] **Step 5: Run the test**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.MemberDisableTest'
```

Expected: PASS, 5 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/easycrm/iam/MemberService.java backend/src/test/java/com/easycrm/iam/MemberDisableTest.java
git commit -m "feat: disable and enable a member, gated on reassignment

Disable refuses while the member still holds active customers,
non-terminal enquiries or pending follow-ups, and the 409 names every
blocker at once with machine-readable counts so a client can route to the
right reassign screen. Both invariants run before any mutation, so a
refusal leaves no partial state.

On success the member's live sessions are revoked and the count goes into
the audit row."
```

---

### Task 7: The HTTP surface

**Files:**
- Create: `backend/src/main/java/com/easycrm/iam/web/MemberController.java`
- Create: `backend/src/main/java/com/easycrm/iam/web/dto/ChangeRoleRequest.java`
- Test: `backend/src/test/java/com/easycrm/iam/web/MemberControllerTest.java` (create)

**Interfaces:**
- Consumes: `MemberService` (Tasks 5, 6).
- Produces: the four routes in the spec's §2 table. No `SecurityConfig` change — `/api/**` is already `.authenticated()`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/iam/web/MemberControllerTest.java`:

```java
package com.easycrm.iam.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.iam.Role;
import com.easycrm.iam.User;
import com.easycrm.iam.UserRepository;
import com.easycrm.iam.UserStatus;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class MemberControllerTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    UserRepository users;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private UUID addUser(UUID tenantId, String email, Role role, UserStatus status) {
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"));
        try {
            return tx.execute(s -> users.save(new User(email, null, "hash", role, status))
                    .getId());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void ownerCanListMembers() throws Exception {
        var owner = tokens.provisionOwner("27");
        addUser(owner.tenantId(), "a@x.test", Role.OWNER, UserStatus.ACTIVE);

        mvc.perform(get("/api/v1/members").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("a@x.test"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void salesExecCannotListMembers() throws Exception {
        var owner = tokens.provisionOwner("27");
        String exec = tokens.as(owner.tenantId(), UUID.randomUUID(), "SALES_EXEC");

        mvc.perform(get("/api/v1/members").header("Authorization", "Bearer " + exec))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void anUnknownMemberIs404() throws Exception {
        var owner = tokens.provisionOwner("27");

        mvc.perform(post("/api/v1/members/" + UUID.randomUUID() + "/disable")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void anUnknownRoleIs400FromBeanValidation() throws Exception {
        var owner = tokens.provisionOwner("27");
        UUID member = addUser(owner.tenantId(), "a@x.test", Role.SALES_EXEC, UserStatus.ACTIVE);

        mvc.perform(post("/api/v1/members/" + member + "/role")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void changeRoleReturnsTheUpdatedMember() throws Exception {
        var owner = tokens.provisionOwner("27");
        addUser(owner.tenantId(), "keeper@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID member = addUser(owner.tenantId(), "a@x.test", Role.SALES_EXEC, UserStatus.ACTIVE);

        mvc.perform(post("/api/v1/members/" + member + "/role")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SALES_MANAGER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SALES_MANAGER"));
    }

    @Test
    void disableThenEnableRoundTrips() throws Exception {
        var owner = tokens.provisionOwner("27");
        addUser(owner.tenantId(), "keeper@x.test", Role.OWNER, UserStatus.ACTIVE);
        UUID member = addUser(owner.tenantId(), "a@x.test", Role.SALES_EXEC, UserStatus.ACTIVE);

        mvc.perform(post("/api/v1/members/" + member + "/disable")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mvc.perform(post("/api/v1/members/" + member + "/enable")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void anUnauthenticatedRequestIs401() throws Exception {
        mvc.perform(get("/api/v1/members")).andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.web.MemberControllerTest'
```

Expected: 404 on every route — the controller does not exist yet.

- [ ] **Step 3: Add the request DTO**

Create `backend/src/main/java/com/easycrm/iam/web/dto/ChangeRoleRequest.java`:

```java
package com.easycrm.iam.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * {@code role} is a validated String rather than a {@code Role} parameter, for the same
 * reason InviteRequest does it: an unknown value must be a 400 from bean validation, not a
 * Jackson deserialisation failure. The pattern is deliberately identical to InviteRequest's.
 */
public record ChangeRoleRequest(
        @NotBlank
        @Pattern(regexp = "OWNER|SALES_MANAGER|SALES_EXEC", message = "role must be OWNER, SALES_MANAGER or SALES_EXEC")
        String role) {}
```

- [ ] **Step 4: Add the controller**

Create `backend/src/main/java/com/easycrm/iam/web/MemberController.java`:

```java
package com.easycrm.iam.web;

import com.easycrm.iam.MemberService;
import com.easycrm.iam.web.dto.ChangeRoleRequest;
import com.easycrm.iam.web.dto.MemberResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated, owner-only. Every route needs a JWT, so unlike the invitations surface
 * there is no pre-auth half and no SecurityConfig change — /api/** is already authenticated.
 *
 * <p>Role change is POST /{id}/role rather than PATCH /{id}: PATCH house-wide is
 * full-header-replace, so a PATCH carrying only role would read as "clear the other fields
 * too". A verb sub-resource has no such ambiguity, and matches OrderController's transitions.
 */
@RestController
@RequestMapping("/api/v1/members")
public class MemberController {

    private final MemberService members;

    public MemberController(MemberService members) {
        this.members = members;
    }

    @GetMapping
    public List<MemberResponse> list() {
        return members.list();
    }

    @PostMapping("/{id}/role")
    public MemberResponse changeRole(@PathVariable UUID id, @Valid @RequestBody ChangeRoleRequest req) {
        return members.changeRole(id, req.role());
    }

    @PostMapping("/{id}/disable")
    public MemberResponse disable(@PathVariable UUID id) {
        return members.disable(id);
    }

    @PostMapping("/{id}/enable")
    public MemberResponse enable(@PathVariable UUID id) {
        return members.enable(id);
    }
}
```

- [ ] **Step 5: Run the test**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.web.MemberControllerTest'
```

Expected: PASS, 7 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/easycrm/iam/web backend/src/test/java/com/easycrm/iam/web/MemberControllerTest.java
git commit -m "feat: expose members management over HTTP

Four owner-only routes on /api/v1/members. Role change is a POST verb
sub-resource rather than a PATCH, because PATCH house-wide is
full-header-replace and would read as clearing the omitted fields."
```

---

### Task 8: Prove the tenant lock closes the write skew

A concurrency guard nobody has watched fail is not a guard — this is the discipline that caught challenge #33. **The mandatory step here is Step 4: remove the lock, watch the test fail, put it back.**

**Files:**
- Test: `backend/src/test/java/com/easycrm/iam/MemberOwnerRaceTest.java` (create)

**Interfaces:**
- Consumes: `MemberService.changeRole` (Task 5), `UserRepository.countByRoleAndStatus` (Task 5).
- Produces: nothing.

- [ ] **Step 1: Write the test**

Create `backend/src/test/java/com/easycrm/iam/MemberOwnerRaceTest.java`:

```java
package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The last-active-owner rule is check-then-act, and the anomaly it invites is WRITE SKEW:
 * two transactions read an overlapping set (the active owners) and then write DISJOINT rows
 * within it. @Version cannot see it (different rows), no constraint expresses "at least
 * one", and Postgres REPEATABLE READ does not detect it — only SERIALIZABLE does. The tenant
 * row lock materialises the conflict instead.
 */
class MemberOwnerRaceTest extends IntegrationTest {

    @Autowired
    MemberService members;

    @Autowired
    UserRepository users;

    @Autowired
    TestTokens tokens;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private UUID addOwner(UUID tenantId, String email) {
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"));
        try {
            return tx.execute(s -> users.save(new User(email, null, "hash", Role.OWNER, UserStatus.ACTIVE))
                    .getId());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void twoOwnersDemotingEachOtherCannotStrandTheWorkspace() throws Exception {
        var provisioned = tokens.provisionOwner("27");
        UUID tenantId = provisioned.tenantId();
        UUID asha = addOwner(tenantId, "asha@race.test");
        UUID bilal = addOwner(tenantId, "bilal@race.test");

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // Each thread acts AS one owner and demotes the OTHER, releasing together so both
        // count the active owners before either commits.
        Callable<Throwable> ashaDemotesBilal = demote(tenantId, asha, bilal, barrier);
        Callable<Throwable> bilalDemotesAsha = demote(tenantId, bilal, asha, barrier);

        List<Future<Throwable>> results = pool.invokeAll(List.of(ashaDemotesBilal, bilalDemotesAsha));
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "both attempts finished");

        long succeeded = results.stream().filter(f -> outcome(f) == null).count();
        assertEquals(1, succeeded, "exactly one demotion may win");

        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"));
        long remaining = tx.execute(s -> users.countByRoleAndStatus(Role.OWNER, UserStatus.ACTIVE));
        assertEquals(1, remaining, "the workspace must never be left without an active owner");
    }

    private Callable<Throwable> demote(UUID tenantId, UUID actor, UUID target, CyclicBarrier barrier) {
        return () -> {
            // TenantContext is a ThreadLocal, so each thread binds its own principal.
            TenantContext.set(new TenantContext.TenantPrincipal(tenantId, actor, "OWNER"));
            try {
                barrier.await(10, TimeUnit.SECONDS);
                members.changeRole(target, "SALES_EXEC");
                return null;
            } catch (Throwable t) {
                return t;
            } finally {
                TenantContext.clear();
            }
        };
    }

    private Throwable outcome(Future<Throwable> f) {
        try {
            return f.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return e;
        }
    }
}
```

- [ ] **Step 2: Run it**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.MemberOwnerRaceTest'
```

Expected: PASS.

- [ ] **Step 3: Prove it can fail — temporarily remove the lock**

In `MemberService.lockTenant()`, comment out the body so it does nothing:

```java
    void lockTenant() {
        // TEMPORARY: proving MemberOwnerRaceTest actually detects the write skew.
        // tenants.findForUpdate(TenantContext.tenantId())
        //         .orElseThrow(() -> new IllegalStateException("no tenant row for the authenticated tenant"));
    }
```

- [ ] **Step 4: Run it again and confirm it FAILS**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.MemberOwnerRaceTest'
```

Expected: **FAIL** — both demotions succeed and `remaining` is 0, or `succeeded` is 2.

**If it still passes, the test is not exercising the race** (the two threads are serialising somewhere else, or the barrier is not releasing them together). Fix the test until it fails here. Do not proceed with a guard you have not seen catch anything.

- [ ] **Step 5: Restore the lock and re-run**

Restore `lockTenant()` exactly as Task 5 wrote it, then:

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.MemberOwnerRaceTest'
```

Expected: PASS.

- [ ] **Step 6: Run the full check**

```bash
cd backend && ./gradlew clean check
```

Expected: BUILD SUCCESSFUL. Count the tests and record the number for Task 9.

- [ ] **Step 7: Commit**

```bash
git add backend/src/test/java/com/easycrm/iam/MemberOwnerRaceTest.java
git commit -m "test: pin the last-owner write skew against the tenant lock

Two owners demote each other from a CyclicBarrier so both count the
active owners before either commits. Verified to FAIL with lockTenant()
emptied — both demotions commit and the workspace is left with zero
active owners — and to pass with it restored."
```

---

### Task 9: Documentation wrap-up

**Files:**
- Modify: `docs/superpowers/HANDOFF.md`
- Modify: `docs/superpowers/engineering-challenges.md`
- Modify: `docs/superpowers/annotations-reference.md`

- [ ] **Step 1: Log the engineering challenges**

Append two entries using the template at the bottom of `engineering-challenges.md`, numbered from whatever the current last entry is (the file ended at #61 when this plan was written — check, do not assume):

**Entry A — the last-owner invariant is write skew, not a lost update.** Problem: two owners demoting each other concurrently both pass a `count(active owners) > 1` check and strand the workspace at zero. Why it is hard: every defence this codebase already owns misses it — `@Version` guards a single row and these are disjoint rows; a unique index expresses *at most one*, and there is no declarative form of *at least one*; and Postgres REPEATABLE READ is snapshot isolation, which aborts write-write conflicts but not write skew. The damage is also unrecoverable in-product, since every member-admin route calls `RoleGuard.requireOwner` and there is no support surface. Solution: materialise the conflict onto a row both transactions must touch — `SELECT ... FOR UPDATE` on the tenant row — rather than escalating to SERIALIZABLE and taking retry handling everywhere. Lesson: when a check reads a *set* and writes a *row*, row-level optimistic locking is structurally unable to see the conflict; either raise isolation or invent a shared row to contend on.

**Entry B — a gate that must not be filtered, in a codebase where reads are filtered by default.** Problem: the reassign-first gate needs tenant-wide counts from three repositories that `VisibilityScopingArchTest` allows only `platform.visibility` to read, while `iam` must not depend on `crm`/`sales`. Why it is hard: the obvious route, `VisibleFinder`, returns the correct number *today* only because owners are unrestricted — it would silently start hiding rows if a non-owner ever reached the path, and an under-counting invariant check lets a disable through while work remains assigned. Solution: declare the `AssignedWorkload` port in `iam` and implement it in `crm`/`sales`, reusing the dependency edge those packages already have on `iam`, and allowlist the count methods so the "never filtered" property is structural rather than incidental. Lesson: an invariant check and a user-facing read have opposite requirements about visibility filtering; sharing one mechanism between them is a latent correctness bug, not a DRY win.

- [ ] **Step 2: Update the annotations reference**

Check whether `@Lock` and `jakarta.persistence.LockModeType` already have rows (`DocumentCounterRepository` uses them, so they may). If not, add a row: origin `org.springframework.data.jpa.repository.Lock` / `jakarta.persistence`, purpose "apply a JPA lock mode to a query method — `PESSIMISTIC_WRITE` emits `SELECT ... FOR UPDATE`", used by `DocumentCounterRepository.findForUpdate` and `TenantRepository.findForUpdate`. Every other annotation this slice uses (`@Pattern`, `@NotBlank`, `@Valid`, `@GetMapping`, `@PostMapping`, `@PathVariable`, `@RequestBody`, `@Component`, `@Service`, `@Transactional`) is already documented.

- [ ] **Step 3: Update the handoff**

In `HANDOFF.md`:
- Rewrite the header block and §0's "Nothing is in flight" to describe this slice as the latest code work, with its branch and merge commit. **§0 also currently claims `main` is the baseline and nothing is in flight, which was already stale when this slice began** — the `openapi-contract` slice was mid-execution. Correct that too.
- Add a §3 entry: the four routes, the `AssignedWorkload` inversion, the tenant lock, the `AuthService.refresh` fix, the `ConflictException` fields addition, the `V33` indexes, and the new test count.
- In §8, strike "Members management" from the candidate list and record what it does **not** do: no delete, no bulk reassignment, no self-service profile editing, `SALES_MANAGER` still collapsed into the unrestricted tier.
- Record the two carried-forward notes: the ≤15-minute access-token window after a disable or demotion (spec §6.1), and the suspended-tenant hole still open in `AuthService.refresh` (spec §10).

- [ ] **Step 4: Run the full check one last time**

```bash
cd backend && ./gradlew clean check
```

- [ ] **Step 5: Commit**

```bash
git add docs/
git commit -m "docs: record the members-management slice

Handoff, two engineering-challenges entries (the last-owner write skew
and the invariant-check-must-not-filter tension), and the annotations
reference. Also corrects §0, which claimed nothing was in flight while
the openapi-contract slice was mid-execution."
```

---

## Completion

- [ ] `./gradlew clean check` is green from a clean state
- [ ] `MemberOwnerRaceTest` was seen to **fail** with `lockTenant()` emptied (Task 8, Step 4)
- [ ] `AuthServiceRefreshTest.aDisabledMemberCannotRefresh` was seen to **fail** before the `AuthService.refresh` fix (Task 4, Step 2)
- [ ] `VisibilityScopingArchTest` was seen to **fail** before the three allowlist entries were added (Task 3, Step 6)
- [ ] Every 409 other than the reassign-first gate still serializes without a `fields` key
- [ ] No response body anywhere carries `passwordHash`
- [ ] `iam` imports nothing from `crm` or `sales` (`grep -rn "import com.easycrm.\(crm\|sales\)" backend/src/main/java/com/easycrm/iam/` returns nothing)
- [ ] The two engineering-challenges entries are logged
- [ ] The handoff's stale "nothing is in flight" claim is corrected
