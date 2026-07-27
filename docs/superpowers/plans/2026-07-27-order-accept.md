# Order + Accept Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Accepting a `SENT` quotation creates exactly one `CONFIRMED` order, moves the quotation to `ACCEPTED`, and publishes an event that an audit-log subscriber handles — all in one transaction.

**Architecture:** A new `Order` aggregate under `com.easycrm.sales` (physical table `sales_order`, tenant-scoped + RLS). The `accept` transition lives on `QuotationService` beside its `send`/`revise`/`reject`/`expire` siblings; it creates the order inline, transitions the quotation, and publishes `QuotationAcceptedEvent` for decoupled side-effects. Idempotency is *natural* — a `UNIQUE(tenant_id, quotation_id)` constraint plus the quotation's `@Version` guarantees one order per quotation, so no client key is needed. Order reads live in a small `OrderService`/`OrderController`.

**Tech Stack:** Spring Boot 4.1, Java 25, Hibernate 7, PostgreSQL (RLS), Flyway, JUnit 5 + Testcontainers + MockMvc + jayway JsonPath.

## Global Constraints

- **Money is never a `double`** — `NUMERIC(18,2)` in Postgres, `BigDecimal` in Java, JSON **string** on the wire (global `BigDecimalStringModule`, already installed). Order totals are **snapshotted** from the accepted version, never recomputed.
- **Tenant isolation is structural** — `Order` extends `TenantScopedEntity` (`@TenantId` + RLS); ArchUnit fails the build otherwise. Never hand-write `WHERE tenant_id`.
- **Cross-tenant reads return 404**, not 403/200.
- **`ddl-auto: validate`** — migration column types must match entity mappings exactly (`VARCHAR` for `String`, `NUMERIC(18,2)` for money `BigDecimal`, `BIGINT` for `@Version long`).
- **RLS grants are automatic** — `V1__roles_and_extensions.sql` sets `ALTER DEFAULT PRIVILEGES … GRANT … TO easycrm_app`, so new tables need **no explicit GRANT**; the RLS migration only does `ENABLE ROW LEVEL SECURITY` + `CREATE POLICY`.
- **Tests connect as `easycrm_app`** (non-owner, RLS enforced) via `IntegrationTest`; provision tenants with `TestTokens.provisionOwner(stateCode)` (the quotation build path reads `Tenant.state_code` — a phantom tenant is not enough).
- **Commits:** author as `divyam`, plain `git commit`, **never** mention Claude/AI or add a `Co-Authored-By` trailer. One task per commit.
- **TDD:** failing test → run-to-confirm-fail → minimal code → run-to-pass → commit.

**Run tests:** `cd backend && ./gradlew test` (Docker must be running). A single test class: `./gradlew test --tests 'com.easycrm.sales.web.QuotationAcceptTest'`.

---

## File Structure

**Create:**
- `backend/src/main/java/com/easycrm/sales/Order.java` — the order aggregate root.
- `backend/src/main/java/com/easycrm/sales/OrderStatus.java` — `{ CONFIRMED }`.
- `backend/src/main/java/com/easycrm/sales/OrderRepository.java` — persistence + idempotent lookup + list finders.
- `backend/src/main/java/com/easycrm/sales/QuotationAcceptedEvent.java` — the domain event (record).
- `backend/src/main/java/com/easycrm/sales/OrderAcceptedAuditListener.java` — writes the audit row.
- `backend/src/main/java/com/easycrm/sales/OrderService.java` — order reads.
- `backend/src/main/java/com/easycrm/sales/web/OrderController.java` — `GET /api/v1/orders` + `/{id}`.
- `backend/src/main/java/com/easycrm/sales/web/dto/OrderResponse.java`
- `backend/src/main/java/com/easycrm/sales/web/dto/AcceptRequest.java`
- `backend/src/main/resources/db/migration/V18__sales_order.sql`
- `backend/src/main/resources/db/migration/V19__rls_sales_order.sql`
- Tests: `OrderRepositoryTest`, `QuotationAcceptTest`, `OrderReadTest` under `backend/src/test/java/com/easycrm/sales/…`, plus new cases in `DocumentNumberServiceTest`.

**Modify:**
- `backend/src/main/java/com/easycrm/sales/QuotationStatus.java` — add `ACCEPTED`.
- `backend/src/main/java/com/easycrm/sales/Quotation.java` — add `markAccepted()`.
- `backend/src/main/java/com/easycrm/sales/DocumentNumberService.java` — add `nextOrderNo(LocalDate)`.
- `backend/src/main/java/com/easycrm/sales/QuotationService.java` — add `accept(...)`; inject `OrderRepository` + `ApplicationEventPublisher`.
- `backend/src/main/java/com/easycrm/sales/web/QuotationController.java` — add `POST /{id}/accept`.
- Docs: `engineering-challenges.md`, `annotations-reference.md`, `HANDOFF.md`.

---

## Task 1: `Order` aggregate + migrations + repository (persistence & RLS)

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/OrderStatus.java`
- Create: `backend/src/main/java/com/easycrm/sales/Order.java`
- Create: `backend/src/main/java/com/easycrm/sales/OrderRepository.java`
- Create: `backend/src/main/resources/db/migration/V18__sales_order.sql`
- Create: `backend/src/main/resources/db/migration/V19__rls_sales_order.sql`
- Test: `backend/src/test/java/com/easycrm/sales/OrderRepositoryTest.java`

**Interfaces:**
- Produces: `Order` (constructor `Order(UUID quotationId, UUID quotationVersionId, UUID customerId, String orderNo, BigDecimal subTotal, BigDecimal totalTax, BigDecimal grandTotal, String poReference, LocalDate poDate)`, status defaults to `CONFIRMED`); getters `getOrderNo/getQuotationId/getQuotationVersionId/getCustomerId/getStatus/getSubTotal/getTotalTax/getGrandTotal/getPoReference/getPoDate` + `BaseEntity` getters. `OrderStatus { CONFIRMED }`. `OrderRepository extends JpaRepository<Order, UUID>` with `Optional<Order> findByQuotationId(UUID quotationId)`, `Page<Order> findByCustomerId(UUID customerId, Pageable pageable)`, `Page<Order> findByStatus(OrderStatus status, Pageable pageable)`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/easycrm/sales/OrderRepositoryTest.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.tenancy.TenantContext.TenantPrincipal;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OrderRepositoryTest extends IntegrationTest {

    @Autowired OrderRepository orders;
    @Autowired TransactionTemplate tx;

    @AfterEach void clear() { TenantContext.clear(); }

    private Order newOrder(UUID quotationId, String orderNo) {
        return new Order(quotationId, UUID.randomUUID(), UUID.randomUUID(), orderNo,
            new BigDecimal("100.00"), new BigDecimal("18.00"), new BigDecimal("118.00"),
            "PO-1", LocalDate.of(2026, 7, 27));
    }

    @Test
    void persistsAndReadsBackWithinTenant() {
        UUID tenant = UUID.randomUUID();
        UUID quotationId = UUID.randomUUID();
        TenantContext.set(new TenantPrincipal(tenant, null, "OWNER"));
        UUID id = tx.execute(s -> orders.save(newOrder(quotationId, "ORD/25-26/0001")).getId());

        Order found = tx.execute(s -> orders.findByQuotationId(quotationId).orElseThrow());
        assertThat(found.getId()).isEqualTo(id);
        assertThat(found.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(found.getGrandTotal()).isEqualByComparingTo("118.00");
    }

    @Test
    void oneOrderPerQuotationIsEnforced() {
        UUID tenant = UUID.randomUUID();
        UUID quotationId = UUID.randomUUID();
        TenantContext.set(new TenantPrincipal(tenant, null, "OWNER"));
        tx.executeWithoutResult(s -> orders.save(newOrder(quotationId, "ORD/25-26/0001")));

        assertThatThrownBy(() ->
            tx.executeWithoutResult(s -> orders.saveAndFlush(newOrder(quotationId, "ORD/25-26/0002"))))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rlsHidesAnotherTenantsOrder() {
        UUID tenantA = UUID.randomUUID();
        UUID quotationId = UUID.randomUUID();
        TenantContext.set(new TenantPrincipal(tenantA, null, "OWNER"));
        tx.executeWithoutResult(s -> orders.save(newOrder(quotationId, "ORD/25-26/0001")));

        TenantContext.set(new TenantPrincipal(UUID.randomUUID(), null, "OWNER"));
        assertThat(tx.execute(s -> orders.findByQuotationId(quotationId))).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.OrderRepositoryTest'`
Expected: FAIL — `Order`, `OrderStatus`, `OrderRepository` don't exist (compile error).

- [ ] **Step 3: Create `OrderStatus`**

`backend/src/main/java/com/easycrm/sales/OrderStatus.java`:

```java
package com.easycrm.sales;

// Only CONFIRMED for now; DISPATCHED/CLOSED/CANCELLED arrive with the order-management slice.
public enum OrderStatus { CONFIRMED }
```

- [ ] **Step 4: Create the `Order` entity**

`backend/src/main/java/com/easycrm/sales/Order.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "sales_order", // "order" is a reserved SQL word
       uniqueConstraints = {
           @UniqueConstraint(name = "uq_order_tenant_no", columnNames = {"tenant_id", "order_no"}),
           @UniqueConstraint(name = "uq_order_tenant_quotation", columnNames = {"tenant_id", "quotation_id"})
       })
public class Order extends TenantScopedEntity {

    @Column(name = "order_no", nullable = false, length = 32)
    private String orderNo;

    @Column(name = "quotation_id", nullable = false)
    private UUID quotationId;

    @Column(name = "quotation_version_id", nullable = false)
    private UUID quotationVersionId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "po_reference")
    private String poReference;

    @Column(name = "po_date")
    private LocalDate poDate;

    @Column(name = "sub_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal subTotal;

    @Column(name = "total_tax", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalTax;

    @Column(name = "grand_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal grandTotal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    protected Order() {}

    public Order(UUID quotationId, UUID quotationVersionId, UUID customerId, String orderNo,
                 BigDecimal subTotal, BigDecimal totalTax, BigDecimal grandTotal,
                 String poReference, LocalDate poDate) {
        this.quotationId = quotationId;
        this.quotationVersionId = quotationVersionId;
        this.customerId = customerId;
        this.orderNo = orderNo;
        this.subTotal = subTotal;
        this.totalTax = totalTax;
        this.grandTotal = grandTotal;
        this.poReference = poReference;
        this.poDate = poDate;
        this.status = OrderStatus.CONFIRMED;
    }

    public String getOrderNo() { return orderNo; }
    public UUID getQuotationId() { return quotationId; }
    public UUID getQuotationVersionId() { return quotationVersionId; }
    public UUID getCustomerId() { return customerId; }
    public String getPoReference() { return poReference; }
    public LocalDate getPoDate() { return poDate; }
    public BigDecimal getSubTotal() { return subTotal; }
    public BigDecimal getTotalTax() { return totalTax; }
    public BigDecimal getGrandTotal() { return grandTotal; }
    public OrderStatus getStatus() { return status; }
}
```

- [ ] **Step 5: Create `OrderRepository`**

`backend/src/main/java/com/easycrm/sales/OrderRepository.java`:

```java
package com.easycrm.sales;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByQuotationId(UUID quotationId);

    Page<Order> findByCustomerId(UUID customerId, Pageable pageable);

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);
}
```

- [ ] **Step 6: Create migration `V18__sales_order.sql`**

`backend/src/main/resources/db/migration/V18__sales_order.sql`:

```sql
CREATE TABLE sales_order (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL,
    order_no             VARCHAR(32) NOT NULL,
    quotation_id         UUID NOT NULL,
    quotation_version_id UUID NOT NULL,
    customer_id          UUID NOT NULL,
    po_reference         VARCHAR(255),
    po_date              DATE,
    sub_total            NUMERIC(18,2) NOT NULL,
    total_tax            NUMERIC(18,2) NOT NULL,
    grand_total          NUMERIC(18,2) NOT NULL,
    status               VARCHAR(16) NOT NULL,
    created_at           TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ,
    version              BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_order_tenant_no UNIQUE (tenant_id, order_no),
    CONSTRAINT uq_order_tenant_quotation UNIQUE (tenant_id, quotation_id)
);
CREATE INDEX idx_order_tenant ON sales_order (tenant_id, id);
CREATE INDEX idx_order_customer ON sales_order (tenant_id, customer_id);
```

- [ ] **Step 7: Create migration `V19__rls_sales_order.sql`**

`backend/src/main/resources/db/migration/V19__rls_sales_order.sql`:

```sql
ALTER TABLE sales_order ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sales_order
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
```

- [ ] **Step 8: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.OrderRepositoryTest'`
Expected: PASS (3 tests). If ArchUnit runs, `TenantScopingArchTest` must still pass (`Order` extends `TenantScopedEntity`).

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/Order.java \
        backend/src/main/java/com/easycrm/sales/OrderStatus.java \
        backend/src/main/java/com/easycrm/sales/OrderRepository.java \
        backend/src/main/resources/db/migration/V18__sales_order.sql \
        backend/src/main/resources/db/migration/V19__rls_sales_order.sql \
        backend/src/test/java/com/easycrm/sales/OrderRepositoryTest.java
git commit -m "feat(sales): order aggregate with tenant isolation and one-order-per-quotation constraint"
```

---

## Task 2: Order numbering (`nextOrderNo`)

**Files:**
- Modify: `backend/src/main/java/com/easycrm/sales/DocumentNumberService.java`
- Test: `backend/src/test/java/com/easycrm/sales/DocumentNumberServiceTest.java`

**Interfaces:**
- Consumes: `DocumentNumberService` (existing `nextQuoteNo(LocalDate)`, `DocumentCounterRepository.findForUpdate(docType, fy)`).
- Produces: `String nextOrderNo(LocalDate onDate)` → `ORD/<FY>/<0000>` gapless per tenant/FY, using a `"ORDER"` counter independent of `"QUOTE"`.

- [ ] **Step 1: Write the failing test**

Add to `backend/src/test/java/com/easycrm/sales/DocumentNumberServiceTest.java` (new methods in the existing class — match its existing setup for tenant context; mirror the `nextQuoteNo` cases already there):

```java
    @Test
    void orderNumbersAreGaplessAndIndependentOfQuoteNumbers() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenant, null, "OWNER"));
        LocalDate d = LocalDate.of(2026, 7, 27); // FY 26-27

        // A quote counter in the same tenant/FY must not affect order numbering.
        tx.executeWithoutResult(s -> service.nextQuoteNo(d));

        String o1 = tx.execute(s -> service.nextOrderNo(d));
        String o2 = tx.execute(s -> service.nextOrderNo(d));
        assertThat(o1).isEqualTo("ORD/26-27/0001");
        assertThat(o2).isEqualTo("ORD/26-27/0002");
    }
```

> If the existing test class lacks a `tx`/`TenantContext`/`service` field or imports, copy them from the existing `nextQuoteNo` test method in the same file — do not invent a new pattern.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.DocumentNumberServiceTest'`
Expected: FAIL — `nextOrderNo` is undefined.

- [ ] **Step 3: Implement `nextOrderNo`**

In `DocumentNumberService.java`, add (mirrors `nextQuoteNo`):

```java
    /**
     * Assigns the next gapless order number for the tenant/FY of {@code onDate}. Uses a
     * "ORDER" counter, independent of "QUOTE". Must run in the caller's transaction so the
     * FOR UPDATE lock and increment commit atomically with the accept.
     */
    @Transactional
    public String nextOrderNo(LocalDate onDate) {
        String fy = financialYear(onDate);
        DocumentCounter counter = counters.findForUpdate("ORDER", fy)
            .orElseGet(() -> counters.save(new DocumentCounter("ORDER", fy)));
        long value = counter.getNextVal();
        counter.increment();
        return String.format("ORD/%s/%04d", fy, value);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.DocumentNumberServiceTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/DocumentNumberService.java \
        backend/src/test/java/com/easycrm/sales/DocumentNumberServiceTest.java
git commit -m "feat(sales): gapless per-tenant/FY order numbering"
```

---

## Task 3: Accept a SENT quotation → create order (happy path)

**Files:**
- Modify: `backend/src/main/java/com/easycrm/sales/QuotationStatus.java`
- Modify: `backend/src/main/java/com/easycrm/sales/Quotation.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/AcceptRequest.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/OrderResponse.java`
- Modify: `backend/src/main/java/com/easycrm/sales/QuotationService.java`
- Modify: `backend/src/main/java/com/easycrm/sales/web/QuotationController.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/QuotationAcceptTest.java`

**Interfaces:**
- Consumes: `Order` + `OrderRepository` (Task 1), `DocumentNumberService.nextOrderNo` (Task 2), `QuotationService.findQuotation`, `versions`, `customers`.
- Produces: `QuotationStatus.ACCEPTED`; `Quotation.markAccepted()`; `AcceptRequest(String poReference, LocalDate poDate)`; `OrderResponse.of(Order)`; `QuotationService.accept(UUID id, AcceptRequest req) -> OrderResponse`; `POST /api/v1/quotations/{id}/accept`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/easycrm/sales/web/QuotationAcceptTest.java`:

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

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class QuotationAcceptTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    /** Builds a customer + product + quotation, sends it, returns the quotation id. */
    private String createSent(String auth) throws Exception {
        String cust = """
            {"businessName":"Acme","stateCode":"27","source":"MANUAL"}""";
        String cId = JsonPath.read(mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(cust))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String prod = """
            {"sku":"SKU-%s","name":"W","hsnCode":"84818090","uom":"PCS","gstRate":"18","baseRate":"100.00"}"""
            .formatted(UUID.randomUUID().toString().substring(0, 8));
        String pId = JsonPath.read(mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(prod))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String body = """
            {"customerId":"%s","items":[{"productId":"%s","qty":"2"}]}""".formatted(cId, pId);
        String qId = JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth))
            .andExpect(status().isOk());
        return qId;
    }

    @Test
    void acceptingSentQuotationCreatesConfirmedOrder() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String qId = createSent(auth);

        // Read the sent quotation's grand total to compare against the order snapshot.
        String qJson = mvc.perform(get("/api/v1/quotations/" + qId).header("Authorization", auth))
            .andReturn().getResponse().getContentAsString();
        String grandTotal = JsonPath.read(qJson, "$.currentVersion.grandTotal");

        mvc.perform(post("/api/v1/quotations/" + qId + "/accept").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"poReference\":\"PO-99\",\"poDate\":\"2026-07-27\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.orderNo").value(matchesPattern("ORD/\\d{2}-\\d{2}/0001")))
            .andExpect(jsonPath("$.quotationId").value(qId))
            .andExpect(jsonPath("$.poReference").value("PO-99"))
            .andExpect(jsonPath("$.poDate").value("2026-07-27"))
            .andExpect(jsonPath("$.grandTotal").value(grandTotal));

        // The quotation is now ACCEPTED.
        mvc.perform(get("/api/v1/quotations/" + qId).header("Authorization", auth))
            .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void emptyBodyIsAccepted() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String qId = createSent(auth);
        mvc.perform(post("/api/v1/quotations/" + qId + "/accept").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.poReference").doesNotExist());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationAcceptTest'`
Expected: FAIL — the `/accept` endpoint and `accept` method don't exist (404 / compile error).

- [ ] **Step 3: Add `ACCEPTED` to `QuotationStatus`**

`backend/src/main/java/com/easycrm/sales/QuotationStatus.java`:

```java
package com.easycrm.sales;

public enum QuotationStatus { DRAFT, SENT, ACCEPTED, REJECTED, EXPIRED }
```

- [ ] **Step 4: Add `markAccepted()` to `Quotation`**

In `backend/src/main/java/com/easycrm/sales/Quotation.java`, alongside the other transition methods (after `markSent`):

```java
    public void markAccepted() { this.status = QuotationStatus.ACCEPTED; }
```

- [ ] **Step 5: Create the request + response DTOs**

`backend/src/main/java/com/easycrm/sales/web/dto/AcceptRequest.java`:

```java
package com.easycrm.sales.web.dto;

import java.time.LocalDate;

public record AcceptRequest(String poReference, LocalDate poDate) {}
```

`backend/src/main/java/com/easycrm/sales/web/dto/OrderResponse.java`:

```java
package com.easycrm.sales.web.dto;

import com.easycrm.sales.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record OrderResponse(UUID id, String orderNo, UUID quotationId, UUID quotationVersionId,
                            UUID customerId, String status, String poReference, LocalDate poDate,
                            BigDecimal subTotal, BigDecimal totalTax, BigDecimal grandTotal,
                            Instant createdAt) {

    public static OrderResponse of(Order o) {
        return new OrderResponse(o.getId(), o.getOrderNo(), o.getQuotationId(),
            o.getQuotationVersionId(), o.getCustomerId(), o.getStatus().name(),
            o.getPoReference(), o.getPoDate(), o.getSubTotal(), o.getTotalTax(),
            o.getGrandTotal(), o.getCreatedAt());
    }
}
```

> Money `BigDecimal` fields serialize as JSON strings via the global `BigDecimalStringModule` — no per-field annotation needed. Verified by `$.grandTotal` matching the quotation's string total in the test.

- [ ] **Step 6: Add `accept` to `QuotationService`**

In `QuotationService.java`, inject `OrderRepository` (add to constructor + field). Add the method (place beside `send`):

```java
    @Transactional
    public OrderResponse accept(UUID id, AcceptRequest req) {
        Quotation q = findQuotation(id);
        if (q.getStatus() == QuotationStatus.ACCEPTED) {
            // Idempotent: return the order already created for this quotation.
            return OrderResponse.of(orders.findByQuotationId(q.getId())
                .orElseThrow(() -> new NotFoundException("order not found")));
        }
        if (q.getStatus() != QuotationStatus.SENT) {
            throw new ValidationException("status", "only a sent quotation can be accepted");
        }
        QuotationVersion v = versions.findById(q.getCurrentVersionId())
            .orElseThrow(() -> new NotFoundException("quotation version not found"));
        Order order = orders.save(new Order(q.getId(), v.getId(), q.getCustomerId(),
            documentNumbers.nextOrderNo(LocalDate.now()),
            v.getSubTotal(), v.getTotalTax(), v.getGrandTotal(),
            req.poReference(), req.poDate()));
        q.markAccepted();
        return OrderResponse.of(order);
    }
```

Add the field + constructor parameter:

```java
    private final OrderRepository orders;
    // ...append to the constructor signature and body:
    //   OrderRepository orders  ->  this.orders = orders;
```

Add imports: `com.easycrm.sales.web.dto.AcceptRequest`, `com.easycrm.sales.web.dto.OrderResponse` (and `Order`, `OrderRepository` are same-package).

- [ ] **Step 7: Add the endpoint to `QuotationController`**

In `QuotationController.java`, add (after `send`):

```java
    @PostMapping("/{id}/accept")
    public OrderResponse accept(@PathVariable UUID id, @RequestBody(required = false) AcceptRequest req) {
        return service.accept(id, req == null ? new AcceptRequest(null, null) : req);
    }
```

Add imports: `com.easycrm.sales.web.dto.AcceptRequest`, `com.easycrm.sales.web.dto.OrderResponse`.

- [ ] **Step 8: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationAcceptTest'`
Expected: PASS (2 tests).

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/QuotationStatus.java \
        backend/src/main/java/com/easycrm/sales/Quotation.java \
        backend/src/main/java/com/easycrm/sales/QuotationService.java \
        backend/src/main/java/com/easycrm/sales/web/QuotationController.java \
        backend/src/main/java/com/easycrm/sales/web/dto/AcceptRequest.java \
        backend/src/main/java/com/easycrm/sales/web/dto/OrderResponse.java \
        backend/src/test/java/com/easycrm/sales/web/QuotationAcceptTest.java
git commit -m "feat(sales): accept a sent quotation into a confirmed order"
```

---

## Task 4: Idempotent re-accept + illegal-state guards

**Files:**
- Test: `backend/src/test/java/com/easycrm/sales/web/QuotationAcceptTest.java` (add cases)
- (No production change expected — Task 3's `accept` already handles these. This task *proves* the invariant and guards; if a test fails, fix `accept` minimally.)

**Interfaces:**
- Consumes: `QuotationService.accept` (Task 3), the reject/expire transitions on `QuotationController` (existing). No dependency on the order read API — idempotency is asserted via id-equality only.

- [ ] **Step 1: Write the failing/again-passing tests**

Add to `QuotationAcceptTest`:

```java
    @Test
    void reAcceptReturnsSameOrderAndCreatesNoSecondOrder() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String qId = createSent(auth);

        String firstOrderId = JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/accept")
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");

        String secondOrderId = JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/accept")
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");

        // Same order returned. "No second row" is additionally guaranteed by the
        // UNIQUE(tenant_id, quotation_id) constraint proven in Task 1 — a second insert
        // would have thrown, not returned a different id.
        org.assertj.core.api.Assertions.assertThat(secondOrderId).isEqualTo(firstOrderId);
    }

    @Test
    void acceptingADraftIsRejected() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        // create a draft but do NOT send it
        String cust = """
            {"businessName":"Acme","stateCode":"27","source":"MANUAL"}""";
        String cId = JsonPath.read(mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(cust))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String prod = """
            {"sku":"SKU-%s","name":"W","hsnCode":"84818090","uom":"PCS","gstRate":"18","baseRate":"100.00"}"""
            .formatted(UUID.randomUUID().toString().substring(0, 8));
        String pId = JsonPath.read(mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(prod))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String body = """
            {"customerId":"%s","items":[{"productId":"%s","qty":"1"}]}""".formatted(cId, pId);
        String qId = JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(post("/api/v1/quotations/" + qId + "/accept").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void acceptingARejectedQuotationIsRejected() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String qId = createSent(auth);
        mvc.perform(post("/api/v1/quotations/" + qId + "/reject").header("Authorization", auth))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/quotations/" + qId + "/accept").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnprocessableEntity());
    }
```

- [ ] **Step 2: Run tests**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationAcceptTest'`
Expected: all cases PASS immediately against Task 3's `accept` logic — `reAcceptReturnsSameOrderAndCreatesNoSecondOrder` (id-equality), `acceptingADraftIsRejected` (422), `acceptingARejectedQuotationIsRejected` (422). No new production code should be needed.

- [ ] **Step 3: Fix `accept` only if a guard test fails**

If `acceptingADraftIsRejected` or the rejected-quotation case fails, verify the guard order in `accept`: the `ACCEPTED` short-circuit comes first, then `!= SENT` → 422. No other change expected.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/easycrm/sales/web/QuotationAcceptTest.java
git commit -m "test(sales): idempotent re-accept and illegal-state accept guards"
```

---

## Task 5: `QuotationAcceptedEvent` + audit-log subscriber

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/QuotationAcceptedEvent.java`
- Create: `backend/src/main/java/com/easycrm/sales/OrderAcceptedAuditListener.java`
- Modify: `backend/src/main/java/com/easycrm/sales/QuotationService.java` (publish the event)
- Test: `backend/src/test/java/com/easycrm/sales/web/QuotationAcceptAuditTest.java`

**Interfaces:**
- Consumes: `AuditService.record(String, UUID, Map<String,Object>)` from `com.easycrm.iam`; `ApplicationEventPublisher`; `TenantContext.get()`.
- Produces: `QuotationAcceptedEvent(UUID quotationId, UUID orderId, UUID quotationVersionId, BigDecimal grandTotal, String orderNo, UUID actorUserId)`; `OrderAcceptedAuditListener` (writes `QUOTATION_ACCEPTED` audit row in the same transaction).

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/easycrm/sales/web/QuotationAcceptAuditTest.java`:

```java
package com.easycrm.sales.web;

import com.easycrm.iam.AuditLogRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class QuotationAcceptAuditTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired AuditLogRepository audits;
    @Autowired TransactionTemplate tx;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void acceptWritesQuotationAcceptedAuditRow() throws Exception {
        var owner = tokens.provisionOwner("27");
        String auth = "Bearer " + owner.token();

        // Minimal sent quotation.
        String cId = JsonPath.read(mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"businessName":"Acme","stateCode":"27","source":"MANUAL"}"""))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String pId = JsonPath.read(mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"sku":"SKU-%s","name":"W","hsnCode":"84818090","uom":"PCS","gstRate":"18","baseRate":"100.00"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 8))))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String qId = JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(
                    "{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"1\"}]}".formatted(cId, pId)))
            .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth))
            .andExpect(status().isOk());

        mvc.perform(post("/api/v1/quotations/" + qId + "/accept").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk());

        // Count QUOTATION_ACCEPTED audit rows in this tenant (RLS-scoped read).
        TenantContext.set(new TenantContext.TenantPrincipal(owner.tenantId(), null, "OWNER"));
        long count = tx.execute(s -> audits.countByAction("QUOTATION_ACCEPTED"));
        assertThat(count).isEqualTo(1);
    }
}
```

> `AuditLogRepository.countByAction(String)` exists (used by P0-auth tests). If its signature differs, adapt the assertion to whatever finder the repository exposes; do not add a new finder unless none fits.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationAcceptAuditTest'`
Expected: FAIL — no audit row written (`count == 0`).

- [ ] **Step 3: Create the event**

`backend/src/main/java/com/easycrm/sales/QuotationAcceptedEvent.java`:

```java
package com.easycrm.sales;

import java.math.BigDecimal;
import java.util.UUID;

public record QuotationAcceptedEvent(UUID quotationId, UUID orderId, UUID quotationVersionId,
                                     BigDecimal grandTotal, String orderNo, UUID actorUserId) {}
```

- [ ] **Step 4: Create the audit listener**

`backend/src/main/java/com/easycrm/sales/OrderAcceptedAuditListener.java`:

```java
package com.easycrm.sales;

import com.easycrm.iam.AuditService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OrderAcceptedAuditListener {

    private final AuditService audit;

    public OrderAcceptedAuditListener(AuditService audit) { this.audit = audit; }

    // Synchronous, runs in the publisher's transaction (Spring default) — the audit row
    // commits or rolls back together with the order (challenge #3 atomicity).
    @EventListener
    public void on(QuotationAcceptedEvent e) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("quotationId", e.quotationId().toString());
        detail.put("orderId", e.orderId().toString());
        detail.put("orderNo", e.orderNo());
        detail.put("grandTotal", e.grandTotal().toPlainString());
        audit.record("QUOTATION_ACCEPTED", e.actorUserId(), detail);
    }
}
```

- [ ] **Step 5: Publish the event from `accept`**

In `QuotationService.java`: inject `ApplicationEventPublisher events` (field + constructor). At the end of `accept`, after `q.markAccepted();` and before `return`:

```java
        UUID actorUserId = TenantContext.get()
            .map(TenantContext.TenantPrincipal::userId).orElse(null);
        events.publishEvent(new QuotationAcceptedEvent(q.getId(), order.getId(), v.getId(),
            order.getGrandTotal(), order.getOrderNo(), actorUserId));
        return OrderResponse.of(order);
```

Add import `org.springframework.context.ApplicationEventPublisher`.

- [ ] **Step 6: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationAcceptAuditTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/QuotationAcceptedEvent.java \
        backend/src/main/java/com/easycrm/sales/OrderAcceptedAuditListener.java \
        backend/src/main/java/com/easycrm/sales/QuotationService.java \
        backend/src/test/java/com/easycrm/sales/web/QuotationAcceptAuditTest.java
git commit -m "feat(sales): publish QuotationAcceptedEvent with same-transaction audit subscriber"
```

---

## Task 6: Order read API (`GET /orders`, `GET /orders/{id}`)

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/OrderService.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/OrderController.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/OrderReadTest.java`

**Interfaces:**
- Consumes: `OrderRepository` (Task 1), `OrderResponse` (Task 3), `PageResponse` (`com.easycrm.platform.web.PageResponse`), `NotFoundException`.
- Produces: `OrderService.get(UUID) -> OrderResponse`, `OrderService.list(OrderStatus, UUID customerId, Pageable) -> PageResponse<OrderResponse>`; `GET /api/v1/orders`, `GET /api/v1/orders/{id}`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/easycrm/sales/web/OrderReadTest.java`:

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
class OrderReadTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String createAcceptedOrderId(String auth) throws Exception {
        String cId = JsonPath.read(mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"businessName":"Acme","stateCode":"27","source":"MANUAL"}"""))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String pId = JsonPath.read(mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"sku":"SKU-%s","name":"W","hsnCode":"84818090","uom":"PCS","gstRate":"18","baseRate":"100.00"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 8))))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String qId = JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(
                    "{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"1\"}]}".formatted(cId, pId)))
            .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth));
        return JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/accept").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void getByIdAndListReturnTheOrder() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String orderId = createAcceptedOrderId(auth);

        mvc.perform(get("/api/v1/orders/" + orderId).header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(orderId))
            .andExpect(jsonPath("$.status").value("CONFIRMED"));

        mvc.perform(get("/api/v1/orders").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(orderId));
    }

    @Test
    void crossTenantGetReturns404() throws Exception {
        String authA = "Bearer " + tokens.provisionOwner("27").token();
        String orderId = createAcceptedOrderId(authA);

        String authB = "Bearer " + tokens.provisionOwner("29").token();
        mvc.perform(get("/api/v1/orders/" + orderId).header("Authorization", authB))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.OrderReadTest'`
Expected: FAIL — `/api/v1/orders` routes don't exist (404 on GET / compile error).

- [ ] **Step 3: Create `OrderService`**

`backend/src/main/java/com/easycrm/sales/OrderService.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.web.dto.OrderResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orders;

    public OrderService(OrderRepository orders) { this.orders = orders; }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID id) {
        return OrderResponse.of(orders.findById(id)
            .orElseThrow(() -> new NotFoundException("order not found")));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> list(OrderStatus status, UUID customerId, Pageable pageable) {
        Page<Order> page;
        if (status != null) page = orders.findByStatus(status, pageable);
        else if (customerId != null) page = orders.findByCustomerId(customerId, pageable);
        else page = orders.findAll(pageable);
        return PageResponse.of(page.map(OrderResponse::of));
    }
}
```

- [ ] **Step 4: Create `OrderController`**

`backend/src/main/java/com/easycrm/sales/web/OrderController.java`:

```java
package com.easycrm.sales.web;

import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.OrderService;
import com.easycrm.sales.OrderStatus;
import com.easycrm.sales.web.dto.OrderResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) { this.service = service; }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable UUID id) { return service.get(id); }

    @GetMapping
    public PageResponse<OrderResponse> list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) UUID customerId,
            Pageable pageable) {
        return service.list(status, customerId, pageable);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.OrderReadTest'`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/OrderService.java \
        backend/src/main/java/com/easycrm/sales/web/OrderController.java \
        backend/src/test/java/com/easycrm/sales/web/OrderReadTest.java
git commit -m "feat(sales): order read endpoints with cross-tenant 404"
```

---

## Task 7: Full-suite verification + docs wrap-up

**Files:**
- Modify: `docs/superpowers/engineering-challenges.md`
- Modify: `docs/superpowers/annotations-reference.md`
- Modify: `docs/superpowers/HANDOFF.md`

- [ ] **Step 1: Run the full suite from clean**

Run: `cd backend && ./gradlew clean test`
Expected: PASS. Baseline was 120; this slice adds ~13 tests (Order repo 3, numbering 1, accept 5, audit 1, order read 2, + any) → expect ~133 green. Record the actual number.

- [ ] **Step 2: Log engineering challenges**

Append three entries to `docs/superpowers/engineering-challenges.md` using the template at the bottom of that file (Problem → why hard → Solution → Lesson):

1. **`order` is a reserved SQL word** — an unquoted `order` table breaks Flyway DDL and HQL; naming the physical table `sales_order` (class stays `Order`) avoids identifier-quoting across migrations, RLS policies, and JPA.
2. **Natural (state-based) idempotency for accept** — instead of a client idempotency key + table (challenge #3's sketch), "exactly one order per quotation" is a domain invariant enforced by `UNIQUE(tenant_id, quotation_id)` + the quotation `@Version`. Re-accept returns the existing order; a raced double-tap loses the unique-constraint/optimistic-lock race. This is challenge #3's own "weakest tool that's actually sufficient" lesson applied — the quotation id *is* the idempotency key.
3. **`QuotationAcceptedEvent` as a side-effect seam** — the parent spec said "the order handler subscribes," but creating the order *inside* the accept command (and publishing the event afterward for audit/activity/WhatsApp) avoids using the event as a return channel while keeping same-transaction atomicity and the open/closed seam.

- [ ] **Step 3: Update the annotations reference**

Add a row to `docs/superpowers/annotations-reference.md` for `@EventListener` (origin: `org.springframework.context.event`; purpose: register a method as a synchronous application-event listener running in the publisher's transaction by default; composition: plain annotation, not meta-composed). If `@RequestBody(required = false)` usage is not already documented, note it too.

- [ ] **Step 4: Update the handoff**

In `docs/superpowers/HANDOFF.md`: move the order/accept item from "deferred" to done; note the new `sales_order` table, the `ACCEPTED` status, `QuotationAcceptedEvent` + audit subscriber, natural idempotency, and the read endpoints; update the test count; and note what remains deferred (enquiry, order status transitions, PDF/WhatsApp, scheduled expiry, visibility filtering, cursor pagination).

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/engineering-challenges.md \
        docs/superpowers/annotations-reference.md \
        docs/superpowers/HANDOFF.md
git commit -m "docs(sales): log order/accept challenges, annotations, handoff update"
```

---

## Self-Review (completed during planning)

- **Spec coverage:** §2 scope → Tasks 1–6; §4 entity/migrations → Task 1; §5 accept + idempotency → Tasks 3–4; §6 event/audit → Task 5; §7 read API → Task 6; §8 errors → Tasks 3/4/6; §9 tests → each task's test; §10 docs → Task 7. No gap.
- **Type consistency:** `accept(UUID, AcceptRequest) -> OrderResponse`, `nextOrderNo(LocalDate) -> String`, `OrderResponse.of(Order)`, `QuotationAcceptedEvent(quotationId, orderId, quotationVersionId, grandTotal, orderNo, actorUserId)`, `findByQuotationId(UUID) -> Optional<Order>` are used identically wherever referenced.
- **Task independence:** every task's test passes against code available at that task — no forward dependency. Task 4 proves idempotency via id-equality (not `GET /orders`), so it does not depend on Task 6.
- **Placeholder scan:** none — every code step shows full code; every run step shows the command + expected result.
