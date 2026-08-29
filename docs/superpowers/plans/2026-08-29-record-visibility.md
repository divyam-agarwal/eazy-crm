# Record-Level Visibility Filtering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop every authenticated user in a tenant from reading and mutating every record in it — a `SALES_EXEC` sees only records assigned to them plus unassigned ones, across customers, enquiries, quotations and orders, on reads and writes alike.

**Architecture:** A new `com.easycrm.platform.visibility` package holds a `VisibilityPolicy` (role → JPA `Specification` per aggregate) and a `VisibleFinder` (the only class permitted to call read methods on the four guarded repositories). Each service's existing single by-id choke point delegates to the finder, which covers reads and mutations at once. An ArchUnit allowlist rule fails the build when a new repository read bypasses the layer.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring Data JPA + Hibernate 7, Postgres 16 (Testcontainers), JUnit 5, ArchUnit, Gradle (two projects: root + `:platform:platform-primitives`).

**Spec:** `docs/superpowers/specs/2026-08-29-record-visibility-design.md` — read it before Task 1. This plan argues from it; where they disagree, the spec wins and the plan is wrong.

## Global Constraints

- **Baseline before starting:** `main` at `55a6b22` or later, **296 tests, 0 failures, 0 errors**. Confirm with the counting snippet in `HANDOFF.md` §0 item 1 — Gradle prints no total for a multi-project build.
- **A filtered test run must be project-qualified.** Use `./gradlew :test --tests '<filter>'` for root-project tests. Bare `./gradlew test --tests '…'` applies the filter to *both* projects and fails on the one with no match. Every command in this plan is already qualified — do not "fix" them.
- **Commit as `divyam`.** Plain `git commit`, no `-c user.name=` override, no `Co-Authored-By` trailer, no mention of Claude or AI anywhere in a commit message.
- **No new columns and no migration.** The design needs none (spec §4). If a task seems to need one, the task is wrong — stop and re-read the spec.
- **Money stays `BigDecimal`.** Nothing here touches money, but the rule holds if you find yourself near it.
- **Tenant isolation is not this layer's job.** Never hand-write `WHERE tenant_id = ?`. RLS and `@TenantId` handle it, including inside the subquery this plan introduces.
- **404, never 403,** for an invisible record. Cross-tenant and not-visible must stay indistinguishable to the caller.
- **Docker must be running** (`open -a Docker`, wait for `docker info`) — every integration test uses Testcontainers.

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `backend/src/main/java/com/easycrm/platform/visibility/VisibilityPolicy.java` | Role → `Specification` per aggregate. The only place the rule is written down. |
| `backend/src/main/java/com/easycrm/platform/visibility/VisibleFinder.java` | The single permitted reader of the four guarded repositories. |
| `backend/src/main/java/com/easycrm/crm/CustomerSpecifications.java` | `active` filter, replacing the `findByActive` derived query. |
| `backend/src/test/java/com/easycrm/platform/visibility/VisibilityPolicyIntegrationTest.java` | Proves each specification's SQL, including the subquery. |
| `backend/src/test/java/com/easycrm/platform/visibility/VisibleFinderIntegrationTest.java` | Proves by-id and paged finders filter. |
| `backend/src/test/java/com/easycrm/crm/CustomerVisibilityTest.java` | Endpoint-level customer visibility, read + write. |
| `backend/src/test/java/com/easycrm/sales/EnquiryVisibilityTest.java` | Enquiry visibility + the unfiltered dedupe lane. |
| `backend/src/test/java/com/easycrm/sales/QuotationOrderVisibilityTest.java` | Derive-through-customer visibility for both aggregates. |
| `backend/src/test/java/com/easycrm/sales/NestedVisibilityTest.java` | Contacts, PDF render, share-link mint. |
| `backend/src/test/java/com/easycrm/arch/VisibilityScopingArchTest.java` | The allowlist guard. |

**Modified:**

| File | Change |
|---|---|
| `crm/CustomerRepository.java` | Add `JpaSpecificationExecutor<Customer>`; delete `findByActive`. |
| `crm/CustomerService.java` | `find` and `list` go through `VisibleFinder`; `assignedTo` validation. |
| `crm/ContactService.java` | `requireCustomer` and `find` gate on the parent customer's visibility. |
| `sales/EnquiryService.java` | `find` and `list` through the finder; `assignedTo` validation. |
| `sales/QuotationService.java` | `findQuotation` and `list` through the finder. |
| `sales/OrderService.java` | `find` and `list` through the finder; reword the stale 404 comment. |
| `sales/ShareLinkService.java` | Quotation load through the finder. |
| `sales/pdf/QuotationPdfService.java` | Both entry points re-check the quotation through the finder. |
| `backend/src/test/java/com/easycrm/support/TestTokens.java` | Mint a token for an explicit user id and role. |
| `docs/superpowers/engineering-challenges.md` | Challenges 43 and 44. |
| `docs/superpowers/annotations-reference.md` | Any new annotation rows. |
| `docs/superpowers/HANDOFF.md` | §3 inventory, §8 re-rank. |

---

### Task 1: `VisibilityPolicy` — the rule, and the test harness to exercise it

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/visibility/VisibilityPolicy.java`
- Modify: `backend/src/test/java/com/easycrm/support/TestTokens.java`
- Test: `backend/src/test/java/com/easycrm/platform/visibility/VisibilityPolicyIntegrationTest.java`

**Interfaces:**
- Consumes: `TenantContext.TenantPrincipal` (existing — carries `tenantId`, `userId`, `role`).
- Produces:
  - `VisibilityPolicy.unrestricted() : boolean`
  - `VisibilityPolicy.customers() : Specification<Customer>`
  - `VisibilityPolicy.enquiries() : Specification<Enquiry>`
  - `VisibilityPolicy.quotations() : Specification<Quotation>`
  - `VisibilityPolicy.orders() : Specification<Order>`
  - `TestTokens.as(UUID tenantId, UUID userId, String role) : String`

**Why this task exists first:** the customer-derived subquery is the only genuinely uncertain
mechanism in this slice. Proving it against a real Postgres before anything depends on it means a
failure here costs one task, not six.

- [ ] **Step 1: Add the test-token helper**

`TestTokens` currently only mints `OWNER` with a random user id. Every later test needs a specific
`(userId, role)` pair. Add this method — do not change the existing ones, 296 tests depend on them:

```java
    /**
     * A bearer token for an explicit principal. Visibility filtering keys on userId and
     * role, so a test that exercises it cannot use owner()/provisionOwner() — those mint a
     * random user id and always the unrestricted OWNER role.
     */
    public String as(UUID tenantId, UUID userId, String role) {
        return jwt.mint(tenantId, userId, role);
    }
```

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/easycrm/platform/visibility/VisibilityPolicyIntegrationTest.java`.

This test drives the specifications through the real repositories, because the thing most likely to
be wrong is the generated SQL, not the Java. It seeds one tenant with two "users" (plain UUIDs — no
`User` rows are needed, the JWT is the only source of identity here) and rows in three states:
assigned to A, assigned to B, unassigned.

```java
package com.easycrm.platform.visibility;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.Order;
import com.easycrm.sales.OrderRepository;
import com.easycrm.sales.Quotation;
import com.easycrm.sales.QuotationRepository;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VisibilityPolicyIntegrationTest extends IntegrationTest {

    @Autowired VisibilityPolicy policy;
    @Autowired CustomerRepository customers;
    @Autowired QuotationRepository quotations;
    @Autowired OrderRepository orders;
    @Autowired TestTokens tokens;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID execA = UUID.randomUUID();
    private final UUID execB = UUID.randomUUID();

    private UUID customerA, customerB, customerUnassigned;
    private UUID quoteA, quoteB, quoteUnassigned;

    @BeforeEach
    void seed() {
        asPrincipal(execA, "OWNER", () -> {
            customerA = save(newCustomer("A Traders", execA)).getId();
            customerB = save(newCustomer("B Traders", execB)).getId();
            customerUnassigned = save(newCustomer("Pool Traders", null)).getId();
            quoteA = saveQuote(customerA);
            quoteB = saveQuote(customerB);
            quoteUnassigned = saveQuote(customerUnassigned);
        });
    }

    @Test
    void ownerSeesEveryCustomer() {
        asPrincipal(execA, "OWNER", () -> {
            assertThat(policy.unrestricted()).isTrue();
            assertThat(ids(customers.findAll(policy.customers())))
                .containsExactlyInAnyOrder(customerA, customerB, customerUnassigned);
        });
    }

    @Test
    void salesManagerSeesEveryCustomer() {
        asPrincipal(execA, "SALES_MANAGER", () -> {
            assertThat(policy.unrestricted()).isTrue();
            assertThat(ids(customers.findAll(policy.customers()))).hasSize(3);
        });
    }

    @Test
    void salesExecSeesOwnAndUnassignedCustomersOnly() {
        asPrincipal(execA, "SALES_EXEC", () -> {
            assertThat(policy.unrestricted()).isFalse();
            assertThat(ids(customers.findAll(policy.customers())))
                .containsExactlyInAnyOrder(customerA, customerUnassigned)
                .doesNotContain(customerB);
        });
    }

    @Test
    void salesExecSeesQuotationsThroughTheirCustomer() {
        asPrincipal(execA, "SALES_EXEC", () ->
            assertThat(ids(quotations.findAll(policy.quotations())))
                .containsExactlyInAnyOrder(quoteA, quoteUnassigned)
                .doesNotContain(quoteB));
    }

    @Test
    void salesExecSeesOrdersThroughTheirCustomer() {
        UUID orderA = asPrincipalGet(execA, "OWNER", () -> saveOrder(customerA, quoteA));
        UUID orderB = asPrincipalGet(execA, "OWNER", () -> saveOrder(customerB, quoteB));

        asPrincipal(execA, "SALES_EXEC", () ->
            assertThat(ids(orders.findAll(policy.orders())))
                .contains(orderA)
                .doesNotContain(orderB));
    }

    /** A principal with no user id at all must not be silently restricted to nothing. */
    @Test
    void absentPrincipalIsUnrestricted() {
        TenantContext.clear();
        assertThat(policy.unrestricted()).isTrue();
    }

    // --- helpers -------------------------------------------------------------

    private void asPrincipal(UUID userId, String role, Runnable body) {
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, userId, role),
            () -> tx.executeWithoutResult(s -> body.run()));
    }

    private <T> T asPrincipalGet(UUID userId, String role, java.util.function.Supplier<T> body) {
        return TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, userId, role),
            () -> tx.execute(s -> body.get()));
    }

    private Customer newCustomer(String name, UUID assignedTo) {
        return new Customer(name, null, "27", "addr", "addr", 0,
            assignedTo, null, CustomerSource.DIRECT);
    }

    private Customer save(Customer c) { return customers.save(c); }

    private UUID saveQuote(UUID customerId) {
        return quotations.save(new Quotation(customerId, null)).getId();
    }

    private UUID saveOrder(UUID customerId, UUID quotationId) {
        return orders.save(new Order(quotationId, UUID.randomUUID(), customerId,
            "SO-" + UUID.randomUUID().toString().substring(0, 8),
            LocalDate.now(), BigDecimal.ZERO)).getId();
    }

    private static List<UUID> ids(List<? extends com.easycrm.platform.persistence.BaseEntity> rows) {
        return rows.stream().map(com.easycrm.platform.persistence.BaseEntity::getId).toList();
    }
}
```

**Fixture warning — verify before assuming.** `Customer`, `Quotation` and `Order` constructor
signatures are copied from the entities as they stand at `55a6b22`, and `CustomerSource.DIRECT` is a
guess at an enum constant. Open `crm/Customer.java`, `crm/CustomerSource.java`, `sales/Quotation.java`
and `sales/Order.java` and correct the three helper methods to the real signatures before running
anything. Adjusting a fixture to compile is expected; adjusting an **assertion** to pass is not.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :test --tests 'com.easycrm.platform.visibility.VisibilityPolicyIntegrationTest'`
Expected: FAIL to compile — `VisibilityPolicy` does not exist.

- [ ] **Step 4: Write the implementation**

Create `backend/src/main/java/com/easycrm/platform/visibility/VisibilityPolicy.java`:

```java
package com.easycrm.platform.visibility;

import com.easycrm.crm.Customer;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.Enquiry;
import com.easycrm.sales.Order;
import com.easycrm.sales.Quotation;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Intra-tenant record visibility — a PRODUCT rule, deliberately not the tenant wall.
 * RLS and {@code @TenantId} enforce the tenant boundary; this decides which of a tenant's
 * own rows a principal may see. See spec 2026-08-29-record-visibility-design.md §1.
 */
@Component
public class VisibilityPolicy {

    /**
     * Only SALES_EXEC is restricted; every other role — and an absent principal — is
     * unrestricted.
     *
     * <p>This is a deliberate fail-OPEN default, and it is safe only because this class is
     * not a security boundary. The tenant wall is RLS, which still applies to every query
     * built here. Two cases depend on it: internal flows that run with no principal or a
     * synthetic one (async listeners, tenant provisioning), and any role added later, which
     * must not start silently hiding rows from users who could see them the day before.
     * A new restricted role is an explicit edit to this method.
     */
    public boolean unrestricted() {
        return TenantContext.get()
            .map(p -> !"SALES_EXEC".equals(p.role()))
            .orElse(true);
    }

    public Specification<Customer> customers() { return ownedOrUnassigned(); }

    public Specification<Enquiry> enquiries() { return ownedOrUnassigned(); }

    public Specification<Quotation> quotations() { return viaCustomer("customerId"); }

    public Specification<Order> orders() { return viaCustomer("customerId"); }

    /** The row carries its own assigned_to. */
    private <T> Specification<T> ownedOrUnassigned() {
        if (unrestricted()) return unrestrictedSpec();
        UUID me = currentUserId();
        return (root, query, cb) -> cb.or(
            cb.equal(root.get("assignedTo"), me),
            cb.isNull(root.get("assignedTo")));
    }

    /**
     * The row has no assigned_to of its own and derives visibility from its customer.
     * The subquery's Customer root is itself {@code @TenantId}-scoped and runs under RLS,
     * so it cannot reach another tenant's customers.
     */
    private <T> Specification<T> viaCustomer(String customerIdAttribute) {
        if (unrestricted()) return unrestrictedSpec();
        UUID me = currentUserId();
        return (root, query, cb) -> {
            Subquery<UUID> sub = query.subquery(UUID.class);
            Root<Customer> c = sub.from(Customer.class);
            sub.select(c.get("id"));
            sub.where(cb.and(
                cb.equal(c.get("id"), root.get(customerIdAttribute)),
                cb.or(cb.equal(c.get("assignedTo"), me), cb.isNull(c.get("assignedTo")))));
            return cb.exists(sub);
        };
    }

    /** Empty conjunction — the same always-true idiom OrderSpecifications.filter uses. */
    private static <T> Specification<T> unrestrictedSpec() {
        return (root, query, cb) -> cb.and();
    }

    private static UUID currentUserId() {
        return TenantContext.get().map(TenantContext.TenantPrincipal::userId).orElse(null);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :test --tests 'com.easycrm.platform.visibility.VisibilityPolicyIntegrationTest'`
Expected: PASS, 6 tests.

If `salesExecSeesQuotationsThroughTheirCustomer` fails with a Hibernate criteria error about the
subquery, the likely cause is `query` being the count query during a `findAll(spec)` — it is not here
(no `Pageable`), but Task 2 does page. Do not paper over it with a `query.getResultType()` check;
read the actual exception first.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew :test`
Expected: 0 failures. The new tests are additive — every existing test mints `OWNER` and lands on
the unrestricted path.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/easycrm/platform/visibility/VisibilityPolicy.java \
        backend/src/test/java/com/easycrm/platform/visibility/VisibilityPolicyIntegrationTest.java \
        backend/src/test/java/com/easycrm/support/TestTokens.java
git commit -m "feat: visibility policy — role to specification, per aggregate

Customer and enquiry filter on their own assigned_to; quotation and order
derive from their customer's via an EXISTS subquery, which needs no new
column and no fallback because customer_id is NOT NULL on both.

Only SALES_EXEC is restricted. Everything else, including an absent
principal, is unrestricted -- a fail-open default that is safe because
this is a product rule and RLS remains the tenant boundary underneath it."
```

---

### Task 2: `VisibleFinder` — the single permitted reader

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/visibility/VisibleFinder.java`
- Modify: `backend/src/main/java/com/easycrm/crm/CustomerRepository.java`
- Test: `backend/src/test/java/com/easycrm/platform/visibility/VisibleFinderIntegrationTest.java`

**Interfaces:**
- Consumes: `VisibilityPolicy` (Task 1) — all five methods.
- Produces:
  - `VisibleFinder.findCustomer(UUID) : Optional<Customer>`
  - `VisibleFinder.findEnquiry(UUID) : Optional<Enquiry>`
  - `VisibleFinder.findQuotation(UUID) : Optional<Quotation>`
  - `VisibleFinder.findOrder(UUID) : Optional<Order>`
  - `VisibleFinder.pageCustomers(Specification<Customer>, Pageable) : Page<Customer>`
  - `VisibleFinder.pageEnquiries(Specification<Enquiry>, Pageable) : Page<Enquiry>`
  - `VisibleFinder.pageQuotations(Specification<Quotation>, Pageable) : Page<Quotation>`
  - `VisibleFinder.pageOrders(Specification<Order>, Pageable) : Page<Order>`

- [ ] **Step 1: Give `CustomerRepository` a specification executor**

Modify `backend/src/main/java/com/easycrm/crm/CustomerRepository.java`. Add the interface and the
import; **leave `findByActive` alone for now** — Task 3 deletes it, and deleting it here would break
`CustomerService` before its replacement exists.

```java
public interface CustomerRepository
        extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {
```

with `import org.springframework.data.jpa.repository.JpaSpecificationExecutor;`.

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/easycrm/platform/visibility/VisibleFinderIntegrationTest.java`.
Reuse the seeding shape from Task 1 — repeated deliberately, because the two tests must be able to
fail independently.

```java
package com.easycrm.platform.visibility;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VisibleFinderIntegrationTest extends IntegrationTest {

    @Autowired VisibleFinder finder;
    @Autowired CustomerRepository customers;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID execA = UUID.randomUUID();
    private final UUID execB = UUID.randomUUID();

    private UUID mine, theirs, pool;

    @BeforeEach
    void seed() {
        run(execA, "OWNER", () -> {
            mine   = customers.save(customer("Mine", execA)).getId();
            theirs = customers.save(customer("Theirs", execB)).getId();
            pool   = customers.save(customer("Pool", null)).getId();
        });
    }

    @Test
    void byIdReturnsAVisibleRecord() {
        run(execA, "SALES_EXEC", () ->
            assertThat(finder.findCustomer(mine)).isPresent());
    }

    @Test
    void byIdReturnsEmptyForAnInvisibleRecord() {
        run(execA, "SALES_EXEC", () ->
            assertThat(finder.findCustomer(theirs)).isEmpty());
    }

    @Test
    void byIdReturnsAnUnassignedRecord() {
        run(execA, "SALES_EXEC", () ->
            assertThat(finder.findCustomer(pool)).isPresent());
    }

    @Test
    void ownerSeesEvenAnotherExecsRecordById() {
        run(execA, "OWNER", () ->
            assertThat(finder.findCustomer(theirs)).isPresent());
    }

    /** The paging path builds a COUNT query too — the subquery must survive both. */
    @Test
    void pagingAppliesVisibilityToBothTheDataAndCountQueries() {
        run(execA, "SALES_EXEC", () -> {
            var page = finder.pageCustomers(null, PageRequest.of(0, 50));
            assertThat(page.getContent()).extracting(Customer::getId)
                .containsExactlyInAnyOrder(mine, pool);
            assertThat(page.getTotalElements()).isEqualTo(2);
        });
    }

    private void run(UUID userId, String role, Runnable body) {
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, userId, role),
            () -> tx.executeWithoutResult(s -> body.run()));
    }

    private Customer customer(String name, UUID assignedTo) {
        return new Customer(name, null, "27", "addr", "addr", 0,
            assignedTo, null, CustomerSource.DIRECT);
    }
}
```

`pagingAppliesVisibilityToBothTheDataAndCountQueries` is the load-bearing case here — asserting
`getTotalElements()` and not just the content is what catches a visibility predicate that the count
query drops. Do not delete that assertion if it is inconvenient.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :test --tests 'com.easycrm.platform.visibility.VisibleFinderIntegrationTest'`
Expected: FAIL to compile — `VisibleFinder` does not exist.

- [ ] **Step 4: Write the implementation**

Create `backend/src/main/java/com/easycrm/platform/visibility/VisibleFinder.java`:

```java
package com.easycrm.platform.visibility;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.sales.Enquiry;
import com.easycrm.sales.EnquiryRepository;
import com.easycrm.sales.Order;
import com.easycrm.sales.OrderRepository;
import com.easycrm.sales.Quotation;
import com.easycrm.sales.QuotationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * The ONLY class permitted to call a read method on the four visibility-scoped
 * repositories. VisibilityScopingArchTest fails the build on any other caller — see spec
 * 2026-08-29-record-visibility-design.md §8. Services keep their repositories for save()
 * and nothing else.
 */
@Component
public class VisibleFinder {

    private final VisibilityPolicy policy;
    private final CustomerRepository customers;
    private final EnquiryRepository enquiries;
    private final QuotationRepository quotations;
    private final OrderRepository orders;

    public VisibleFinder(VisibilityPolicy policy, CustomerRepository customers,
                         EnquiryRepository enquiries, QuotationRepository quotations,
                         OrderRepository orders) {
        this.policy = policy;
        this.customers = customers;
        this.enquiries = enquiries;
        this.quotations = quotations;
        this.orders = orders;
    }

    public Optional<Customer> findCustomer(UUID id) {
        return customers.findOne(policy.customers().and(hasId(id)));
    }

    public Optional<Enquiry> findEnquiry(UUID id) {
        return enquiries.findOne(policy.enquiries().and(hasId(id)));
    }

    public Optional<Quotation> findQuotation(UUID id) {
        return quotations.findOne(policy.quotations().and(hasId(id)));
    }

    public Optional<Order> findOrder(UUID id) {
        return orders.findOne(policy.orders().and(hasId(id)));
    }

    public Page<Customer> pageCustomers(Specification<Customer> filter, Pageable pageable) {
        return customers.findAll(policy.customers().and(filter), pageable);
    }

    public Page<Enquiry> pageEnquiries(Specification<Enquiry> filter, Pageable pageable) {
        return enquiries.findAll(policy.enquiries().and(filter), pageable);
    }

    public Page<Quotation> pageQuotations(Specification<Quotation> filter, Pageable pageable) {
        return quotations.findAll(policy.quotations().and(filter), pageable);
    }

    public Page<Order> pageOrders(Specification<Order> filter, Pageable pageable) {
        return orders.findAll(policy.orders().and(filter), pageable);
    }

    private static <T> Specification<T> hasId(UUID id) {
        return (root, query, cb) -> cb.equal(root.get("id"), id);
    }
}
```

`Specification.and(null)` is null-safe in Spring Data JPA 3+, which is why `pageCustomers(null, …)`
works in the test. If the version in use rejects it, wrap with
`filter == null ? base : base.and(filter)` rather than changing the test.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :test --tests 'com.easycrm.platform.visibility.VisibleFinderIntegrationTest'`
Expected: PASS, 5 tests.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew :test`
Expected: 0 failures.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/easycrm/platform/visibility/VisibleFinder.java \
        backend/src/main/java/com/easycrm/crm/CustomerRepository.java \
        backend/src/test/java/com/easycrm/platform/visibility/VisibleFinderIntegrationTest.java
git commit -m "feat: VisibleFinder, the single permitted reader of scoped repositories

Funnelling every read through one class is what makes the ArchUnit guard
in a later task expressible as a package rule rather than a brittle
method-name heuristic.

The paging test asserts getTotalElements as well as content: a visibility
predicate that the COUNT query silently drops would pass a content-only
assertion while reporting the wrong total."
```

---

### Task 3: Customer read and write paths

**Files:**
- Create: `backend/src/main/java/com/easycrm/crm/CustomerSpecifications.java`
- Modify: `backend/src/main/java/com/easycrm/crm/CustomerService.java`, `backend/src/main/java/com/easycrm/crm/CustomerRepository.java`
- Test: `backend/src/test/java/com/easycrm/crm/CustomerVisibilityTest.java`

**Interfaces:**
- Consumes: `VisibleFinder.findCustomer`, `VisibleFinder.pageCustomers` (Task 2).
- Produces: `CustomerSpecifications.filter(Boolean active) : Specification<Customer>`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/crm/CustomerVisibilityTest.java`. This is an
endpoint-level test — the point is that the HTTP contract 404s, not that a method returns empty.

Model the MockMvc setup on an existing endpoint test in `com.easycrm.crm` (open one and copy its
`@AutoConfigureMockMvc` / builder wiring rather than inventing it). The cases:

```java
    @Test
    void execCannotGetAnotherExecsCustomer() throws Exception {
        mvc.perform(get("/api/v1/customers/" + theirs).header(AUTH, bearer(execAToken)))
            .andExpect(status().isNotFound());
    }

    @Test
    void execCanGetTheirOwnCustomer() throws Exception {
        mvc.perform(get("/api/v1/customers/" + mine).header(AUTH, bearer(execAToken)))
            .andExpect(status().isOk());
    }

    @Test
    void execCanGetAnUnassignedCustomer() throws Exception {
        mvc.perform(get("/api/v1/customers/" + pool).header(AUTH, bearer(execAToken)))
            .andExpect(status().isOk());
    }

    @Test
    void ownerCanGetAnyCustomer() throws Exception {
        mvc.perform(get("/api/v1/customers/" + theirs).header(AUTH, bearer(ownerToken)))
            .andExpect(status().isOk());
    }

    @Test
    void execListOmitsAnotherExecsCustomer() throws Exception {
        mvc.perform(get("/api/v1/customers").header(AUTH, bearer(execAToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[*].id").value(not(hasItem(theirs.toString()))));
    }

    /** WRITE coverage. Without this the layer is cosmetic: a read filter alone still lets
     *  an exec who knows an id mutate a record they cannot see. */
    @Test
    void execCannotPatchAnotherExecsCustomer() throws Exception {
        mvc.perform(patch("/api/v1/customers/" + theirs)
                .header(AUTH, bearer(execAToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCustomerJson()))
            .andExpect(status().isNotFound());
    }

    @Test
    void execCannotDeactivateAnotherExecsCustomer() throws Exception {
        mvc.perform(post("/api/v1/customers/" + theirs + "/deactivate")
                .header(AUTH, bearer(execAToken)))
            .andExpect(status().isNotFound());
    }

    /** The active filter must still work after findByActive is deleted. */
    @Test
    void activeFilterStillWorksForAnOwner() throws Exception {
        mvc.perform(get("/api/v1/customers?active=false").header(AUTH, bearer(ownerToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[*].id").value(not(hasItem(mine.toString()))));
    }
```

Check `CustomerController` for the real deactivate verb and path before writing that case — this plan
asserts `POST /{id}/deactivate` from the service method name, which is an inference, not a reading.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :test --tests 'com.easycrm.crm.CustomerVisibilityTest'`
Expected: FAIL — the 404 cases return 200, because nothing filters yet.

Confirm the failure is 200-instead-of-404 and not a wiring error. A test that fails for the wrong
reason proves nothing about what it is supposed to prove.

- [ ] **Step 3: Add `CustomerSpecifications`**

Create `backend/src/main/java/com/easycrm/crm/CustomerSpecifications.java`, mirroring the three
existing `*Specifications` classes exactly:

```java
package com.easycrm.crm;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class CustomerSpecifications {

    private CustomerSpecifications() {}

    /** AND-composes whichever filters are non-null. Tenant scoping comes from RLS, not here. */
    public static Specification<Customer> filter(Boolean active) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (active != null) ps.add(cb.equal(root.get("active"), active));
            return cb.and(ps.toArray(new Predicate[0])); // empty -> always-true conjunction
        };
    }
}
```

This is the fourth class using string-keyed `root.get(...)`. Deferred minor #9 covers migrating all
four to a JPA static metamodel — **do not do it here**; fixing one alone makes the set inconsistent.

- [ ] **Step 4: Re-point `CustomerService`**

Replace the repository read calls. `find` is the choke point every mutation already uses, so this
one edit covers `get`, `update`, `deactivate` and `activate`:

```java
    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> list(Boolean active, Pageable pageable) {
        return PageResponse.of(
            finder.pageCustomers(CustomerSpecifications.filter(active), pageable)
                .map(CustomerResponse::of));
    }

    /**
     * Cross-tenant rows are invisible to RLS and out-of-scope rows are invisible to the
     * visibility policy. "Not there", "not this tenant's" and "not yours" all 404 — the
     * caller must not be able to tell them apart.
     */
    private Customer find(UUID id) {
        return finder.findCustomer(id)
            .orElseThrow(() -> new NotFoundException("customer not found"));
    }
```

Add `VisibleFinder finder` to the constructor alongside the existing `CustomerRepository customers`
— the repository is still needed for `save` and `findByGstin`.

- [ ] **Step 5: Delete `findByActive`**

Remove the method from `CustomerRepository`. It has exactly one caller, which Step 4 just replaced.
Leaving it would create an unguarded read path that Task 8's allowlist would then have to exempt for
no reason.

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :test --tests 'com.easycrm.crm.CustomerVisibilityTest'`
Expected: PASS.

- [ ] **Step 7: Run the full suite**

Run: `./gradlew :test`
Expected: 0 failures. Existing customer tests mint `OWNER` and are unaffected; if any fails,
the unrestricted path is wrong — fix the code, not the test.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/easycrm/crm/ backend/src/test/java/com/easycrm/crm/CustomerVisibilityTest.java
git commit -m "feat: filter customer reads and writes by visibility

The private find(id) was already the choke point for get, update,
deactivate and activate, so re-pointing it covers the mutation paths too
-- a read-only filter would have looked closed while leaving them open.

findByActive is deleted rather than exempted: CustomerSpecifications.filter
replaces it, so the list path goes through the finder like every other read."
```

---

### Task 4: Enquiry paths, and the deliberately unfiltered dedupe lane

**Files:**
- Modify: `backend/src/main/java/com/easycrm/sales/EnquiryService.java`
- Test: `backend/src/test/java/com/easycrm/sales/EnquiryVisibilityTest.java`

**Interfaces:**
- Consumes: `VisibleFinder.findEnquiry`, `VisibleFinder.pageEnquiries` (Task 2).
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/sales/EnquiryVisibilityTest.java`. Seed one tenant with an
enquiry assigned to exec A, one assigned to exec B, and one unassigned.

```java
    @Test
    void execCanGetTheirOwnEnquiry() throws Exception {
        mvc.perform(get("/api/v1/enquiries/" + mine).header(AUTH, bearer(execAToken)))
            .andExpect(status().isOk());
    }

    @Test
    void execCannotGetAnotherExecsEnquiry() throws Exception {
        mvc.perform(get("/api/v1/enquiries/" + execBEnquiry).header(AUTH, bearer(execAToken)))
            .andExpect(status().isNotFound());
    }

    @Test
    void execCanGetAnUnassignedEnquiry() throws Exception {
        mvc.perform(get("/api/v1/enquiries/" + pool).header(AUTH, bearer(execAToken)))
            .andExpect(status().isOk());
    }

    @Test
    void ownerCanGetAnyEnquiry() throws Exception {
        mvc.perform(get("/api/v1/enquiries/" + execBEnquiry).header(AUTH, bearer(ownerToken)))
            .andExpect(status().isOk());
    }

    @Test
    void execListOmitsAnotherExecsEnquiry() throws Exception {
        mvc.perform(get("/api/v1/enquiries").header(AUTH, bearer(execAToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[*].id")
                .value(not(hasItem(execBEnquiry.toString()))));
    }

    /** WRITE coverage — a read-only filter would leave this path open. */
    @Test
    void execCannotPatchAnotherExecsEnquiry() throws Exception {
        mvc.perform(patch("/api/v1/enquiries/" + execBEnquiry)
                .header(AUTH, bearer(execAToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validEnquiryJson()))
            .andExpect(status().isNotFound());
    }

    /** WRITE coverage on the lifecycle path, which does not go through PATCH. */
    @Test
    void execCannotAdvanceAnotherExecsEnquiry() throws Exception {
        mvc.perform(post("/api/v1/enquiries/" + execBEnquiry + "/advance")
                .header(AUTH, bearer(execAToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"stage\":\"QUALIFIED\"}"))
            .andExpect(status().isNotFound());
    }
```

Read `EnquiryController` for the real advance verb, path and body shape, and `EnquiryStage` for a
valid target constant — both are inferred above.

The eighth case is the one this task exists for:

```java
    /**
     * The dedupe pre-check MUST stay unfiltered (spec §6). If it only saw exec A's own
     * enquiries, exec A would successfully create a second active enquiry for a phone
     * exec B already holds -- breaking one-active-per-phone, the invariant the check
     * exists to protect. The 409 does disclose that SOMEONE holds the number. That
     * disclosure is the accepted trade; a broken invariant is not.
     *
     * <p>The discriminator is the error MESSAGE, not the row count. The partial unique
     * index forbids a second active row unconditionally, so if the pre-check were
     * filtered and let the insert through, the index would reject it at commit and
     * Postgres would roll back the whole transaction -- the row count would still land
     * on 1, identical to the correct behaviour. Only the message distinguishes
     * "the pre-check caught it" from "the backstop caught it."
     */
    @Test
    void dedupeStillTripsAgainstAnInvisibleEnquiry() throws Exception {
        // execB owns an active enquiry on this phone; execA cannot see it.
        mvc.perform(get("/api/v1/enquiries/" + execBEnquiry).header(AUTH, bearer(execAToken)))
            .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/enquiries")
                .header(AUTH, bearer(execAToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(enquiryJsonFor(EXEC_B_PHONE)))
            .andExpect(status().isConflict())
            // The discriminator: only the app-level pre-check's ConflictException carries
            // this message. The unique-index backstop's handler returns the generic
            // "the request conflicts with existing data" and would NOT match.
            .andExpect(jsonPath("$.error.message")
                .value(containsString("active enquiry already exists for this phone")));

        // Secondary sanity check only -- NOT a discriminator. See doc-comment above.
        assertThat(countActiveEnquiriesFor(EXEC_B_PHONE)).isEqualTo(1);
    }
```

Both halves matter, but not for the reason it might first appear. The 409 alone would also be
produced by a *broken* implementation that filtered the check and then hit the database unique
index — but so would the row count staying at 1: a unique-constraint violation rolls back the
*entire* enclosing transaction, not just the failed insert, so a broken pre-check that let the
duplicate through still ends with the count back at 1, indistinguishable from the correct case.
The row count is a sanity check on the schema, not a discriminator between the two code paths.
What actually distinguishes "the pre-check saw it" from "the backstop caught it" is the error
**message**: the pre-check's `ConflictException` names the conflict specifically; the backstop's
`DataIntegrityViolationException` handler returns a generic string. Assert on the message.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :test --tests 'com.easycrm.sales.EnquiryVisibilityTest'`
Expected: FAIL on the visibility cases (200 instead of 404). `dedupeStillTripsAgainstAnInvisibleEnquiry`
should **already pass** at this point except for its first assertion — nothing filters yet, so the
dedupe already sees everything. That is expected: this case is a regression guard for the change
you are about to make, not a driver of it.

- [ ] **Step 3: Re-point `EnquiryService`**

```java
    @Transactional(readOnly = true)
    public PageResponse<EnquiryResponse> list(
            EnquiryStage stage, UUID assignedTo, EnquirySource source, Pageable pageable) {
        return PageResponse.of(
            finder.pageEnquiries(EnquirySpecifications.filter(stage, assignedTo, source), pageable)
                .map(EnquiryResponse::of));
    }

    private Enquiry find(UUID id) {
        return finder.findEnquiry(id)
            .orElseThrow(() -> new NotFoundException("enquiry not found"));
    }
```

Add `VisibleFinder finder` to the constructor. **Leave `requireNoActiveDuplicateExcept` exactly as
it is** — it calls `enquiries.findByNormalizedPhone` directly and must keep doing so. Add a comment
above that call so the next reader does not "fix" it:

```java
        // Deliberately UNFILTERED: this pre-check must see every active enquiry in the
        // tenant, not just the caller's. Filtering it would let two reps each create an
        // active enquiry for the same phone. Spec 2026-08-29-record-visibility-design.md §6.
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :test --tests 'com.easycrm.sales.EnquiryVisibilityTest'`
Expected: PASS, including both halves of the dedupe case.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew :test`
Expected: 0 failures.

- [ ] **Step 6: Log challenge 43**

Append to `docs/superpowers/engineering-challenges.md`, using the template at the bottom of that file
and the next free number (**43** — 42 is the last one written). Cover: a visibility filter that must
be deliberately *not* applied at exactly the two points a naive implementation would apply it
everywhere; why filtering a uniqueness pre-check converts a clean 409 into a confusing database-level
conflict at a random later moment; and the disclosure the unfiltered lane accepts in exchange.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/EnquiryService.java \
        backend/src/test/java/com/easycrm/sales/EnquiryVisibilityTest.java \
        docs/superpowers/engineering-challenges.md
git commit -m "feat: filter enquiry reads and writes, keep the dedupe lane unfiltered

The one-active-enquiry-per-phone pre-check deliberately still sees the
whole tenant. Filtering it would let two reps each create an active
enquiry for the same phone -- the pre-check would pass for both and the
unique index would fire later as a confusing conflict.

The test asserts the row count as well as the 409, because a broken
implementation that filtered the check and fell through to the index
would also return 409.

Challenge 43 writes up the tension."
```

---

### Task 5: Quotation and order paths

**Files:**
- Modify: `backend/src/main/java/com/easycrm/sales/QuotationService.java`, `backend/src/main/java/com/easycrm/sales/OrderService.java`
- Test: `backend/src/test/java/com/easycrm/sales/QuotationOrderVisibilityTest.java`

**Interfaces:**
- Consumes: `VisibleFinder.findQuotation`, `findOrder`, `pageQuotations`, `pageOrders` (Task 2).
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/sales/QuotationOrderVisibilityTest.java`. Seed one tenant
with customer-A (assigned to exec A), customer-B (assigned to exec B) and an unassigned customer,
then a quotation and an accepted order under each.

Cases, for **both** aggregates:

- exec A gets 200 on the quotation/order under customer-A;
- exec A gets 404 on the one under customer-B;
- exec A gets 200 on the one under the unassigned customer;
- owner gets 200 on all three;
- exec A's list omits customer-B's;
- **write coverage:** exec A gets 404 attempting a mutation on customer-B's — use `POST
  /api/v1/orders/{id}/cancel` for the order and a quotation mutation (`accept`, or a version edit)
  for the quotation. Read the controllers for the real paths.

Plus the case that proves the derivation is live rather than incidental:

```java
    /**
     * Visibility derives from the customer, so reassigning the customer moves its whole
     * quotation and order history. Spec §4 accepts this consequence explicitly -- this
     * test is what makes it a decision rather than a surprise.
     */
    @Test
    void reassigningTheCustomerMovesItsQuotations() throws Exception {
        mvc.perform(get("/api/v1/quotations/" + quoteUnderB).header(AUTH, bearer(execAToken)))
            .andExpect(status().isNotFound());

        reassignCustomer(customerB, execA);   // owner-authenticated PATCH

        mvc.perform(get("/api/v1/quotations/" + quoteUnderB).header(AUTH, bearer(execAToken)))
            .andExpect(status().isOk());
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :test --tests 'com.easycrm.sales.QuotationOrderVisibilityTest'`
Expected: FAIL — 200 where 404 is expected.

- [ ] **Step 3: Re-point `QuotationService`**

`findQuotation` (line ~283) is the choke point; `list` (line ~93) is the other read.

```java
    Quotation findQuotation(UUID id) {
        return finder.findQuotation(id)
            .orElseThrow(() -> new NotFoundException("quotation not found"));
    }
```

and in `list`, replace `quotations.findAll(...)` with
`finder.pageQuotations(QuotationSpecifications.filter(status, customerId), pageable)`.

Add `VisibleFinder finder` to the constructor. Two other call sites need attention:

- `customers.findById(req.customerId())` in `create` (line ~67) → `finder.findCustomer(...)`. An
  exec must not be able to raise a quotation against a customer they cannot see.
- `enquiries.findById(req.enquiryId())` (line ~72) → `finder.findEnquiry(...)`, same reasoning.
- `customers.findById(q.getCustomerId())` (line ~127) → `finder.findCustomer(...)`. This one is
  reached only from an already-visible quotation, so it cannot change an outcome — route it anyway,
  because Task 8's guard forbids the direct call and an exemption here would be noise.

`versions.findById(...)` and `items.findByVersionId(...)` stay as they are — `QuotationVersionRepository`
and `QuotationItemRepository` are not guarded repositories, and their rows are only ever reached
through a quotation that has already been checked.

- [ ] **Step 4: Re-point `OrderService`**

```java
    /**
     * Cross-tenant rows are invisible to RLS and out-of-scope rows are invisible to the
     * visibility policy. "Not there", "not this tenant's" and "not yours" all 404.
     */
    private Order find(UUID id) {
        return finder.findOrder(id)
            .orElseThrow(() -> new NotFoundException("order not found"));
    }
```

The existing comment on this method says only *"Cross-tenant rows are invisible to RLS, so 'not mine'
and 'not there' both 404."* Replace it — "not mine" now means two different things, one a tenancy
boundary and one a product rule, and a future reader must not collapse them.

In `list` (line ~33), replace `orders.findAll(...)` with
`finder.pageOrders(OrderSpecifications.filter(status, customerId), pageable)`.

`orders.findByQuotationId` stays on the raw repository — it is reached only from an already-checked
quotation, and because a quotation and its order derive visibility from the *same* customer,
filtering it could never change an outcome (spec §6.1).

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :test --tests 'com.easycrm.sales.QuotationOrderVisibilityTest'`
Expected: PASS.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew :test`
Expected: 0 failures.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/QuotationService.java \
        backend/src/main/java/com/easycrm/sales/OrderService.java \
        backend/src/test/java/com/easycrm/sales/QuotationOrderVisibilityTest.java
git commit -m "feat: filter quotation and order reads and writes through their customer

Neither table carries an owner. Both derive from customer.assigned_to via
an EXISTS subquery, which is why this slice needs no migration.

Quotation create now resolves its customer and enquiry through the finder
too: an exec must not be able to raise a quote against a customer they
cannot see.

OrderService.find's comment claimed 'not mine' had one meaning. It now has
two -- a tenancy boundary and a product rule -- and they must not be
collapsed by a future reader."
```

---

### Task 6: Nested and derived paths

**Files:**
- Modify: `backend/src/main/java/com/easycrm/crm/ContactService.java`, `backend/src/main/java/com/easycrm/sales/ShareLinkService.java`, `backend/src/main/java/com/easycrm/sales/pdf/QuotationPdfService.java`
- Test: `backend/src/test/java/com/easycrm/sales/NestedVisibilityTest.java`

**Interfaces:**
- Consumes: `VisibleFinder.findCustomer`, `findQuotation` (Task 2).
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/sales/NestedVisibilityTest.java`:

```java
    @Test
    void execCannotListContactsUnderAnInvisibleCustomer() throws Exception {
        mvc.perform(get("/api/v1/customers/" + customerB + "/contacts")
                .header(AUTH, bearer(execAToken)))
            .andExpect(status().isNotFound());
    }

    @Test
    void execCannotGetAContactUnderAnInvisibleCustomer() throws Exception {
        mvc.perform(get("/api/v1/customers/" + customerB + "/contacts/" + contactUnderB)
                .header(AUTH, bearer(execAToken)))
            .andExpect(status().isNotFound());
    }

    @Test
    void execCannotRenderThePdfOfAnInvisibleQuotation() throws Exception {
        mvc.perform(get("/api/v1/quotations/" + quoteUnderB + "/pdf")
                .header(AUTH, bearer(execAToken)))
            .andExpect(status().isNotFound());
    }

    @Test
    void execCannotMintAShareLinkForAnInvisibleQuotation() throws Exception {
        mvc.perform(post("/api/v1/quotations/" + quoteUnderB + "/share")
                .header(AUTH, bearer(execAToken)))
            .andExpect(status().isNotFound());
    }

    /**
     * The public route is deliberately OUTSIDE this layer: it has no JWT, so there is no
     * principal to filter against. A share link minted by a manager must keep working for
     * the customer who received it. Spec §5.3.
     */
    @Test
    void thePublicShareRouteStaysUnfiltered() throws Exception {
        mvc.perform(get("/public/q/" + tokenMintedByOwnerForQuoteUnderB))
            .andExpect(status().isOk());
    }
```

Read the real controller paths for contacts, `/pdf` and `/share` before writing these — the paths
above are inferred from the service names.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :test --tests 'com.easycrm.sales.NestedVisibilityTest'`
Expected: FAIL on the first four (200 instead of 404). `thePublicShareRouteStaysUnfiltered` should
pass from the start and must **still** pass at the end — it is the guard against over-applying the
filter.

- [ ] **Step 3: Gate contacts on the parent customer**

In `ContactService`, both methods currently read `CustomerRepository`/`ContactRepository` directly:

```java
    private void requireCustomer(UUID customerId) {
        finder.findCustomer(customerId)
            .orElseThrow(() -> new NotFoundException("customer not found"));
    }

    private Contact find(UUID customerId, UUID contactId) {
        requireCustomer(customerId);          // gate on the parent FIRST
        Contact c = contacts.findById(contactId)
            .orElseThrow(() -> new NotFoundException("contact not found"));
        if (!c.getCustomerId().equals(customerId)) {
            throw new NotFoundException("contact not found");
        }
        return c;
    }
```

The added `requireCustomer` call in `find` is the fix: without it, a contact id plus its real
customer id would resolve even when the customer is invisible. `ContactRepository` is not a guarded
repository (spec §6.1), so `contacts.findById` stays as it is.

Add `VisibleFinder finder` to the constructor.

- [ ] **Step 4: Gate the share-link mint**

In `ShareLinkService.share` (line ~56), replace `quotations.findById(quotationId)` with
`finder.findQuotation(quotationId)`. Keep the existing `NotFoundException` message.

- [ ] **Step 5: Gate both PDF entry points**

`QuotationPdfService` has two:

- line ~50, `quotations.findById(quotationId)` → `finder.findQuotation(quotationId)`.
- line ~65-67, `renderVersion(versionId)` loads the **version first** and then its quotation. Route
  that second load through `finder.findQuotation(v.getQuotationId())` so the check happens even
  though the version was reached directly.

`customers.findById(q.getCustomerId())` at line ~85 → `finder.findCustomer(...)`, for the same
guard-noise reason as Task 5. `tenants.findById(...)` stays — `Tenant` is a global table, not a
visibility-scoped one.

**Do not touch the public render path's tenant resolution.** If `QuotationPdfService` is also what
`/public/q/{token}` calls, the visibility policy already returns unrestricted for an absent principal
(Task 1), so the public route keeps working — that is exactly what
`thePublicShareRouteStaysUnfiltered` proves. If that test goes red, the fix is in the policy's
absent-principal branch, not in this service.

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :test --tests 'com.easycrm.sales.NestedVisibilityTest'`
Expected: PASS, all five including the public-route case.

- [ ] **Step 7: Run the full suite**

Run: `./gradlew :test`
Expected: 0 failures.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/easycrm/crm/ContactService.java \
        backend/src/main/java/com/easycrm/sales/ShareLinkService.java \
        backend/src/main/java/com/easycrm/sales/pdf/QuotationPdfService.java \
        backend/src/test/java/com/easycrm/sales/NestedVisibilityTest.java
git commit -m "feat: close the nested read paths to invisible records

Contacts, the PDF render and the share-link mint were all reachable side
doors: each loaded a child or re-entered through a version id without ever
re-checking the parent.

ContactService.find gains a parent check it never had -- a contact id plus
its real customer id used to resolve regardless of the customer.

The public share route stays unfiltered and has a test saying so, because
over-applying this filter would break links already in customers' hands."
```

---

### Task 7: `assigned_to` validation

**Files:**
- Modify: `backend/src/main/java/com/easycrm/crm/CustomerService.java`, `backend/src/main/java/com/easycrm/sales/EnquiryService.java`
- Test: extend `backend/src/test/java/com/easycrm/crm/CustomerVisibilityTest.java` and `backend/src/test/java/com/easycrm/sales/EnquiryVisibilityTest.java`

**Interfaces:**
- Consumes: `UserRepository` (existing), `UserStatus.ACTIVE` (existing).
- Produces: nothing new.

**Why:** once `NULL` means "everyone sees it", a typo'd or stale `assignedTo` means "nobody below
manager sees it" — silently, permanently, with no error at write time. Today the column is inert so
the same typo is harmless; this slice is what makes it load-bearing.

- [ ] **Step 1: Write the failing tests**

Add to both visibility test classes:

```java
    @Test
    void rejectsAnAssignedToThatIsNotAUserInThisTenant() throws Exception {
        mvc.perform(post("/api/v1/customers")
                .header(AUTH, bearer(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(customerJsonAssignedTo(UUID.randomUUID())))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rejectsAnAssignedToThatNamesAnInactiveUser() throws Exception {
        UUID inactive = seedUser(UserStatus.INACTIVE);
        mvc.perform(post("/api/v1/customers")
                .header(AUTH, bearer(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(customerJsonAssignedTo(inactive)))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void acceptsAnAssignedToThatNamesAnActiveUser() throws Exception {
        UUID active = seedUser(UserStatus.ACTIVE);
        mvc.perform(post("/api/v1/customers")
                .header(AUTH, bearer(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(customerJsonAssignedTo(active)))
            .andExpect(status().isCreated());
    }

    @Test
    void acceptsANullAssignedTo() throws Exception {
        mvc.perform(post("/api/v1/customers")
                .header(AUTH, bearer(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(customerJsonAssignedTo(null)))
            .andExpect(status().isCreated());
    }
```

`acceptsANullAssignedTo` is not filler — null is the overwhelmingly common case and the one an
over-eager `@NotNull` would break.

Check `UserStatus`'s real constants before writing `INACTIVE`; the enum exists at
`iam/UserStatus.java` and this plan has not read its values.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :test --tests 'com.easycrm.crm.CustomerVisibilityTest' --tests 'com.easycrm.sales.EnquiryVisibilityTest'`
Expected: the two rejection cases FAIL with 201 instead of 422.

- [ ] **Step 3: Implement the check**

Add to `CustomerService` and `EnquiryService`, called from both `create` and `update` before the
entity is constructed or mutated:

```java
    /**
     * A non-null assignedTo must name an ACTIVE user in this tenant. User is tenant-scoped,
     * so RLS already makes a cross-tenant id come back empty -- no tenant check is needed
     * here and adding one would be hand-written tenant filtering.
     *
     * <p>Without this, a typo'd UUID makes a record visible to nobody below manager,
     * silently and permanently, because unassigned-means-visible only applies to NULL.
     */
    private void requireAssignableUser(UUID assignedTo) {
        if (assignedTo == null) return;
        users.findById(assignedTo)
            .filter(u -> u.getStatus() == UserStatus.ACTIVE)
            .orElseThrow(() -> new ValidationException("assignedTo", "must be an active user"));
    }
```

Add `UserRepository users` to both constructors. Check `ValidationException`'s real constructor
signature in `platform/error/` — the two-argument field/message form above is inferred from the
error contract, not read.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :test --tests 'com.easycrm.crm.CustomerVisibilityTest' --tests 'com.easycrm.sales.EnquiryVisibilityTest'`
Expected: PASS.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew :test`
Expected: 0 failures. **This is the task most likely to break existing tests** — any fixture that
sets a random `assignedTo` UUID now gets a 422. If one fails, the fixture is wrong (it was asserting
against a user that never existed), so fix the fixture. Do not weaken the validation to accommodate
it.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/easycrm/crm/CustomerService.java \
        backend/src/main/java/com/easycrm/sales/EnquiryService.java \
        backend/src/test/java/com/easycrm/crm/CustomerVisibilityTest.java \
        backend/src/test/java/com/easycrm/sales/EnquiryVisibilityTest.java
git commit -m "feat: assignedTo must name an active user in the tenant

This closes a footgun the visibility slice creates rather than one it
found. Unassigned means visible to everyone, so a typo'd UUID does not
fall back to the pool -- it makes the record visible to nobody below
manager, with no error at write time and no way to notice.

User is tenant-scoped, so RLS makes a cross-tenant id come back empty and
no hand-written tenant check is needed."
```

---

### Task 8: The ArchUnit guard, proven to fail

**Files:**
- Create: `backend/src/test/java/com/easycrm/arch/VisibilityScopingArchTest.java`

**Interfaces:**
- Consumes: every service change from Tasks 3–7.
- Produces: nothing new.

**This task must run last of the code tasks.** It fails until every service is re-pointed.

- [ ] **Step 1: Write the guard**

Create `backend/src/test/java/com/easycrm/arch/VisibilityScopingArchTest.java`, modelled on
`TenantScopingArchTest` in the same package:

```java
package com.easycrm.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class VisibilityScopingArchTest {

    /** Repositories whose rows are subject to intra-tenant visibility filtering. */
    private static final Set<String> GUARDED_REPOSITORIES = Set.of(
        "com.easycrm.crm.CustomerRepository",
        "com.easycrm.sales.EnquiryRepository",
        "com.easycrm.sales.QuotationRepository",
        "com.easycrm.sales.OrderRepository");

    /**
     * Methods any class may still call on a guarded repository. Everything else must go
     * through VisibleFinder.
     *
     * <p>This is an ALLOWLIST on purpose. A blocklist of known read methods (findById,
     * findAll, ...) would silently pass a derived query added later -- the exact failure
     * this guard exists to prevent. Adding a name here is a visibility decision and needs
     * the same review as adding a table to TenantScopingArchTest.GLOBAL_TABLES.
     * See spec 2026-08-29-record-visibility-design.md §6.1.
     */
    private static final Set<String> ALLOWED_METHODS = Set.of(
        "save", "saveAndFlush", "delete", "deleteAll",
        // Uniqueness pre-check: must see the whole tenant or the invariant breaks (§6).
        "findByGstin",
        // Dedupe pre-check: same reasoning (§6).
        "findByNormalizedPhone",
        // Reached only from an already-checked quotation; a quotation and its order derive
        // visibility from the SAME customer, so filtering it is a provable no-op (§6.1).
        "findByQuotationId");

    @Test
    void onlyTheVisibilityPackageMayReadAGuardedRepository() {
        JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.easycrm");

        ArchRule rule = noClasses()
            .that().resideOutsideOfPackage("com.easycrm.platform.visibility..")
            .should(callAGuardedRepositoryOutsideTheAllowlist())
            .because("intra-tenant visibility is applied in VisibleFinder; a read that "
                   + "bypasses it silently returns another user's records");

        rule.check(classes);
    }

    private static ArchCondition<JavaClass> callAGuardedRepositoryOutsideTheAllowlist() {
        return new ArchCondition<>("call a guarded repository outside the allowlist") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
                    String owner = call.getTargetOwner().getFullName();
                    if (!GUARDED_REPOSITORIES.contains(owner)) continue;
                    if (ALLOWED_METHODS.contains(call.getName())) continue;
                    events.add(SimpleConditionEvent.satisfied(item, call.getDescription()));
                }
            }
        };
    }
}
```

Note the inversion: with `noClasses().should(condition)`, a **satisfied** event is a violation. That
reads backwards and is easy to get wrong — if the test passes on your first run, do not trust it
until Step 3.

- [ ] **Step 2: Run it**

Run: `./gradlew :test --tests 'com.easycrm.arch.VisibilityScopingArchTest'`
Expected: PASS — Tasks 3–7 have already re-pointed every caller.

If it fails, read the violation list: it names the exact class and call site still bypassing the
finder. That is a real finding, not a test problem.

- [ ] **Step 3: Prove it can fail — mandatory, not optional**

A guard never observed failing is not known to work. Challenge 33 was caught only because the
`platform-primitives` plan forced this step.

1. In `CustomerService.find`, temporarily replace `finder.findCustomer(id)` with
   `customers.findById(id)`.
2. Run: `./gradlew :test --tests 'com.easycrm.arch.VisibilityScopingArchTest'`
3. **Confirm it FAILS**, and that the message names `CustomerService` and `findById`.
4. Revert the change.
5. Run it again and confirm it passes.

Record the observed failure message in the commit body. If the test passed in step 2 above, the
condition's satisfied/violated polarity is inverted — fix it before continuing.

- [ ] **Step 4: Run the full suite**

Run: `./gradlew clean test` (both projects) and count:

```bash
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
```

Expected: 0 failures, and a total meaningfully above 296.

- [ ] **Step 5: Log challenge 44**

Append to `docs/superpowers/engineering-challenges.md` using the template and the next free number
(**44**). Cover: allowlist versus blocklist as the difference between a guard that decays silently
and one that cannot; why the forcing function matters more than the rule's current contents; and the
`noClasses().should(satisfied)` polarity trap, which is why the prove-it-can-fail step is mandatory
rather than advisory.

- [ ] **Step 6: Commit**

```bash
git add backend/src/test/java/com/easycrm/arch/VisibilityScopingArchTest.java \
        docs/superpowers/engineering-challenges.md
git commit -m "test: guard the visibility layer with an allowlist, and prove it fails

An allowlist, not a blocklist. A blocklist of known read methods passes
any derived query added later -- exactly the side door this guard exists
to close. Adding a name to the allowlist is now a visibility decision that
has to be argued for, the same forcing function GLOBAL_TABLES provides for
tenant scoping.

Proven falsifiable: pointing CustomerService.find back at findById turns
the test red naming that class and call.

Challenge 44 writes up the allowlist reasoning and the
noClasses().should(satisfied) polarity trap."
```

---

### Task 9: Documentation wrap-up

**Files:**
- Modify: `docs/superpowers/HANDOFF.md`, `docs/superpowers/annotations-reference.md`, `docs/superpowers/engineering-challenges.md`

- [ ] **Step 1: Check the annotations reference**

Read `docs/superpowers/annotations-reference.md` and add a row for any annotation this slice
introduced that is not already listed. Expect **few or none** — this slice uses specifications, not
method security, so `@PreAuthorize` should *not* appear. If you find yourself adding it, something
was built that the spec did not ask for.

- [ ] **Step 2: Confirm both challenges landed**

Challenges 43 (Task 4) and 44 (Task 8) should both be in
`docs/superpowers/engineering-challenges.md`. Per `CLAUDE.md`, do the end-of-session pass now: did
anything else non-obvious get solved that is not yet written up? Two candidates worth considering if
they actually bit during execution — the criteria subquery surviving the paging COUNT query, and the
`noClasses().should(satisfied)` polarity. Fold the second into 44 rather than logging it separately.

- [ ] **Step 3: Update `HANDOFF.md`**

- **§3** — add this slice as "Latest code work" and demote rate limiting to "Previous code work",
  matching the existing entries' shape. Record the final test count.
- **§8 item 3** — backlog item #3 is now **fully closed**: rate limiting landed in the previous
  slice, record-level visibility in this one. **User invitations become the sole remaining P0-auth
  follow-up.** Rewrite the item rather than appending to it.
- **§8 suggested default** — the "last wrong-in-code-that-runs-today item" is now closed. Re-rank
  honestly: `activity`/`follow_up` (#1) becomes the strongest claim, scheduled auto-expiry (#2)
  stays the cheapest, PF19's entitlement-metering half stays blocked on billing design, and
  `platform-web` stays weakest.
- **§2** — add the spec and this plan to the numbered reading list.
- **Deferred-minor list** — add any `minor (deferred)` findings this slice's reviews produced, and
  note against item #9 that a **fourth** `*Specifications` class now exists, so fixing the
  string-keyed `root.get(...)` means fixing four, not three.

- [ ] **Step 4: Record what this slice deliberately did not do**

In §8, state plainly that `SALES_MANAGER` is collapsed into the unrestricted tier because no team
model exists, and that narrowing it later is a schema-plus-admin-surface slice of its own. A future
reader must not see "record-level visibility: DONE" and assume the three-tier rule in the parent
spec §6 shipped.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/
git commit -m "docs: record the record-visibility slice and re-rank what comes next

Backlog item 3 is now fully closed; user invitations are the only P0-auth
follow-up left. With the last wrong-in-code-that-runs-today item gone,
activity/follow-up becomes the strongest claim on the board.

States plainly that SALES_MANAGER is collapsed into the unrestricted tier
for want of a team model, so nobody reads 'visibility: DONE' as meaning
the parent spec's three-tier rule shipped."
```

---

## Execution notes

- **Task order matters.** Task 8's guard fails until Tasks 3–7 are done; Task 2 needs Task 1's
  policy; Task 3's `findByActive` deletion needs its replacement in the same task.
- **Fixture signatures throughout this plan are inferred from a read of the entities, not compiled.**
  Constructor arities, enum constants (`CustomerSource.DIRECT`, `UserStatus.INACTIVE`), controller
  paths and `ValidationException`'s signature must each be checked against the source before use.
  Correcting a fixture so it compiles is expected. Correcting an **assertion** so it passes is a
  finding — stop and say so.
- **If an existing test breaks, suspect the code first.** Every one of the 296 mints an `OWNER`
  token and should land on the unrestricted path. A break there means the unrestricted path is
  wrong, with Task 7 the most likely culprit.
- **The endpoint tests' shared helpers.** `execAToken`, `execBToken` and `ownerToken` all come from
  `TestTokens.as(tenantId, userId, role)` added in Task 1 — `owner()` and `provisionOwner()` mint a
  *random* user id, so they cannot be used to test a rule that keys on user id. `AUTH`, `bearer(...)`
  and the JSON-body helpers are local conveniences: copy their shape from an existing endpoint test
  in the same package rather than inventing new ones.
- **`jsonPath(...).value(not(hasItem(...)))`** needs the Hamcrest static imports
  (`org.hamcrest.Matchers.not`, `hasItem`). Check how the existing list tests assert absence and
  match them; if they use a different idiom, use theirs.
