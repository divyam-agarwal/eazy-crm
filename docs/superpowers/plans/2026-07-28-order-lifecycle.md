# Order Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `Order` a real lifecycle — `DISPATCHED`, `CLOSED`, `CANCELLED` with guarded transitions, an audit trail, and the deferred order-list filter fix.

**Architecture:** Transition guards live in the `Order` entity (the `Enquiry` pattern), so the state machine is structural rather than advisory and is testable without Spring. `OrderService` stays thin: find → call the entity method → publish one generic `OrderStatusChangedEvent` → map to the response. A synchronous `@EventListener` in the publisher's transaction writes the audit row. The list endpoint moves from `if/else if` filter selection to an AND-composing JPA `Specification`.

**Tech Stack:** Java 25, Spring Boot 4.1, Hibernate 7, PostgreSQL + RLS, Flyway, JUnit 5 + AssertJ, MockMvc, Testcontainers, jayway JsonPath.

**Spec:** `docs/superpowers/specs/2026-07-28-order-lifecycle-design.md` — read it before starting.

## Global Constraints

- **Money is never a `double`.** `BigDecimal` in Java, `NUMERIC` in Postgres, JSON **string** on the wire (`platform.money.BigDecimalStringModule` handles serialization globally). This slice adds no money fields, but existing ones must keep serializing as strings.
- **Tenant isolation is structural.** Never hand-write `WHERE tenant_id = ?`. Rely on Hibernate `@TenantId` + Postgres RLS; the tenant comes from the JWT only. `Order` already extends `TenantScopedEntity`; this slice adds no new entities.
- **`ddl-auto: validate` is on.** Migration column types must match entity mappings exactly, or the application context fails to start and *every* integration test fails. `VARCHAR(500)` nullable ↔ `@Column(name = "cancel_reason", length = 500) private String cancelReason;`.
- **Error mapping is already global** in `platform.error.ApiExceptionHandler`: `ValidationException` → **422**, `ConflictException` → **409**, `NotFoundException` → **404**, `DataIntegrityViolationException` → **409**, `OptimisticLockingFailureException` → **409**, `MethodArgumentNotValidException` (bean validation) → **400**. Do not add new handlers.
- **Tests that read a `Tenant` row need `tokens.provisionOwner(stateCode)`**, not `TestTokens.owner(UUID.randomUUID())`. Every test in this plan reaches through quotation creation, which reads `Tenant.state_code` for the GST split — so all of them use `provisionOwner`.
- **Commits:** author as `divyam <divyam.0444@gmail.com>` (repo git config is already set — use plain `git commit`). **Never** add a `Co-Authored-By: Claude` trailer or mention Claude/AI anywhere in a commit message.
- **TDD, one task per commit:** write the failing test → run it and confirm it fails → write the minimal implementation → run it and confirm it passes → run the full suite → commit.
- **Build:** always `cd backend && ./gradlew …` (the wrapper pins the JDK 25 toolchain; the shell default is JDK 21 — do not change it). **Docker must be running** for Testcontainers: `open -a Docker`, then wait for `docker info` to succeed. In this harness, Docker operations may need the Bash tool's sandbox disabled (`dangerouslyDisableSandbox: true`).
- **Baseline:** `main` is green at **166 tests**. This plan adds **19** (8 + 5 + 2 + 3 + 1). Final expected total: **185**.

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V23__order_cancel_reason.sql` | Adds the one new column |
| `backend/src/main/java/com/easycrm/sales/OrderStatusChangedEvent.java` | The single event covering all three transitions |
| `backend/src/main/java/com/easycrm/sales/OrderStatusChangedAuditListener.java` | Turns that event into an audit row |
| `backend/src/main/java/com/easycrm/sales/OrderSpecifications.java` | AND-composing list filters |
| `backend/src/main/java/com/easycrm/sales/web/dto/CancelRequest.java` | `/cancel` request body |
| `backend/src/test/java/com/easycrm/sales/OrderTest.java` | The state machine, no Spring |
| `backend/src/test/java/com/easycrm/sales/web/OrderTransitionTest.java` | The three endpoints end to end |
| `backend/src/test/java/com/easycrm/sales/web/OrderStatusAuditTest.java` | Audit rows per transition |
| `backend/src/test/java/com/easycrm/sales/web/OrderListTest.java` | Filter-composition regression |

**Modified:**

| File | Change |
|---|---|
| `backend/src/main/java/com/easycrm/sales/OrderStatus.java` | Three new members + `isTerminal()`/`isActive()` |
| `backend/src/main/java/com/easycrm/sales/Order.java` | `cancelReason` field + three guarded transitions |
| `backend/src/main/java/com/easycrm/sales/OrderService.java` | Three transition methods, event publishing, `Specification`-based list |
| `backend/src/main/java/com/easycrm/sales/OrderRepository.java` | Add `JpaSpecificationExecutor`, drop two derived finders |
| `backend/src/main/java/com/easycrm/sales/QuotationService.java` | Cancelled-order case on the idempotent accept branch |
| `backend/src/main/java/com/easycrm/sales/web/OrderController.java` | Three new routes |
| `backend/src/main/java/com/easycrm/sales/web/dto/OrderResponse.java` | New `cancelReason` field |
| `backend/src/test/java/com/easycrm/sales/web/QuotationAcceptTest.java` | One new case |
| `docs/superpowers/engineering-challenges.md` | Challenge #27 |
| `docs/superpowers/annotations-reference.md` | Only if a genuinely new annotation appears |
| `docs/superpowers/HANDOFF.md` | Current state, next-chunk menu, deferred list |

---

## Task 1: State machine in the entity

**Files:**
- Modify: `backend/src/main/java/com/easycrm/sales/OrderStatus.java`
- Modify: `backend/src/main/java/com/easycrm/sales/Order.java`
- Create: `backend/src/main/resources/db/migration/V23__order_cancel_reason.sql`
- Test: `backend/src/test/java/com/easycrm/sales/OrderTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `OrderStatus.{CONFIRMED, DISPATCHED, CLOSED, CANCELLED}`, `OrderStatus.isTerminal()`, `OrderStatus.isActive()`; `Order.dispatch()`, `Order.close()`, `Order.cancel(String reason)`, `Order.getCancelReason()`. All later tasks depend on these exact names.

**Why the migration lands in this task:** `ddl-auto: validate` compares the mapping to the live schema at context startup. Adding the `cancelReason` field without the column breaks every integration test in the repo, so the field and the migration must be committed together.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/sales/OrderTest.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.error.ValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    /** A freshly accepted order: CONFIRMED, no cancel reason. */
    private Order newOrder() {
        return new Order(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "ORD/2526/0001", new BigDecimal("100.00"), new BigDecimal("18.00"),
            new BigDecimal("118.00"), null, null);
    }

    private Order dispatched() {
        Order o = newOrder();
        o.dispatch();
        return o;
    }

    private Order closed() {
        Order o = dispatched();
        o.close();
        return o;
    }

    private Order cancelled() {
        Order o = newOrder();
        o.cancel("customer withdrew PO");
        return o;
    }

    @Test
    void startsConfirmedWithNoCancelReason() {
        Order o = newOrder();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(o.getCancelReason()).isNull();
    }

    @Test
    void terminalPredicatesMatchTheGraph() {
        assertThat(OrderStatus.CONFIRMED.isActive()).isTrue();
        assertThat(OrderStatus.DISPATCHED.isActive()).isTrue();
        assertThat(OrderStatus.CLOSED.isTerminal()).isTrue();
        assertThat(OrderStatus.CANCELLED.isTerminal()).isTrue();
    }

    @Test
    void dispatchThenCloseIsTheHappyPath() {
        Order o = newOrder();
        o.dispatch();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.DISPATCHED);
        o.close();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CLOSED);
    }

    @Test
    void dispatchRejectedUnlessConfirmed() {
        assertThatThrownBy(() -> dispatched().dispatch()).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> closed().dispatch()).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> cancelled().dispatch()).isInstanceOf(ValidationException.class);
    }

    @Test
    void closeRejectedUnlessDispatched() {
        // no skipping dispatch: CONFIRMED -> CLOSED is not a legal edge
        assertThatThrownBy(() -> newOrder().close()).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> closed().close()).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> cancelled().close()).isInstanceOf(ValidationException.class);
    }

    @Test
    void cancelAllowedFromBothActiveStatesAndStoresTheReason() {
        Order fromConfirmed = newOrder();
        fromConfirmed.cancel("customer withdrew PO");
        assertThat(fromConfirmed.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(fromConfirmed.getCancelReason()).isEqualTo("customer withdrew PO");

        Order fromDispatched = dispatched();
        fromDispatched.cancel("goods returned");
        assertThat(fromDispatched.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(fromDispatched.getCancelReason()).isEqualTo("goods returned");
    }

    @Test
    void cancelRejectedOnTerminalStates() {
        assertThatThrownBy(() -> closed().cancel("too late"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> cancelled().cancel("again"))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void cancelRequiresANonBlankReasonAndLeavesTheOrderUntouched() {
        assertThatThrownBy(() -> newOrder().cancel(null)).isInstanceOf(ValidationException.class);
        Order o = newOrder();
        assertThatThrownBy(() -> o.cancel("   ")).isInstanceOf(ValidationException.class);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(o.getCancelReason()).isNull();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.sales.OrderTest"`

Expected: **compilation failure**, not an assertion failure — `cannot find symbol: method dispatch()` / `close()` / `cancel(String)` / `getCancelReason()` / `variable DISPATCHED`. In Java TDD this is the correct red state; do not "fix" it by stubbing before Step 3.

- [ ] **Step 3: Widen the enum**

Replace the whole of `backend/src/main/java/com/easycrm/sales/OrderStatus.java`:

```java
package com.easycrm.sales;

public enum OrderStatus {
    CONFIRMED, DISPATCHED, CLOSED, CANCELLED;

    public boolean isTerminal() { return this == CLOSED || this == CANCELLED; }
    public boolean isActive()   { return !isTerminal(); }
}
```

- [ ] **Step 4: Add the field and the guarded transitions to `Order`**

In `backend/src/main/java/com/easycrm/sales/Order.java`, add the import:

```java
import com.easycrm.platform.error.ValidationException;
```

Add the field immediately after the existing `status` field:

```java
    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;
```

Add the three transitions immediately after the constructor (before the getters):

```java
    /** CONFIRMED -> DISPATCHED. Dispatch is a status flag only; no dispatch details are modelled. */
    public void dispatch() {
        if (status != OrderStatus.CONFIRMED) {
            throw new ValidationException("status", "only a confirmed order can be dispatched");
        }
        this.status = OrderStatus.DISPATCHED;
    }

    /** DISPATCHED -> CLOSED. No skipping: a confirmed order must be dispatched first. */
    public void close() {
        if (status != OrderStatus.DISPATCHED) {
            throw new ValidationException("status", "only a dispatched order can be closed");
        }
        this.status = OrderStatus.CLOSED;
    }

    /**
     * CONFIRMED or DISPATCHED -> CANCELLED. Terminal, and a reason is mandatory.
     * Both checks run before any mutation, so a rejected cancel leaves the order untouched.
     */
    public void cancel(String reason) {
        if (status.isTerminal()) {
            throw new ValidationException("status",
                "a " + status.name().toLowerCase() + " order cannot be cancelled");
        }
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("cancelReason", "a reason is required to cancel an order");
        }
        this.status = OrderStatus.CANCELLED;
        this.cancelReason = reason;
    }
```

Add the getter alongside the existing ones:

```java
    public String getCancelReason() { return cancelReason; }
```

- [ ] **Step 5: Add the migration**

Create `backend/src/main/resources/db/migration/V23__order_cancel_reason.sql`:

```sql
-- Cancellation reason, mirroring enquiry.lost_reason. Nullable: null on every order
-- that was never cancelled. No status-column change is needed — status is already
-- VARCHAR(16) and the longest new enum name (DISPATCHED) is 10 characters.
ALTER TABLE sales_order ADD COLUMN cancel_reason VARCHAR(500);
```

- [ ] **Step 6: Run the unit test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.sales.OrderTest"`
Expected: **PASS**, 8 tests.

- [ ] **Step 7: Run the full suite**

Run: `cd backend && ./gradlew test`
Expected: **PASS**, 174 tests (166 baseline + 8). This is the step that proves the migration matches the mapping — a `ddl-auto: validate` mismatch shows up as a context-startup failure across every integration test, not as a single failing assertion.

- [ ] **Step 8: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/main/java/com/easycrm/sales/OrderStatus.java \
        backend/src/main/java/com/easycrm/sales/Order.java \
        backend/src/main/resources/db/migration/V23__order_cancel_reason.sql \
        backend/src/test/java/com/easycrm/sales/OrderTest.java
git commit -m "feat(sales): guarded order state machine (DISPATCHED/CLOSED/CANCELLED)

Adds the three remaining OrderStatus members with isTerminal()/isActive()
predicates, and three guarded transitions on the Order entity: dispatch()
(CONFIRMED only), close() (DISPATCHED only) and cancel(reason) (either
active state, reason mandatory). Guards live in the entity so no caller can
bypass them and the machine is testable without Spring.

Each verb states its own precondition rather than comparing enum positions,
so reordering the constants cannot change behaviour.

V23 adds the nullable cancel_reason column; the status column is unchanged
(already VARCHAR(16), values persist as enum names)."
```

---

## Task 2: Transition endpoints

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/CancelRequest.java`
- Modify: `backend/src/main/java/com/easycrm/sales/web/dto/OrderResponse.java`
- Modify: `backend/src/main/java/com/easycrm/sales/OrderService.java`
- Modify: `backend/src/main/java/com/easycrm/sales/web/OrderController.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/OrderTransitionTest.java`

**Interfaces:**
- Consumes: `Order.dispatch()`, `Order.close()`, `Order.cancel(String)`, `Order.getCancelReason()` from Task 1.
- Produces: `OrderService.dispatch(UUID)`, `OrderService.close(UUID)`, `OrderService.cancel(UUID, String)` — all returning `OrderResponse`; the private `OrderService.find(UUID)` helper; `CancelRequest(String cancelReason)`; `OrderResponse` with `cancelReason` as its **7th** component (immediately after `status`). Task 3 adds event publishing inside these same three service methods.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/sales/web/OrderTransitionTest.java`:

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
class OrderTransitionTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    /** Customer + product + quotation -> send -> accept. Returns the new order's id. */
    private String createOrder(String auth) throws Exception {
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
                    "{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"1\"}]}"
                        .formatted(cId, pId)))
            .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth))
            .andExpect(status().isOk());
        return JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/accept")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void dispatchThenCloseWalksTheOrderToClosed() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = createOrder(auth);

        mvc.perform(post("/api/v1/orders/" + id + "/dispatch").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DISPATCHED"))
            .andExpect(jsonPath("$.cancelReason").doesNotExist());

        mvc.perform(post("/api/v1/orders/" + id + "/close").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CLOSED"));

        // terminal: no further transitions
        mvc.perform(post("/api/v1/orders/" + id + "/close").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/orders/" + id + "/dispatch").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void closeBeforeDispatchIsRejected() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = createOrder(auth);

        mvc.perform(post("/api/v1/orders/" + id + "/close").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void cancelStoresTheReasonAndBlocksFurtherTransitions() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = createOrder(auth);

        mvc.perform(post("/api/v1/orders/" + id + "/cancel").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"cancelReason":"customer withdrew PO"}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancelReason").value("customer withdrew PO"));

        mvc.perform(post("/api/v1/orders/" + id + "/dispatch").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/orders/" + id + "/cancel").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"cancelReason":"again"}"""))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void blankCancelReasonIsRejectedAtTheEdgeWith400() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = createOrder(auth);

        // @NotBlank fires before the controller body runs -> 400, not the entity's 422.
        mvc.perform(post("/api/v1/orders/" + id + "/cancel").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"cancelReason":"   "}"""))
            .andExpect(status().isBadRequest());
    }

    @Test
    void crossTenantTransitionReturns404() throws Exception {
        String authA = "Bearer " + tokens.provisionOwner("27").token();
        String id = createOrder(authA);

        String authB = "Bearer " + tokens.provisionOwner("29").token();
        mvc.perform(post("/api/v1/orders/" + id + "/dispatch").header("Authorization", authB))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.sales.web.OrderTransitionTest"`
Expected: **FAIL** — the routes don't exist, so every transition call returns 404 (and `crossTenantTransitionReturns404` passes for the wrong reason; that's fine, the others are red).

- [ ] **Step 3: Add the `CancelRequest` DTO**

Create `backend/src/main/java/com/easycrm/sales/web/dto/CancelRequest.java`:

```java
package com.easycrm.sales.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The @NotBlank here and Order.cancel's own blank check are both intended: bean
 * validation rejects a blank reason at the HTTP edge with 400, while the entity guard
 * still protects non-HTTP callers with 422. Mirrors LoseRequest / Enquiry.lose.
 */
public record CancelRequest(@NotBlank String cancelReason) {}
```

- [ ] **Step 4: Add `cancelReason` to `OrderResponse`**

Replace the whole of `backend/src/main/java/com/easycrm/sales/web/dto/OrderResponse.java`:

```java
package com.easycrm.sales.web.dto;

import com.easycrm.sales.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record OrderResponse(UUID id, String orderNo, UUID quotationId, UUID quotationVersionId,
                            UUID customerId, String status, String cancelReason,
                            String poReference, LocalDate poDate,
                            BigDecimal subTotal, BigDecimal totalTax, BigDecimal grandTotal,
                            Instant createdAt) {

    public static OrderResponse of(Order o) {
        return new OrderResponse(o.getId(), o.getOrderNo(), o.getQuotationId(),
            o.getQuotationVersionId(), o.getCustomerId(), o.getStatus().name(),
            o.getCancelReason(), o.getPoReference(), o.getPoDate(), o.getSubTotal(),
            o.getTotalTax(), o.getGrandTotal(), o.getCreatedAt());
    }
}
```

- [ ] **Step 5: Add the three service methods**

In `backend/src/main/java/com/easycrm/sales/OrderService.java`, replace the `get` method with the version below and add the three transitions plus the `find` helper. The class body becomes:

```java
    @Transactional(readOnly = true)
    public OrderResponse get(UUID id) {
        return OrderResponse.of(find(id));
    }

    @Transactional
    public OrderResponse dispatch(UUID id) {
        Order o = find(id);
        o.dispatch();
        return OrderResponse.of(o);
    }

    @Transactional
    public OrderResponse close(UUID id) {
        Order o = find(id);
        o.close();
        return OrderResponse.of(o);
    }

    @Transactional
    public OrderResponse cancel(UUID id, String reason) {
        Order o = find(id);
        o.cancel(reason);
        return OrderResponse.of(o);
    }

    /** Cross-tenant rows are invisible to RLS, so "not mine" and "not there" both 404. */
    private Order find(UUID id) {
        return orders.findById(id)
            .orElseThrow(() -> new NotFoundException("order not found"));
    }
```

Leave the existing `list` method exactly as it is — Task 4 rewrites it.

- [ ] **Step 6: Add the three routes**

In `backend/src/main/java/com/easycrm/sales/web/OrderController.java`, add these imports:

```java
import com.easycrm.sales.web.dto.CancelRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
```

and these methods after the existing `get`:

```java
    @PostMapping("/{id}/dispatch")
    public OrderResponse dispatch(@PathVariable UUID id) { return service.dispatch(id); }

    @PostMapping("/{id}/close")
    public OrderResponse close(@PathVariable UUID id) { return service.close(id); }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest req) {
        return service.cancel(id, req.cancelReason());
    }
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.sales.web.OrderTransitionTest"`
Expected: **PASS**, 5 tests.

- [ ] **Step 8: Run the full suite**

Run: `cd backend && ./gradlew test`
Expected: **PASS**, 179 tests.

- [ ] **Step 9: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/main/java/com/easycrm/sales/web/dto/CancelRequest.java \
        backend/src/main/java/com/easycrm/sales/web/dto/OrderResponse.java \
        backend/src/main/java/com/easycrm/sales/OrderService.java \
        backend/src/main/java/com/easycrm/sales/web/OrderController.java \
        backend/src/test/java/com/easycrm/sales/web/OrderTransitionTest.java
git commit -m "feat(sales): POST /orders/{id}/dispatch, /close and /cancel

Verb-per-endpoint, matching QuotationController's send/reject/expire. The
graph is strictly linear, so each active state has exactly one legal advance
and a target-taking endpoint would carry no information.

Illegal transitions surface as 422 through the entity guards, a blank
cancelReason as 400 through @NotBlank, and cross-tenant ids as 404 through
RLS plus the existing find-or-throw path. OrderResponse now carries
cancelReason, null on every order that was never cancelled."
```

---

## Task 3: Status-change events and audit rows

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/OrderStatusChangedEvent.java`
- Create: `backend/src/main/java/com/easycrm/sales/OrderStatusChangedAuditListener.java`
- Modify: `backend/src/main/java/com/easycrm/sales/OrderService.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/OrderStatusAuditTest.java`

**Interfaces:**
- Consumes: `OrderService.dispatch/close/cancel` from Task 2; `Order.getCancelReason()` from Task 1; the existing `AuditService.record(String action, UUID actorUserId, Map<String, Object> detail)` and `AuditLogRepository.countByAction(String)`.
- Produces: `OrderStatusChangedEvent(UUID orderId, String orderNo, OrderStatus from, OrderStatus to, String cancelReason, UUID actorUserId)`; audit actions `ORDER_DISPATCHED`, `ORDER_CLOSED`, `ORDER_CANCELLED`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/sales/web/OrderStatusAuditTest.java`:

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
class OrderStatusAuditTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired AuditLogRepository audits;
    @Autowired TransactionTemplate tx;

    @AfterEach void clear() { TenantContext.clear(); }

    /** Customer + product + quotation -> send -> accept. Returns the new order's id. */
    private String createOrder(String auth) throws Exception {
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
                    "{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"1\"}]}"
                        .formatted(cId, pId)))
            .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth))
            .andExpect(status().isOk());
        return JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/accept")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void dispatchAndCloseEachWriteAnAuditRow() throws Exception {
        var owner = tokens.provisionOwner("27");
        String auth = "Bearer " + owner.token();
        String id = createOrder(auth);

        mvc.perform(post("/api/v1/orders/" + id + "/dispatch").header("Authorization", auth))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/orders/" + id + "/close").header("Authorization", auth))
            .andExpect(status().isOk());

        // RLS-scoped read: set the tenant context, then count inside a transaction.
        TenantContext.set(new TenantContext.TenantPrincipal(owner.tenantId(), null, "OWNER"));
        long dispatched = tx.execute(s -> audits.countByAction("ORDER_DISPATCHED"));
        long closed = tx.execute(s -> audits.countByAction("ORDER_CLOSED"));
        long cancelled = tx.execute(s -> audits.countByAction("ORDER_CANCELLED"));
        assertThat(dispatched).isEqualTo(1);
        assertThat(closed).isEqualTo(1);
        assertThat(cancelled).isEqualTo(0);
    }

    @Test
    void cancelWritesAnAuditRowAndARejectedTransitionWritesNone() throws Exception {
        var owner = tokens.provisionOwner("27");
        String auth = "Bearer " + owner.token();
        String id = createOrder(auth);

        mvc.perform(post("/api/v1/orders/" + id + "/cancel").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"cancelReason":"customer withdrew PO"}"""))
            .andExpect(status().isOk());
        // rejected transition on a terminal order: no second row
        mvc.perform(post("/api/v1/orders/" + id + "/dispatch").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity());

        TenantContext.set(new TenantContext.TenantPrincipal(owner.tenantId(), null, "OWNER"));
        long cancelled = tx.execute(s -> audits.countByAction("ORDER_CANCELLED"));
        long dispatched = tx.execute(s -> audits.countByAction("ORDER_DISPATCHED"));
        assertThat(cancelled).isEqualTo(1);
        assertThat(dispatched).isEqualTo(0);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.sales.web.OrderStatusAuditTest"`
Expected: **FAIL** — `expected: 1L but was: 0L` on the `ORDER_DISPATCHED` count. Nothing publishes or records yet.

- [ ] **Step 3: Add the event**

Create `backend/src/main/java/com/easycrm/sales/OrderStatusChangedEvent.java`:

```java
package com.easycrm.sales;

import java.util.UUID;

/**
 * One event for all three transitions, so the number of event types stays fixed as
 * statuses are added. {@code cancelReason} is null except on a cancellation.
 */
public record OrderStatusChangedEvent(UUID orderId, String orderNo, OrderStatus from,
                                      OrderStatus to, String cancelReason, UUID actorUserId) {}
```

- [ ] **Step 4: Add the listener**

Create `backend/src/main/java/com/easycrm/sales/OrderStatusChangedAuditListener.java`:

```java
package com.easycrm.sales;

import com.easycrm.iam.AuditService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OrderStatusChangedAuditListener {

    private final AuditService audit;

    public OrderStatusChangedAuditListener(AuditService audit) { this.audit = audit; }

    // Synchronous, runs in the publisher's transaction (Spring default) — the audit row
    // commits or rolls back together with the status change (challenge #3 atomicity).
    // Sibling of OrderAcceptedAuditListener; both move to after-commit + outbox when the
    // first external-I/O slice lands (challenge #22).
    @EventListener
    public void on(OrderStatusChangedEvent e) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("orderId", e.orderId().toString());
        detail.put("orderNo", e.orderNo());
        detail.put("from", e.from().name());
        if (e.cancelReason() != null) {
            detail.put("cancelReason", e.cancelReason());
        }
        audit.record("ORDER_" + e.to().name(), e.actorUserId(), detail);
    }
}
```

- [ ] **Step 5: Publish from the service**

In `backend/src/main/java/com/easycrm/sales/OrderService.java`, add these imports:

```java
import com.easycrm.platform.tenancy.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
```

Replace the field declaration and constructor with:

```java
    private final OrderRepository orders;
    private final ApplicationEventPublisher events;

    public OrderService(OrderRepository orders, ApplicationEventPublisher events) {
        this.orders = orders;
        this.events = events;
    }
```

Replace the three transition methods so each captures the previous status and publishes after a successful transition (a rejected transition throws before reaching `publish`, so no event and no audit row):

```java
    @Transactional
    public OrderResponse dispatch(UUID id) {
        Order o = find(id);
        OrderStatus from = o.getStatus();
        o.dispatch();
        publish(o, from);
        return OrderResponse.of(o);
    }

    @Transactional
    public OrderResponse close(UUID id) {
        Order o = find(id);
        OrderStatus from = o.getStatus();
        o.close();
        publish(o, from);
        return OrderResponse.of(o);
    }

    @Transactional
    public OrderResponse cancel(UUID id, String reason) {
        Order o = find(id);
        OrderStatus from = o.getStatus();
        o.cancel(reason);
        publish(o, from);
        return OrderResponse.of(o);
    }

    private void publish(Order o, OrderStatus from) {
        UUID actorUserId = TenantContext.get()
            .map(TenantContext.TenantPrincipal::userId).orElse(null);
        events.publishEvent(new OrderStatusChangedEvent(o.getId(), o.getOrderNo(), from,
            o.getStatus(), o.getCancelReason(), actorUserId));
    }
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.sales.web.OrderStatusAuditTest"`
Expected: **PASS**, 2 tests.

- [ ] **Step 7: Run the full suite**

Run: `cd backend && ./gradlew test`
Expected: **PASS**, 181 tests.

- [ ] **Step 8: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/main/java/com/easycrm/sales/OrderStatusChangedEvent.java \
        backend/src/main/java/com/easycrm/sales/OrderStatusChangedAuditListener.java \
        backend/src/main/java/com/easycrm/sales/OrderService.java \
        backend/src/test/java/com/easycrm/sales/web/OrderStatusAuditTest.java
git commit -m "feat(sales): audit trail for order status transitions

One generic OrderStatusChangedEvent covers all three transitions, so the
number of event types stays fixed as statuses are added. A synchronous
@EventListener in the publisher's transaction writes ORDER_DISPATCHED /
ORDER_CLOSED / ORDER_CANCELLED rows, with the previous status and (on
cancellation only) the reason in the detail map.

The row commits or rolls back with the status change, matching
OrderAcceptedAuditListener. A rejected transition throws before the publish
call, so it leaves no event and no audit row."
```

---

## Task 4: Fix the list filter composition

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/OrderSpecifications.java`
- Modify: `backend/src/main/java/com/easycrm/sales/OrderRepository.java`
- Modify: `backend/src/main/java/com/easycrm/sales/OrderService.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/OrderListTest.java`

**Interfaces:**
- Consumes: `OrderService.dispatch(UUID)` from Task 2 (to produce a non-`CONFIRMED` order in the fixture).
- Produces: `OrderSpecifications.filter(OrderStatus status, UUID customerId)` returning `Specification<Order>`.

**The bug:** `OrderService.list` currently reads `if (status != null) … else if (customerId != null) … else findAll`. Supplying both silently drops `customerId`. The enquiry slice hit the same shape and solved it with a `Specification`; challenge #24 logged the lesson but orders never got the fix.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/sales/web/OrderListTest.java`:

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
class OrderListTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String createCustomer(String auth, String name) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(
                    "{\"businessName\":\"%s\",\"stateCode\":\"27\",\"source\":\"MANUAL\"}"
                        .formatted(name)))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String createProduct(String auth) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"sku":"SKU-%s","name":"W","hsnCode":"84818090","uom":"PCS","gstRate":"18","baseRate":"100.00"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 8))))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** One quotation per order — sales_order has UNIQUE(tenant_id, quotation_id). */
    private String createOrderFor(String auth, String customerId, String productId) throws Exception {
        String qId = JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(
                    "{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"1\"}]}"
                        .formatted(customerId, productId)))
            .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth))
            .andExpect(status().isOk());
        return JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/accept")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void twoFiltersCombineInsteadOfDroppingOne() throws Exception {
        // Regression guard for challenge #24: the old if/else if dropped customerId
        // whenever status was also supplied.
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String p = createProduct(auth);
        String c1 = createCustomer(auth, "Alpha");
        String c2 = createCustomer(auth, "Beta");

        String a = createOrderFor(auth, c1, p);            // c1, CONFIRMED  <- the only match
        createOrderFor(auth, c2, p);                       // c2, CONFIRMED
        String c = createOrderFor(auth, c1, p);            // c1, DISPATCHED
        mvc.perform(post("/api/v1/orders/" + c + "/dispatch").header("Authorization", auth))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/orders").header("Authorization", auth)
                .param("status", "CONFIRMED").param("customerId", c1))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(a));
    }

    @Test
    void eachFilterWorksOnItsOwn() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String p = createProduct(auth);
        String c1 = createCustomer(auth, "Alpha");
        String c2 = createCustomer(auth, "Beta");

        createOrderFor(auth, c1, p);
        String b = createOrderFor(auth, c2, p);
        String c = createOrderFor(auth, c1, p);
        mvc.perform(post("/api/v1/orders/" + c + "/dispatch").header("Authorization", auth))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/orders").header("Authorization", auth)
                .param("status", "DISPATCHED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(c));

        mvc.perform(get("/api/v1/orders").header("Authorization", auth)
                .param("customerId", c2))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(b));
    }

    @Test
    void noFiltersReturnsEveryOrderInTheTenant() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String p = createProduct(auth);
        String c1 = createCustomer(auth, "Alpha");
        createOrderFor(auth, c1, p);
        createOrderFor(auth, c1, p);

        mvc.perform(get("/api/v1/orders").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.sales.web.OrderListTest"`
Expected: **FAIL** on `twoFiltersCombineInsteadOfDroppingOne` — `totalElements` is **2**, not 1, because the old code answers `findByStatus(CONFIRMED)` and ignores the customer. The other two tests pass already; that's expected, they guard behaviour the fix must not break.

- [ ] **Step 3: Add the specification**

Create `backend/src/main/java/com/easycrm/sales/OrderSpecifications.java`:

```java
package com.easycrm.sales;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class OrderSpecifications {

    private OrderSpecifications() {}

    /** AND-composes whichever filters are non-null. Tenant scoping comes from RLS, not here. */
    public static Specification<Order> filter(OrderStatus status, UUID customerId) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (status != null)     ps.add(cb.equal(root.get("status"), status));
            if (customerId != null) ps.add(cb.equal(root.get("customerId"), customerId));
            return cb.and(ps.toArray(new Predicate[0])); // empty -> always-true conjunction
        };
    }
}
```

- [ ] **Step 4: Point the repository at specifications**

Replace the whole of `backend/src/main/java/com/easycrm/sales/OrderRepository.java`:

```java
package com.easycrm.sales;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository
        extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    // Used by the idempotent accept path; the list endpoint filters via OrderSpecifications.
    Optional<Order> findByQuotationId(UUID quotationId);
}
```

`findByStatus` and `findByCustomerId` are deleted — `OrderService.list` was their only caller.

- [ ] **Step 5: Rewrite `list`**

In `backend/src/main/java/com/easycrm/sales/OrderService.java`, replace the `list` method with:

```java
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> list(OrderStatus status, UUID customerId, Pageable pageable) {
        return PageResponse.of(
            orders.findAll(OrderSpecifications.filter(status, customerId), pageable)
                .map(OrderResponse::of));
    }
```

Then delete the now-unused `import org.springframework.data.domain.Page;` from the top of the file — the build treats unused imports as warnings, but leaving it is dead code.

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.sales.web.OrderListTest"`
Expected: **PASS**, 3 tests.

- [ ] **Step 7: Run the full suite**

Run: `cd backend && ./gradlew test`
Expected: **PASS**, 184 tests. `OrderReadTest` exercises the no-filter path and must still pass.

- [ ] **Step 8: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/main/java/com/easycrm/sales/OrderSpecifications.java \
        backend/src/main/java/com/easycrm/sales/OrderRepository.java \
        backend/src/main/java/com/easycrm/sales/OrderService.java \
        backend/src/test/java/com/easycrm/sales/web/OrderListTest.java
git commit -m "fix(sales): order list drops a filter when two are supplied

OrderService.list picked exactly one derived finder with if/else if, so
?status=&customerId= together silently ignored the customer. Replaced with
an AND-composing OrderSpecifications.filter, the same shape the enquiry
slice used to avoid this (challenge #24); findByStatus and findByCustomerId
are gone, findByQuotationId stays for the accept path.

Now meaningful with four statuses rather than one."
```

---

## Task 5: Accepting a quotation whose order was cancelled

**Files:**
- Modify: `backend/src/main/java/com/easycrm/sales/QuotationService.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/QuotationAcceptTest.java`

**Interfaces:**
- Consumes: `OrderStatus.CANCELLED` from Task 1; the `/orders/{id}/cancel` endpoint from Task 2; the existing private `QuotationAcceptTest.createSent(String auth)` helper, which builds a customer + product + quotation, sends it, and returns the quotation id.
- Produces: no new API surface — only a changed status code on an existing endpoint.

**Why:** `accept` is idempotent — re-accepting an `ACCEPTED` quotation returns the order that already exists. Once an order can be cancelled, that contract quietly breaks: the caller gets a 200 describing a dead order. `UNIQUE(tenant_id, quotation_id)` on `sales_order` rules out simply making another one, so the honest answer is an explicit dead end.

- [ ] **Step 1: Write the failing test**

Append this method to `backend/src/test/java/com/easycrm/sales/web/QuotationAcceptTest.java` (the class already imports everything it needs):

```java
    @Test
    void acceptingAQuotationWhoseOrderWasCancelledIs422() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String qId = createSent(auth);

        String orderId = JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/accept")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(post("/api/v1/orders/" + orderId + "/cancel").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"cancelReason":"customer withdrew PO"}"""))
            .andExpect(status().isOk());

        // No live order to hand back, and the unique constraint rules out making another.
        mvc.perform(post("/api/v1/quotations/" + qId + "/accept").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }
```

Note the JSON path: `ApiExceptionHandler.body` wraps everything under an `error` object, so the assertion is `$.error.code`, not `$.code`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.sales.web.QuotationAcceptTest"`
Expected: **FAIL** — `Status expected:<422> but was:<200>`. The idempotent branch currently returns the cancelled order.

- [ ] **Step 3: Guard the idempotent branch**

In `backend/src/main/java/com/easycrm/sales/QuotationService.java`, replace the opening of the `accept` method's idempotent branch:

```java
        if (q.getStatus() == QuotationStatus.ACCEPTED) {
            // Idempotent: return the order already created for this quotation.
            return OrderResponse.of(orders.findByQuotationId(q.getId())
                .orElseThrow(() -> new NotFoundException("order not found")));
        }
```

with:

```java
        if (q.getStatus() == QuotationStatus.ACCEPTED) {
            // Idempotent: return the order already created for this quotation — unless it
            // was cancelled, in which case there is no live order to hand back. Reopening
            // means a new quotation: UNIQUE(tenant_id, quotation_id) on sales_order makes
            // one-order-per-quotation structural, so a second order here is impossible.
            Order existing = orders.findByQuotationId(q.getId())
                .orElseThrow(() -> new NotFoundException("order not found"));
            if (existing.getStatus() == OrderStatus.CANCELLED) {
                throw new ValidationException("status",
                    "the order for this quotation was cancelled; raise a new quotation");
            }
            return OrderResponse.of(existing);
        }
```

`Order`, `OrderStatus` and `ValidationException` are all already visible in this file (same package / existing import), so no import changes are needed.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.sales.web.QuotationAcceptTest"`
Expected: **PASS**, including the pre-existing cases — a non-cancelled re-accept must still return 200 with the same order.

- [ ] **Step 5: Run the full suite**

Run: `cd backend && ./gradlew clean test`
Expected: **PASS**, 185 tests, from a clean build.

- [ ] **Step 6: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/main/java/com/easycrm/sales/QuotationService.java \
        backend/src/test/java/com/easycrm/sales/web/QuotationAcceptTest.java
git commit -m "fix(sales): 422 when accepting a quotation whose order was cancelled

Accept is idempotent — it returns the order already created for an ACCEPTED
quotation. Once orders can be cancelled that quietly returns a dead order
with a 200. The idempotent branch now checks for CANCELLED and returns 422
telling the caller to raise a new quotation.

Cancellation stays order-local: the quotation stays ACCEPTED, the cancelled
row stays put, and UNIQUE(tenant_id, quotation_id) is untouched."
```

---

## Task 6: Documentation wrap-up

**Files:**
- Modify: `docs/superpowers/engineering-challenges.md`
- Modify: `docs/superpowers/annotations-reference.md` (only if something is genuinely new)
- Modify: `docs/superpowers/HANDOFF.md`

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces: nothing code-facing.

This task is mandatory, not optional — `CLAUDE.md` requires the challenge log and annotations reference to stay current *in the same change* as the work that motivated them.

- [ ] **Step 1: Append challenge #27**

Append to `docs/superpowers/engineering-challenges.md`, above the `<!-- Append new challenges below. Template: -->` comment, following the existing heading style exactly:

```markdown
## Challenge 27 — A terminal order state versus an idempotent accept

**Phase:** Design

### The problem

Adding `CANCELLED` to `Order` collides with two decisions already baked into the
accept path: accept is **idempotent** — re-accepting an `ACCEPTED` quotation
returns the order that already exists — and `UNIQUE(tenant_id, quotation_id)` on
`sales_order` makes one-order-per-quotation **structural** (challenge #21).

Cancellation therefore has no obvious undo. The naive move — flip the quotation
back to `SENT` so it can be accepted again — cannot work: the cancelled row still
occupies the unique slot, so the second accept's `INSERT` hits the constraint and
surfaces as a 409 no caller can act on. But leaving accept untouched is quietly
worse. It keeps returning **200 with a dead order**, so a client that reasonably
reads accept as "give me the live order for this quote" gets a plausible-looking
response describing an order that no longer exists commercially. Nothing fails
loudly; the contract just stops being true.

### The solution

Keep cancellation order-local and make the dead end explicit. The quotation stays
`ACCEPTED`, the cancelled row stays put, the unique constraint is untouched — and
accept's idempotent branch gains one check: if the existing order is `CANCELLED`,
throw `ValidationException` → **422** with "the order for this quotation was
cancelled; raise a new quotation" instead of returning it.

Reopening a cancelled sale means raising a new quotation, which is also the
commercially honest answer: after a cancellation, price, stock and terms have all
had a chance to move, so silently reviving the old accepted version would be the
wrong default even if the schema allowed it.

### Lesson

Idempotency is a claim about a *result*, not about a status code. "Call it again,
get the same answer" holds only while the resource the call produced is still
valid — and introducing a terminal state downstream breaks that invisibly,
because the endpoint carries on returning 200. When you add a terminal state to
an aggregate, re-read every idempotent path that hands that aggregate back and
decide explicitly what each one now means.

And when a structural constraint removes the option of "just make another one",
that is the constraint doing its job. The fix is to say no clearly, not to relax
the constraint.
```

- [ ] **Step 2: Check the annotations reference**

Open `docs/superpowers/annotations-reference.md` and confirm every annotation this slice used already has a row: `@Column`, `@Enumerated`, `@Entity`, `@Table`, `@Transactional`, `@Service`, `@Component`, `@RestController`, `@PostMapping`, `@PathVariable`, `@RequestBody`, `@Valid`, `@NotBlank`, `@EventListener`, `@Autowired`, `@Test`, `@AfterEach`, `@SpringBootTest`, `@AutoConfigureMockMvc`.

All of these were introduced by earlier slices, so the expected outcome is **no change**. Add a row only for an annotation that is genuinely absent — do not pad the file. If nothing is missing, leave the file untouched and say so in the commit body.

`JpaSpecificationExecutor` and `Specification` are types, not annotations; the reference already notes them as concepts from the enquiry slice.

- [ ] **Step 3: Update the handoff**

In `docs/superpowers/HANDOFF.md`:

1. **Header line** — replace the "Last updated" line with `2026-07-28` and the order-lifecycle merge commit (fill in the real hash after merging).
2. **§2 "Read these, in order"** — add the two new documents as items 19 and 20: `specs/2026-07-28-order-lifecycle-design.md` and `plans/2026-07-28-order-lifecycle.md`, both marked **DONE, merged**.
3. **§3 "Current state"** — add an "order lifecycle" bullet at the top of the merged list: the four-state guarded machine with entity-side guards, the required `cancelReason` (`V23`), the generic `OrderStatusChangedEvent` + audit listener, the `OrderSpecifications` filter fix, and the cancelled-order 422 on accept. Update the test count to **185** and note challenge #27.
4. **§4 "THE NEXT TASK"** — replace the sales-hardening paragraph with the order-lifecycle summary, and strike these entries from the deferred list, marking each **DONE**: "Order status transitions beyond `CONFIRMED`".
5. **§8 "START HERE NEXT SESSION"** — delete item 1 (order status transitions) and its "deferred order-list filter backlog" clause, renumber the remaining five, and update the "Suggested default" line — with #1 gone, **#2 (PDF + `wa.me` share)** becomes the recommended default as the highest-product-value chunk and the trigger for the challenge #22 outbox migration.
6. **Smaller deferred-Minor backlog** (bottom of §8) — add two entries:
   - `OrderSpecifications`, like `EnquirySpecifications`, uses string-keyed `root.get(...)` rather than a JPA static metamodel.
   - **`QuotationService.list` still has the same dropped-filter bug** this slice fixed for orders (`QuotationService.java:94-95` — `if (status != null) … else if (customerId != null) …`, so `?status=&customerId=` together ignores the customer). Found while fixing the order list; deliberately left out of this slice because the spec scoped the fix to orders. The fix is mechanical: a `QuotationSpecifications.filter(QuotationStatus, UUID)` mirroring `OrderSpecifications`, plus `JpaSpecificationExecutor<Quotation>` on the repository and a two-filter regression test.

Also update the **Status** line at the top of `docs/superpowers/specs/2026-07-28-order-lifecycle-design.md` from `approved, ready for implementation planning` to `implemented, merged`.

- [ ] **Step 4: Verify the docs build cleanly**

Run: `cd /Users/divyam/Documents/easy-crm && git diff --stat`
Expected: changes limited to the three docs files (two if the annotations reference needed nothing). No code files in the diff.

- [ ] **Step 5: Run the full suite one last time**

Run: `cd backend && ./gradlew clean test`
Expected: **PASS**, 185 tests, from a clean build. Record the real number — do not claim it without seeing it.

- [ ] **Step 6: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add docs/superpowers/engineering-challenges.md \
        docs/superpowers/annotations-reference.md \
        docs/superpowers/HANDOFF.md \
        docs/superpowers/specs/2026-07-28-order-lifecycle-design.md
git commit -m "docs(sales): log challenge #27 and close out the order-lifecycle slice

Challenge #27 covers the collision between a new terminal order state and
the idempotent accept path: re-accepting kept returning 200 with a dead
order, and the naive fix (reopen the quotation) is ruled out by
UNIQUE(tenant_id, quotation_id).

Handoff updated to 185 tests, the next-chunk menu drops the completed order
status transitions item, and the design spec is marked implemented."
```

---

## Verification checklist

Before declaring the slice done, confirm each of these by *running* it, not by reasoning about it:

- [ ] `cd backend && ./gradlew clean test` → **187 tests, all green** from a clean build (185 after Task 6; the final whole-branch review added two more).
- [ ] `git log --oneline main..HEAD` → nine commits: the six task commits, two mid-flight documentation corrections, and one final-review fix wave. Legitimate
      mid-flight documentation corrections (`2d4a81e`, fixing a wrong JSON path in this plan's own
      test code; `6b9accd`, correcting a handoff claim) — none mentioning Claude or AI.
- [ ] `git diff main --stat` → only the files listed in **File Structure** above.
- [ ] Challenge #27 is in `engineering-challenges.md`.
- [ ] The handoff's test count, merged list, and §8 menu all reflect this slice.

Then use `superpowers:finishing-a-development-branch` to integrate.
