# EasyCRM P1a — Master Data (Catalog + CRM) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the tenant-scoped master data (products, customers, contacts, price lists) with REST CRUD that the P1b quotation engine will read.

**Architecture:** Five `TenantScopedEntity` entities across two modules (`catalog`, `crm`), each with a Flyway migration + RLS policy, a Spring Data repository, a `@Transactional` service (the JWT filter has already set `TenantContext`, so services mirror the simple `AuthService.me()` shape — no pre-context dance), and a hand-mapped REST controller. A shared `platform.gst.Gstin` value type validates the GSTIN checksum and derives `state_code`; a new `platform.error.ValidationException` carries field-level 422 errors.

**Tech Stack:** Spring Boot 4.1, Java 25, Hibernate 7, PostgreSQL + RLS, Flyway, Testcontainers, JUnit 5, MockMvc + jayway JsonPath, jakarta bean-validation.

## Global Constraints

- **Commits:** author `divyam <divyam.0444@gmail.com>` (plain `git commit`, repo config already set). NO `Co-Authored-By: Claude` trailer; NO mention of Claude/AI anywhere. One task per commit.
- **Money/rates are `BigDecimal` / `NUMERIC`, never `double`.** `NUMERIC(18,2)` amounts, `NUMERIC(18,4)` rates. Compare `BigDecimal` with `compareTo`, never `equals` (scale-sensitive).
- **Tenant isolation is structural:** every new entity extends `TenantScopedEntity` (ArchUnit `TenantScopingArchTest` enforces this automatically — no `GLOBAL_TABLES` entry). Never hand-write `WHERE tenant_id`. RLS-scoped reads run in a tenant-bound transaction — derived repository finders are annotated `@Transactional(readOnly = true)` (challenge #8), or they silently return zero rows.
- **`ddl-auto: validate` is ON:** migration column types/precision/length must match entity `@Column` mappings exactly (`VARCHAR` for `String`, `NUMERIC(p,s)` for `BigDecimal`, `BOOLEAN` for `boolean`).
- **FK columns are bare `UUID`s, no DB foreign-key constraints** (matches `app_user`/`audit_log`). Same-tenant integrity is structural via RLS.
- **Migrations:** next number is **V9**; continue sequentially. Flyway runs as the owner role; `ALTER DEFAULT PRIVILEGES` (V1) auto-grants DML to `easycrm_app`, so no per-migration `GRANT` is needed. Every table gets `ENABLE ROW LEVEL SECURITY` + a `tenant_isolation` policy using `NULLIF(current_setting('app.current_tenant', true), '')::uuid` (challenge #6).
- **Errors:** structural `@Valid` failure → 400 (existing handler); semantic value error (bad GSTIN checksum, disallowed `gst_rate`, override/discount both set) → **422** via `ValidationException`; duplicate key → 409 `ConflictException`; cross-tenant/missing → 404 `NotFoundException`.
- **DTO mapping is hand-written** (small private `toResponse(...)` methods); **no MapStruct**.

### Deferred to P1b (explicit follow-ups, do NOT build here)
- **Money-as-JSON-string wire format** (challenge #2: Jackson `WRITE_BIGDECIMAL_AS_PLAIN` + serialize `BigDecimal` as string). P1a is the first code to put a `BigDecimal` on the wire; its rates serialize as JSON numbers for now. P1b must add the global Jackson-3/Boot-4 customizer before the quotation wire contract and frontend money handling ship.
- **Price resolution** (customer + product → effective rate). Entities are stored here; the resolver is built in P1b with its consumer.
- **Record-level visibility filtering** (`assigned_to`). Stored as a column now; not used to filter reads.
- **Cursor pagination.** P1a lists use offset-based `Pageable`.

---

## File Structure

```
platform/error/ValidationException.java        (new)  422 field-error carrier
platform/error/ApiExceptionHandler.java        (edit) + @ExceptionHandler(ValidationException)
platform/gst/Gstin.java                         (new)  GSTIN parse + checksum + stateCode()
platform/gst/StateCode.java                     (new)  valid GST state-code set
platform/web/PageResponse.java                  (new)  list-response record (content + page meta)

catalog/Uom.java                                (new)  enum
catalog/Product.java  ProductRepository  ProductService
catalog/web/ProductController.java  web/dto/{ProductCreateRequest,ProductUpdateRequest,ProductResponse}
catalog/PriceList.java  PriceListRepository  PriceListService
catalog/web/PriceListController.java  web/dto/{PriceListRequest,PriceListResponse}
catalog/PriceListItem.java  PriceListItemRepository  PriceListItemService
catalog/web/PriceListItemController.java  web/dto/{PriceListItemRequest,PriceListItemResponse}

crm/CustomerSource.java                          (new)  enum
crm/Customer.java  CustomerRepository  CustomerService
crm/web/CustomerController.java  web/dto/{CustomerRequest,CustomerResponse}
crm/Contact.java  ContactRepository  ContactService
crm/web/ContactController.java  web/dto/{ContactRequest,ContactResponse}

resources/db/migration/V9__product.sql … V13__price_list_item.sql
```

---

## Task 1: `ValidationException` + 422 handler

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/error/ValidationException.java`
- Modify: `backend/src/main/java/com/easycrm/platform/error/ApiExceptionHandler.java`
- Test: `backend/src/test/java/com/easycrm/platform/error/ApiExceptionHandlerTest.java`

**Interfaces:**
- Produces: `ValidationException(Map<String,String> fields)`, `ValidationException(String field, String message)`, `Map<String,String> getFields()`. Mapped to HTTP 422, code `VALIDATION_FAILED`, body `{ "error": { "code", "message", "fields": {..} } }`.

- [ ] **Step 1: Write the failing test**

```java
package com.easycrm.platform.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @SuppressWarnings("unchecked")
    void validationExceptionMapsTo422WithFields() {
        ResponseEntity<Map<String, Object>> resp =
            handler.validation(new ValidationException("gstin", "GSTIN checksum is invalid"));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode());
        Map<String, Object> error = (Map<String, Object>) resp.getBody().get("error");
        assertEquals("VALIDATION_FAILED", error.get("code"));
        Map<String, Object> fields = (Map<String, Object>) error.get("fields");
        assertEquals("GSTIN checksum is invalid", fields.get("gstin"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.platform.error.ApiExceptionHandlerTest"`
Expected: FAIL — `ValidationException` and `handler.validation` do not exist (compile error).

- [ ] **Step 3: Write minimal implementation**

`ValidationException.java`:
```java
package com.easycrm.platform.error;

import java.util.Map;

/** Field-level domain validation failure. Mapped to HTTP 422 by ApiExceptionHandler. */
public class ValidationException extends RuntimeException {

    private final Map<String, String> fields;

    public ValidationException(Map<String, String> fields) {
        super("validation failed");
        this.fields = fields;
    }

    public ValidationException(String field, String message) {
        this(Map.of(field, message));
    }

    public Map<String, String> getFields() { return fields; }
}
```

Add to `ApiExceptionHandler` (new import `import java.util.Map;` already present):
```java
@ExceptionHandler(ValidationException.class)
public ResponseEntity<Map<String, Object>> validation(ValidationException ex) {
    Map<String, Object> fields = new HashMap<>(ex.getFields());
    return body(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "request is invalid", fields);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.platform.error.ApiExceptionHandlerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/platform/error src/test/java/com/easycrm/platform/error
git commit -m "feat: ValidationException mapped to HTTP 422 with field errors"
```

---

## Task 2: `Gstin` value type + `StateCode`

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/gst/Gstin.java`
- Create: `backend/src/main/java/com/easycrm/platform/gst/StateCode.java`
- Test: `backend/src/test/java/com/easycrm/platform/gst/GstinTest.java`

**Interfaces:**
- Produces: `Gstin.parse(String) -> Gstin` (throws `ValidationException` on bad length/chars/checksum), `Gstin.value() -> String`, `Gstin.stateCode() -> String`. `StateCode.isValid(String) -> boolean`, `StateCode.requireValid(String)` (throws `ValidationException`).
- Consumes: `ValidationException` (Task 1).

- [ ] **Step 1: Write the failing test**

`27AAPFU0939F1ZV` is a valid GSTIN (verified check digit `V`, state `27`). `27AAPFU0939F1ZZ` has a wrong check digit.

```java
package com.easycrm.platform.gst;

import com.easycrm.platform.error.ValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GstinTest {

    @Test
    void parsesValidGstinAndExtractsStateCode() {
        Gstin g = Gstin.parse("27AAPFU0939F1ZV");
        assertEquals("27AAPFU0939F1ZV", g.value());
        assertEquals("27", g.stateCode());
    }

    @Test
    void trimsAndUppercases() {
        assertEquals("27AAPFU0939F1ZV", Gstin.parse(" 27aapfu0939f1zv ").value());
    }

    @Test
    void rejectsBadChecksum() {
        ValidationException ex = assertThrows(ValidationException.class,
            () -> Gstin.parse("27AAPFU0939F1ZZ"));
        assertTrue(ex.getFields().containsKey("gstin"));
    }

    @Test
    void rejectsWrongLength() {
        assertThrows(ValidationException.class, () -> Gstin.parse("27AAPFU0939F1Z"));
    }

    @Test
    void stateCodeValidation() {
        assertTrue(StateCode.isValid("27"));
        assertFalse(StateCode.isValid("00"));
        assertFalse(StateCode.isValid("2"));
        assertThrows(ValidationException.class, () -> StateCode.requireValid("88"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.platform.gst.GstinTest"`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Write minimal implementation**

`Gstin.java`:
```java
package com.easycrm.platform.gst;

import com.easycrm.platform.error.ValidationException;

/**
 * A validated GSTIN (15 chars). The 15th char is a base-36 check digit computed from the
 * first 14 via a Luhn-mod-36 algorithm (GSTN spec). First two chars are the GST state code.
 */
public final class Gstin {

    private static final String CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int CP = 36;

    private final String value;

    private Gstin(String value) { this.value = value; }

    public static Gstin parse(String raw) {
        if (raw == null) throw new ValidationException("gstin", "GSTIN is required");
        String g = raw.trim().toUpperCase();
        if (g.length() != 15) throw new ValidationException("gstin", "GSTIN must be 15 characters");
        for (int i = 0; i < 15; i++) {
            if (CHARSET.indexOf(g.charAt(i)) < 0)
                throw new ValidationException("gstin", "GSTIN has invalid characters");
        }
        if (checkChar(g.substring(0, 14)) != g.charAt(14))
            throw new ValidationException("gstin", "GSTIN checksum is invalid");
        return new Gstin(g);
    }

    private static char checkChar(String payload14) {
        int factor = 2, sum = 0;
        for (int i = payload14.length() - 1; i >= 0; i--) {
            int cp = CHARSET.indexOf(payload14.charAt(i));
            int d = factor * cp;
            d = (d / CP) + (d % CP);
            sum += d;
            factor = (factor == 2) ? 1 : 2;
        }
        return CHARSET.charAt((CP - (sum % CP)) % CP);
    }

    public String value() { return value; }
    public String stateCode() { return value.substring(0, 2); }
}
```

`StateCode.java`:
```java
package com.easycrm.platform.gst;

import com.easycrm.platform.error.ValidationException;

import java.util.HashSet;
import java.util.Set;

/** Valid GST state codes: 01–38, plus 97 (Other Territory) and 99 (Centre Jurisdiction). */
public final class StateCode {

    private static final Set<String> VALID;
    static {
        Set<String> v = new HashSet<>();
        for (int i = 1; i <= 38; i++) v.add(String.format("%02d", i));
        v.add("97");
        v.add("99");
        VALID = Set.copyOf(v);
    }

    private StateCode() {}

    public static boolean isValid(String code) {
        return code != null && VALID.contains(code);
    }

    public static void requireValid(String code) {
        if (!isValid(code)) throw new ValidationException("stateCode", "invalid GST state code");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.platform.gst.GstinTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/platform/gst src/test/java/com/easycrm/platform/gst
git commit -m "feat: Gstin value type with checksum validation and state-code extraction"
```

---

## Task 3: `PageResponse` list helper

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/web/PageResponse.java`
- Test: `backend/src/test/java/com/easycrm/platform/web/PageResponseTest.java`

**Interfaces:**
- Produces: `PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages)`; static `PageResponse.of(Page<T>) -> PageResponse<T>`. Consumed by every list endpoint.

- [ ] **Step 1: Write the failing test**

```java
package com.easycrm.platform.web;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageResponseTest {

    @Test
    void mapsSpringPageMetadata() {
        PageResponse<String> r = PageResponse.of(
            new PageImpl<>(List.of("a", "b"), PageRequest.of(0, 20), 2));
        assertEquals(List.of("a", "b"), r.content());
        assertEquals(0, r.page());
        assertEquals(20, r.size());
        assertEquals(2, r.totalElements());
        assertEquals(1, r.totalPages());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.platform.web.PageResponseTest"`
Expected: FAIL — `PageResponse` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.easycrm.platform.web;

import org.springframework.data.domain.Page;

import java.util.List;

/** Stable list-response envelope (avoids serializing Spring's PageImpl directly). */
public record PageResponse<T>(List<T> content, int page, int size,
                              long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> p) {
        return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(),
                                  p.getTotalElements(), p.getTotalPages());
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.platform.web.PageResponseTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/platform/web src/test/java/com/easycrm/platform/web
git commit -m "feat: PageResponse envelope for paginated list endpoints"
```

---

## Task 4: `Product` entity + migration + repository

**Files:**
- Create: `backend/src/main/java/com/easycrm/catalog/Uom.java`
- Create: `backend/src/main/java/com/easycrm/catalog/Product.java`
- Create: `backend/src/main/java/com/easycrm/catalog/ProductRepository.java`
- Create: `backend/src/main/resources/db/migration/V9__product.sql`
- Test: `backend/src/test/java/com/easycrm/catalog/ProductRepositoryTest.java`

**Interfaces:**
- Produces: `Product(String sku, String name, String hsnCode, Uom uom, BigDecimal gstRate, BigDecimal baseRate)`; `update(String name, String hsnCode, Uom uom, BigDecimal gstRate, BigDecimal baseRate)`; `activate()`; `deactivate()`; getters `getSku/getName/getHsnCode/getUom/getGstRate/getBaseRate/isActive`. `ProductRepository extends JpaRepository<Product, UUID>` with `Optional<Product> findBySku(String)` and `Page<Product> findByActive(boolean, Pageable)`.
- Consumes: `TenantScopedEntity`, `IntegrationTest`, `TenantContext`.

- [ ] **Step 1: Write the failing test**

```java
package com.easycrm.catalog;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProductRepositoryTest extends IntegrationTest {
    @Autowired ProductRepository products;

    @AfterEach void clear() { TenantContext.clear(); }

    private void asTenant(UUID t) {
        TenantContext.set(new TenantContext.TenantPrincipal(t, UUID.randomUUID(), "OWNER"));
    }

    private Product sample(String sku) {
        return new Product(sku, "Widget", "84818090", Uom.PCS,
                           new BigDecimal("18.0000"), new BigDecimal("100.00"));
    }

    @Test
    void savesAndFindsBySkuWithinTenant() {
        asTenant(UUID.randomUUID());
        products.save(sample("SKU-1"));
        assertTrue(products.findBySku("SKU-1").isPresent());
    }

    @Test
    void findBySkuIsTenantScoped() {
        asTenant(UUID.randomUUID());
        products.save(sample("SKU-DUP"));
        asTenant(UUID.randomUUID()); // different tenant
        assertTrue(products.findBySku("SKU-DUP").isEmpty(), "sku lookup must not cross tenants");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.catalog.ProductRepositoryTest"`
Expected: FAIL — classes/table do not exist.

- [ ] **Step 3: Write minimal implementation**

`Uom.java`:
```java
package com.easycrm.catalog;

public enum Uom { PCS, KG, NOS, MTR, LTR, BOX, SET, DOZEN, PACK, BAG, ROLL, PAIR }
```

`Product.java`:
```java
package com.easycrm.catalog;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;

@Entity
@Table(name = "product",
       uniqueConstraints = @UniqueConstraint(name = "uq_product_tenant_sku",
                                             columnNames = {"tenant_id", "sku"}))
public class Product extends TenantScopedEntity {

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(name = "hsn_code", length = 8)
    private String hsnCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Uom uom;

    @Column(name = "gst_rate", nullable = false, precision = 18, scale = 4)
    private BigDecimal gstRate;

    @Column(name = "base_rate", nullable = false, precision = 18, scale = 2)
    private BigDecimal baseRate;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected Product() {}

    public Product(String sku, String name, String hsnCode, Uom uom,
                   BigDecimal gstRate, BigDecimal baseRate) {
        this.sku = sku;
        this.name = name;
        this.hsnCode = hsnCode;
        this.uom = uom;
        this.gstRate = gstRate;
        this.baseRate = baseRate;
        this.active = true;
    }

    public void update(String name, String hsnCode, Uom uom, BigDecimal gstRate, BigDecimal baseRate) {
        this.name = name;
        this.hsnCode = hsnCode;
        this.uom = uom;
        this.gstRate = gstRate;
        this.baseRate = baseRate;
    }

    public void activate() { this.active = true; }
    public void deactivate() { this.active = false; }

    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getHsnCode() { return hsnCode; }
    public Uom getUom() { return uom; }
    public BigDecimal getGstRate() { return gstRate; }
    public BigDecimal getBaseRate() { return baseRate; }
    public boolean isActive() { return active; }
}
```

`ProductRepository.java`:
```java
package com.easycrm.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    // @Transactional(readOnly = true): derived finders are not tx-wrapped by Spring Data,
    // so without this the RLS tenant GUC is unset and the query returns zero rows (challenge #8).
    @Transactional(readOnly = true)
    Optional<Product> findBySku(String sku);

    @Transactional(readOnly = true)
    Page<Product> findByActive(boolean active, Pageable pageable);
}
```

`V9__product.sql`:
```sql
CREATE TABLE product (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    sku        VARCHAR(64) NOT NULL,
    name       VARCHAR(255) NOT NULL,
    hsn_code   VARCHAR(8),
    uom        VARCHAR(16) NOT NULL,
    gst_rate   NUMERIC(18,4) NOT NULL,
    base_rate  NUMERIC(18,2) NOT NULL,
    is_active  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    version    BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_product_tenant_sku UNIQUE (tenant_id, sku)
);
CREATE INDEX idx_product_tenant ON product (tenant_id, id);

ALTER TABLE product ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON product
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.catalog.ProductRepositoryTest"`
Expected: PASS (2 tests). ArchUnit `TenantScopingArchTest` also still passes (Product extends TenantScopedEntity).

- [ ] **Step 5: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/catalog src/main/resources/db/migration/V9__product.sql src/test/java/com/easycrm/catalog
git commit -m "feat: Product entity, migration with RLS, and tenant-scoped repository"
```

---

## Task 5: `Product` service + controller (CRUD)

**Files:**
- Create: `backend/src/main/java/com/easycrm/catalog/ProductService.java`
- Create: `backend/src/main/java/com/easycrm/catalog/web/ProductController.java`
- Create: `backend/src/main/java/com/easycrm/catalog/web/dto/ProductCreateRequest.java`
- Create: `backend/src/main/java/com/easycrm/catalog/web/dto/ProductUpdateRequest.java`
- Create: `backend/src/main/java/com/easycrm/catalog/web/dto/ProductResponse.java`
- Test: `backend/src/test/java/com/easycrm/catalog/web/ProductControllerTest.java`

**Interfaces:**
- Consumes: `ProductRepository`, `Product`, `Uom`, `PageResponse`, `ValidationException`, `ConflictException`, `NotFoundException`, `TestTokens`.
- Produces: `ProductService.create/get/list/update/deactivate/activate`; REST `POST/GET/PUT /api/v1/products`, `POST /api/v1/products/{id}/deactivate|activate`.

- [ ] **Step 1: Write the failing test**

```java
package com.easycrm.catalog.web;

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
class ProductControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void createThenGet() throws Exception {
        UUID tenant = UUID.randomUUID();
        String auth = "Bearer " + tokens.owner(tenant);
        String create = """
            {"sku":"SKU-9","name":"Bolt","hsnCode":"7318","uom":"PCS",
             "gstRate":"18","baseRate":"12.50"}""";

        String body = mvc.perform(post("/api/v1/products")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sku").value("SKU-9"))
            .andExpect(jsonPath("$.active").value(true))
            .andReturn().getResponse().getContentAsString();

        String id = JsonPath.read(body, "$.id");
        mvc.perform(get("/api/v1/products/" + id).header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Bolt"));
    }

    @Test
    void rejectsDisallowedGstRateWith422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String create = """
            {"sku":"SKU-BAD","name":"X","hsnCode":"7318","uom":"PCS",
             "gstRate":"7","baseRate":"1.00"}""";
        mvc.perform(post("/api/v1/products")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.fields.gstRate").exists());
    }

    @Test
    void duplicateSkuReturns409() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String create = """
            {"sku":"SKU-DUP2","name":"X","hsnCode":"7318","uom":"PCS",
             "gstRate":"18","baseRate":"1.00"}""";
        mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isConflict());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.catalog.web.ProductControllerTest"`
Expected: FAIL — service/controller/DTOs do not exist.

- [ ] **Step 3: Write minimal implementation**

`ProductCreateRequest.java`:
```java
package com.easycrm.catalog.web.dto;

import com.easycrm.catalog.Uom;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ProductCreateRequest(
    @NotBlank String sku,
    @NotBlank String name,
    String hsnCode,
    @NotNull Uom uom,
    @NotNull BigDecimal gstRate,
    @NotNull BigDecimal baseRate) {}
```

`ProductUpdateRequest.java`:
```java
package com.easycrm.catalog.web.dto;

import com.easycrm.catalog.Uom;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ProductUpdateRequest(
    @NotBlank String name,
    String hsnCode,
    @NotNull Uom uom,
    @NotNull BigDecimal gstRate,
    @NotNull BigDecimal baseRate) {}
```

`ProductResponse.java`:
```java
package com.easycrm.catalog.web.dto;

import com.easycrm.catalog.Product;
import com.easycrm.catalog.Uom;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductResponse(UUID id, String sku, String name, String hsnCode,
                              Uom uom, BigDecimal gstRate, BigDecimal baseRate, boolean active) {

    public static ProductResponse of(Product p) {
        return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.getHsnCode(),
                                   p.getUom(), p.getGstRate(), p.getBaseRate(), p.isActive());
    }
}
```

`ProductService.java`:
```java
package com.easycrm.catalog;

import com.easycrm.catalog.web.dto.ProductCreateRequest;
import com.easycrm.catalog.web.dto.ProductResponse;
import com.easycrm.catalog.web.dto.ProductUpdateRequest;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductService {

    // Compared with compareTo (not equals): BigDecimal("18") != BigDecimal("18.0") under equals.
    private static final BigDecimal[] ALLOWED_GST_RATES = {
        new BigDecimal("0"), new BigDecimal("0.25"), new BigDecimal("3"),
        new BigDecimal("5"), new BigDecimal("12"), new BigDecimal("18"), new BigDecimal("28")
    };

    private final ProductRepository products;

    public ProductService(ProductRepository products) { this.products = products; }

    @Transactional
    public ProductResponse create(ProductCreateRequest req) {
        validate(req.hsnCode(), req.gstRate(), req.baseRate());
        products.findBySku(req.sku()).ifPresent(p -> {
            throw new ConflictException("product with this SKU already exists");
        });
        Product saved = products.save(new Product(req.sku(), req.name(), req.hsnCode(),
                                                  req.uom(), req.gstRate(), req.baseRate()));
        return ProductResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(UUID id) {
        return ProductResponse.of(find(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> list(Boolean active, Pageable pageable) {
        var page = (active == null)
            ? products.findAll(pageable)
            : products.findByActive(active, pageable);
        return PageResponse.of(page.map(ProductResponse::of));
    }

    @Transactional
    public ProductResponse update(UUID id, ProductUpdateRequest req) {
        validate(req.hsnCode(), req.gstRate(), req.baseRate());
        Product p = find(id);
        p.update(req.name(), req.hsnCode(), req.uom(), req.gstRate(), req.baseRate());
        return ProductResponse.of(p);
    }

    @Transactional
    public ProductResponse deactivate(UUID id) {
        Product p = find(id);
        p.deactivate();
        return ProductResponse.of(p);
    }

    @Transactional
    public ProductResponse activate(UUID id) {
        Product p = find(id);
        p.activate();
        return ProductResponse.of(p);
    }

    private Product find(UUID id) {
        return products.findById(id).orElseThrow(() -> new NotFoundException("product not found"));
    }

    private void validate(String hsnCode, BigDecimal gstRate, BigDecimal baseRate) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (hsnCode != null && !hsnCode.isBlank()
                && !hsnCode.matches("\\d{4}|\\d{6}|\\d{8}")) {
            errors.put("hsnCode", "HSN code must be 4, 6, or 8 digits");
        }
        if (gstRate != null && !isAllowedRate(gstRate)) {
            errors.put("gstRate", "GST rate must be one of 0, 0.25, 3, 5, 12, 18, 28");
        }
        if (baseRate != null && baseRate.compareTo(BigDecimal.ZERO) < 0) {
            errors.put("baseRate", "base rate must not be negative");
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
    }

    private boolean isAllowedRate(BigDecimal rate) {
        for (BigDecimal allowed : ALLOWED_GST_RATES) {
            if (allowed.compareTo(rate) == 0) return true;
        }
        return false;
    }
}
```

`ProductController.java`:
```java
package com.easycrm.catalog.web;

import com.easycrm.catalog.ProductService;
import com.easycrm.catalog.web.dto.ProductCreateRequest;
import com.easycrm.catalog.web.dto.ProductResponse;
import com.easycrm.catalog.web.dto.ProductUpdateRequest;
import com.easycrm.platform.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable UUID id) { return service.get(id); }

    @GetMapping
    public PageResponse<ProductResponse> list(@RequestParam(required = false) Boolean active,
                                              Pageable pageable) {
        return service.list(active, pageable);
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable UUID id,
                                  @Valid @RequestBody ProductUpdateRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/deactivate")
    public ProductResponse deactivate(@PathVariable UUID id) { return service.deactivate(id); }

    @PostMapping("/{id}/activate")
    public ProductResponse activate(@PathVariable UUID id) { return service.activate(id); }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.catalog.web.ProductControllerTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/catalog src/test/java/com/easycrm/catalog/web
git commit -m "feat: Product REST CRUD with HSN/GST-rate validation"
```

---

## Task 6: `Customer` entity + migration + repository

**Files:**
- Create: `backend/src/main/java/com/easycrm/crm/CustomerSource.java`
- Create: `backend/src/main/java/com/easycrm/crm/Customer.java`
- Create: `backend/src/main/java/com/easycrm/crm/CustomerRepository.java`
- Create: `backend/src/main/resources/db/migration/V10__customer.sql`
- Test: `backend/src/test/java/com/easycrm/crm/CustomerRepositoryTest.java`

**Interfaces:**
- Produces: `Customer(String businessName, String gstin, String stateCode, String billingAddress, String shippingAddress, int creditDays, UUID assignedTo, UUID priceListId, CustomerSource source)`; `update(...)` (same params); `activate()/deactivate()`; getters. `CustomerRepository` with `Optional<Customer> findByGstin(String)` and `Page<Customer> findByActive(boolean, Pageable)`.

- [ ] **Step 1: Write the failing test**

```java
package com.easycrm.crm;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CustomerRepositoryTest extends IntegrationTest {
    @Autowired CustomerRepository customers;

    @AfterEach void clear() { TenantContext.clear(); }

    private void asTenant(UUID t) {
        TenantContext.set(new TenantContext.TenantPrincipal(t, UUID.randomUUID(), "OWNER"));
    }

    @Test
    void savesAndFindsByGstinWithinTenant() {
        asTenant(UUID.randomUUID());
        customers.save(new Customer("Acme Traders", "27AAPFU0939F1ZV", "27",
                                    null, null, 30, null, null, CustomerSource.MANUAL));
        assertTrue(customers.findByGstin("27AAPFU0939F1ZV").isPresent());
    }

    @Test
    void allowsMultipleCustomersWithoutGstin() {
        asTenant(UUID.randomUUID());
        customers.save(new Customer("Walk-in A", null, "27",
                                    null, null, 0, null, null, CustomerSource.PHONE));
        customers.save(new Customer("Walk-in B", null, "27",
                                    null, null, 0, null, null, CustomerSource.PHONE));
        assertEquals(2, customers.findAll().size(), "null GSTINs must not collide on the unique key");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.crm.CustomerRepositoryTest"`
Expected: FAIL — classes/table do not exist.

- [ ] **Step 3: Write minimal implementation**

`CustomerSource.java`:
```java
package com.easycrm.crm;

public enum CustomerSource { INDIAMART, WHATSAPP, PHONE, REFERRAL, MANUAL, IMPORT }
```

`Customer.java`:
```java
package com.easycrm.crm;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

@Entity
@Table(name = "customer",
       uniqueConstraints = @UniqueConstraint(name = "uq_customer_tenant_gstin",
                                             columnNames = {"tenant_id", "gstin"}))
public class Customer extends TenantScopedEntity {

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(length = 15)
    private String gstin;

    @Column(name = "state_code", nullable = false, length = 2)
    private String stateCode;

    @Column(name = "billing_address", length = 512)
    private String billingAddress;

    @Column(name = "shipping_address", length = 512)
    private String shippingAddress;

    @Column(name = "credit_days", nullable = false)
    private int creditDays;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "price_list_id")
    private UUID priceListId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CustomerSource source;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected Customer() {}

    public Customer(String businessName, String gstin, String stateCode,
                    String billingAddress, String shippingAddress, int creditDays,
                    UUID assignedTo, UUID priceListId, CustomerSource source) {
        this.businessName = businessName;
        this.gstin = gstin;
        this.stateCode = stateCode;
        this.billingAddress = billingAddress;
        this.shippingAddress = shippingAddress;
        this.creditDays = creditDays;
        this.assignedTo = assignedTo;
        this.priceListId = priceListId;
        this.source = source;
        this.active = true;
    }

    public void update(String businessName, String gstin, String stateCode,
                       String billingAddress, String shippingAddress, int creditDays,
                       UUID assignedTo, UUID priceListId, CustomerSource source) {
        this.businessName = businessName;
        this.gstin = gstin;
        this.stateCode = stateCode;
        this.billingAddress = billingAddress;
        this.shippingAddress = shippingAddress;
        this.creditDays = creditDays;
        this.assignedTo = assignedTo;
        this.priceListId = priceListId;
        this.source = source;
    }

    public void activate() { this.active = true; }
    public void deactivate() { this.active = false; }

    public String getBusinessName() { return businessName; }
    public String getGstin() { return gstin; }
    public String getStateCode() { return stateCode; }
    public String getBillingAddress() { return billingAddress; }
    public String getShippingAddress() { return shippingAddress; }
    public int getCreditDays() { return creditDays; }
    public UUID getAssignedTo() { return assignedTo; }
    public UUID getPriceListId() { return priceListId; }
    public CustomerSource getSource() { return source; }
    public boolean isActive() { return active; }
}
```

`CustomerRepository.java`:
```java
package com.easycrm.crm;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    @Transactional(readOnly = true)
    Optional<Customer> findByGstin(String gstin);

    @Transactional(readOnly = true)
    Page<Customer> findByActive(boolean active, Pageable pageable);
}
```

`V10__customer.sql`:
```sql
CREATE TABLE customer (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    business_name    VARCHAR(255) NOT NULL,
    gstin            VARCHAR(15),
    state_code       VARCHAR(2) NOT NULL,
    billing_address  VARCHAR(512),
    shipping_address VARCHAR(512),
    credit_days      INTEGER NOT NULL DEFAULT 0,
    assigned_to      UUID,
    price_list_id    UUID,
    source           VARCHAR(16) NOT NULL,
    is_active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ,
    version          BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_customer_tenant_gstin UNIQUE (tenant_id, gstin)
);
CREATE INDEX idx_customer_tenant ON customer (tenant_id, id);

ALTER TABLE customer ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
```

Note: `UNIQUE (tenant_id, gstin)` allows many NULL `gstin` rows per tenant (Postgres treats each NULL as distinct) — exactly the "unregistered walk-in" case.

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.crm.CustomerRepositoryTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/crm src/main/resources/db/migration/V10__customer.sql src/test/java/com/easycrm/crm
git commit -m "feat: Customer entity, migration with RLS, and tenant-scoped repository"
```

---

## Task 7: `Customer` service + controller + cross-tenant 404 test

**Files:**
- Create: `backend/src/main/java/com/easycrm/crm/CustomerService.java`
- Create: `backend/src/main/java/com/easycrm/crm/web/CustomerController.java`
- Create: `backend/src/main/java/com/easycrm/crm/web/dto/CustomerRequest.java`
- Create: `backend/src/main/java/com/easycrm/crm/web/dto/CustomerResponse.java`
- Test: `backend/src/test/java/com/easycrm/crm/web/CustomerControllerTest.java`

**Interfaces:**
- Consumes: `CustomerRepository`, `Gstin`, `StateCode`, `ValidationException`, `ConflictException`, `NotFoundException`, `PageResponse`, `TestTokens`.
- Produces: `CustomerService.create/get/list/update/deactivate/activate`; REST under `/api/v1/customers`. GSTIN-present ⇒ checksum-validated + `state_code` derived (must match if also supplied); GSTIN-absent ⇒ `state_code` required and validated.

- [ ] **Step 1: Write the failing test**

```java
package com.easycrm.crm.web;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CustomerControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired CustomerRepository customers;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void createWithGstinDerivesStateCode() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String create = """
            {"businessName":"Acme","gstin":"27AAPFU0939F1ZV","source":"MANUAL"}""";
        mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.stateCode").value("27"));
    }

    @Test
    void badChecksumReturns422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String create = """
            {"businessName":"Acme","gstin":"27AAPFU0939F1ZZ","source":"MANUAL"}""";
        mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.fields.gstin").exists());
    }

    @Test
    void missingStateCodeWithoutGstinReturns422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String create = """
            {"businessName":"Walk-in","source":"PHONE"}""";
        mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.fields.stateCode").exists());
    }

    @Test
    void crossTenantGetReturns404() throws Exception {
        UUID tenantA = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenantA, UUID.randomUUID(), "OWNER"));
        Customer saved = customers.saveAndFlush(new Customer("Acme A", null, "27",
                                    null, null, 0, null, null, CustomerSource.MANUAL));
        TenantContext.clear();

        String otherTenantAuth = "Bearer " + tokens.owner(UUID.randomUUID());
        mvc.perform(get("/api/v1/customers/" + saved.getId()).header("Authorization", otherTenantAuth))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.crm.web.CustomerControllerTest"`
Expected: FAIL — service/controller/DTOs do not exist.

- [ ] **Step 3: Write minimal implementation**

`CustomerRequest.java`:
```java
package com.easycrm.crm.web.dto;

import com.easycrm.crm.CustomerSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CustomerRequest(
    @NotBlank String businessName,
    String gstin,
    String stateCode,
    String billingAddress,
    String shippingAddress,
    Integer creditDays,
    UUID assignedTo,
    UUID priceListId,
    @NotNull CustomerSource source) {}
```

`CustomerResponse.java`:
```java
package com.easycrm.crm.web.dto;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerSource;

import java.util.UUID;

public record CustomerResponse(UUID id, String businessName, String gstin, String stateCode,
                               String billingAddress, String shippingAddress, int creditDays,
                               UUID assignedTo, UUID priceListId, CustomerSource source,
                               boolean active) {

    public static CustomerResponse of(Customer c) {
        return new CustomerResponse(c.getId(), c.getBusinessName(), c.getGstin(), c.getStateCode(),
            c.getBillingAddress(), c.getShippingAddress(), c.getCreditDays(),
            c.getAssignedTo(), c.getPriceListId(), c.getSource(), c.isActive());
    }
}
```

`CustomerService.java`:
```java
package com.easycrm.crm;

import com.easycrm.crm.web.dto.CustomerRequest;
import com.easycrm.crm.web.dto.CustomerResponse;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.gst.Gstin;
import com.easycrm.platform.gst.StateCode;
import com.easycrm.platform.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CustomerService {

    private final CustomerRepository customers;

    public CustomerService(CustomerRepository customers) { this.customers = customers; }

    @Transactional
    public CustomerResponse create(CustomerRequest req) {
        Resolved r = resolveGstinAndState(req);
        if (r.gstin() != null) {
            customers.findByGstin(r.gstin()).ifPresent(c -> {
                throw new ConflictException("customer with this GSTIN already exists");
            });
        }
        Customer saved = customers.save(new Customer(req.businessName(), r.gstin(), r.stateCode(),
            req.billingAddress(), req.shippingAddress(), creditDays(req),
            req.assignedTo(), req.priceListId(), req.source()));
        return CustomerResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(UUID id) { return CustomerResponse.of(find(id)); }

    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> list(Boolean active, Pageable pageable) {
        var page = (active == null)
            ? customers.findAll(pageable)
            : customers.findByActive(active, pageable);
        return PageResponse.of(page.map(CustomerResponse::of));
    }

    @Transactional
    public CustomerResponse update(UUID id, CustomerRequest req) {
        Resolved r = resolveGstinAndState(req);
        Customer c = find(id);
        c.update(req.businessName(), r.gstin(), r.stateCode(), req.billingAddress(),
            req.shippingAddress(), creditDays(req), req.assignedTo(), req.priceListId(), req.source());
        return CustomerResponse.of(c);
    }

    @Transactional
    public CustomerResponse deactivate(UUID id) {
        Customer c = find(id); c.deactivate(); return CustomerResponse.of(c);
    }

    @Transactional
    public CustomerResponse activate(UUID id) {
        Customer c = find(id); c.activate(); return CustomerResponse.of(c);
    }

    private Customer find(UUID id) {
        return customers.findById(id).orElseThrow(() -> new NotFoundException("customer not found"));
    }

    private int creditDays(CustomerRequest req) {
        return req.creditDays() == null ? 0 : req.creditDays();
    }

    /** GSTIN present ⇒ validate checksum, derive state (must match if supplied). Absent ⇒ require valid state_code. */
    private Resolved resolveGstinAndState(CustomerRequest req) {
        if (req.gstin() != null && !req.gstin().isBlank()) {
            Gstin g = Gstin.parse(req.gstin()); // throws 422 on bad checksum
            String derived = g.stateCode();
            if (req.stateCode() != null && !req.stateCode().isBlank()
                    && !req.stateCode().equals(derived)) {
                throw new ValidationException("stateCode", "must match the GSTIN state code");
            }
            return new Resolved(g.value(), derived);
        }
        if (req.stateCode() == null || req.stateCode().isBlank()) {
            throw new ValidationException("stateCode", "state code is required when GSTIN is absent");
        }
        StateCode.requireValid(req.stateCode());
        return new Resolved(null, req.stateCode());
    }

    private record Resolved(String gstin, String stateCode) {}
}
```

`CustomerController.java`:
```java
package com.easycrm.crm.web;

import com.easycrm.crm.CustomerService;
import com.easycrm.crm.web.dto.CustomerRequest;
import com.easycrm.crm.web.dto.CustomerResponse;
import com.easycrm.platform.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable UUID id) { return service.get(id); }

    @GetMapping
    public PageResponse<CustomerResponse> list(@RequestParam(required = false) Boolean active,
                                               Pageable pageable) {
        return service.list(active, pageable);
    }

    @PutMapping("/{id}")
    public CustomerResponse update(@PathVariable UUID id, @Valid @RequestBody CustomerRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/deactivate")
    public CustomerResponse deactivate(@PathVariable UUID id) { return service.deactivate(id); }

    @PostMapping("/{id}/activate")
    public CustomerResponse activate(@PathVariable UUID id) { return service.activate(id); }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.crm.web.CustomerControllerTest"`
Expected: PASS (4 tests, including the cross-tenant 404).

- [ ] **Step 5: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/crm src/test/java/com/easycrm/crm/web
git commit -m "feat: Customer REST CRUD with GSTIN checksum + state-code derivation"
```

---

## Task 8: `Contact` entity + migration + repository

**Files:**
- Create: `backend/src/main/java/com/easycrm/crm/Contact.java`
- Create: `backend/src/main/java/com/easycrm/crm/ContactRepository.java`
- Create: `backend/src/main/resources/db/migration/V11__contact.sql`
- Test: `backend/src/test/java/com/easycrm/crm/ContactRepositoryTest.java`

**Interfaces:**
- Produces: `Contact(UUID customerId, String name, String phone, String whatsappNumber, String email, String designation, boolean primary)`; `update(...)`; getters incl. `getCustomerId`, `isPrimary`. `ContactRepository` with `List<Contact> findByCustomerId(UUID)`.

- [ ] **Step 1: Write the failing test**

```java
package com.easycrm.crm;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ContactRepositoryTest extends IntegrationTest {
    @Autowired ContactRepository contacts;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void findsContactsByCustomerWithinTenant() {
        TenantContext.set(new TenantContext.TenantPrincipal(UUID.randomUUID(), UUID.randomUUID(), "OWNER"));
        UUID customerId = UUID.randomUUID();
        contacts.save(new Contact(customerId, "Ravi", "9876543210", "9876543210",
                                  "ravi@acme.test", "Purchase", true));
        assertEquals(1, contacts.findByCustomerId(customerId).size());
        assertTrue(contacts.findByCustomerId(UUID.randomUUID()).isEmpty());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.crm.ContactRepositoryTest"`
Expected: FAIL — classes/table do not exist.

- [ ] **Step 3: Write minimal implementation**

`Contact.java`:
```java
package com.easycrm.crm;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "contact")
public class Contact extends TenantScopedEntity {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false)
    private String name;

    @Column(length = 20)
    private String phone;

    @Column(name = "whatsapp_number", length = 20)
    private String whatsappNumber;

    @Column
    private String email;

    @Column(length = 128)
    private String designation;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    protected Contact() {}

    public Contact(UUID customerId, String name, String phone, String whatsappNumber,
                   String email, String designation, boolean primary) {
        this.customerId = customerId;
        this.name = name;
        this.phone = phone;
        this.whatsappNumber = whatsappNumber;
        this.email = email;
        this.designation = designation;
        this.primary = primary;
    }

    public void update(String name, String phone, String whatsappNumber,
                       String email, String designation, boolean primary) {
        this.name = name;
        this.phone = phone;
        this.whatsappNumber = whatsappNumber;
        this.email = email;
        this.designation = designation;
        this.primary = primary;
    }

    public UUID getCustomerId() { return customerId; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getWhatsappNumber() { return whatsappNumber; }
    public String getEmail() { return email; }
    public String getDesignation() { return designation; }
    public boolean isPrimary() { return primary; }
}
```

`ContactRepository.java`:
```java
package com.easycrm.crm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface ContactRepository extends JpaRepository<Contact, UUID> {

    @Transactional(readOnly = true)
    List<Contact> findByCustomerId(UUID customerId);
}
```

`V11__contact.sql`:
```sql
CREATE TABLE contact (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    customer_id     UUID NOT NULL,
    name            VARCHAR(255) NOT NULL,
    phone           VARCHAR(20),
    whatsapp_number VARCHAR(20),
    email           VARCHAR(255),
    designation     VARCHAR(128),
    is_primary      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    version         BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_contact_tenant ON contact (tenant_id, id);
CREATE INDEX idx_contact_customer ON contact (tenant_id, customer_id);

ALTER TABLE contact ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON contact
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.crm.ContactRepositoryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/crm/Contact.java src/main/java/com/easycrm/crm/ContactRepository.java src/main/resources/db/migration/V11__contact.sql src/test/java/com/easycrm/crm/ContactRepositoryTest.java
git commit -m "feat: Contact entity, migration with RLS, and repository"
```

---

## Task 9: `Contact` service + nested controller

**Files:**
- Create: `backend/src/main/java/com/easycrm/crm/ContactService.java`
- Create: `backend/src/main/java/com/easycrm/crm/web/ContactController.java`
- Create: `backend/src/main/java/com/easycrm/crm/web/dto/ContactRequest.java`
- Create: `backend/src/main/java/com/easycrm/crm/web/dto/ContactResponse.java`
- Test: `backend/src/test/java/com/easycrm/crm/web/ContactControllerTest.java`

**Interfaces:**
- Consumes: `ContactRepository`, `CustomerRepository` (to 404 on unknown customer), `NotFoundException`, `TestTokens`.
- Produces: `ContactService.add/list/update/delete`; REST `POST/GET/PUT/DELETE /api/v1/customers/{customerId}/contacts[/{contactId}]`.

- [ ] **Step 1: Write the failing test**

```java
package com.easycrm.crm.web;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ContactControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired CustomerRepository customers;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void addAndListContacts() throws Exception {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenant, UUID.randomUUID(), "OWNER"));
        Customer c = customers.saveAndFlush(new Customer("Acme", null, "27",
                                    null, null, 0, null, null, CustomerSource.MANUAL));
        TenantContext.clear();

        String auth = "Bearer " + tokens.owner(tenant);
        String add = """
            {"name":"Ravi","phone":"9876543210","isPrimary":true}""";
        mvc.perform(post("/api/v1/customers/" + c.getId() + "/contacts")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(add))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Ravi"));

        mvc.perform(get("/api/v1/customers/" + c.getId() + "/contacts").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void addContactToUnknownCustomerReturns404() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String add = "{\"name\":\"Ravi\"}";
        mvc.perform(post("/api/v1/customers/" + UUID.randomUUID() + "/contacts")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(add))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.crm.web.ContactControllerTest"`
Expected: FAIL — service/controller/DTOs do not exist.

- [ ] **Step 3: Write minimal implementation**

`ContactRequest.java`:
```java
package com.easycrm.crm.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ContactRequest(
    @NotBlank String name,
    String phone,
    String whatsappNumber,
    String email,
    String designation,
    boolean isPrimary) {}
```

`ContactResponse.java`:
```java
package com.easycrm.crm.web.dto;

import com.easycrm.crm.Contact;

import java.util.UUID;

public record ContactResponse(UUID id, UUID customerId, String name, String phone,
                              String whatsappNumber, String email, String designation,
                              boolean isPrimary) {

    public static ContactResponse of(Contact c) {
        return new ContactResponse(c.getId(), c.getCustomerId(), c.getName(), c.getPhone(),
            c.getWhatsappNumber(), c.getEmail(), c.getDesignation(), c.isPrimary());
    }
}
```

`ContactService.java`:
```java
package com.easycrm.crm;

import com.easycrm.crm.web.dto.ContactRequest;
import com.easycrm.crm.web.dto.ContactResponse;
import com.easycrm.platform.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ContactService {

    private final ContactRepository contacts;
    private final CustomerRepository customers;

    public ContactService(ContactRepository contacts, CustomerRepository customers) {
        this.contacts = contacts;
        this.customers = customers;
    }

    @Transactional
    public ContactResponse add(UUID customerId, ContactRequest req) {
        requireCustomer(customerId);
        Contact saved = contacts.save(new Contact(customerId, req.name(), req.phone(),
            req.whatsappNumber(), req.email(), req.designation(), req.isPrimary()));
        return ContactResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public List<ContactResponse> list(UUID customerId) {
        requireCustomer(customerId);
        return contacts.findByCustomerId(customerId).stream().map(ContactResponse::of).toList();
    }

    @Transactional
    public ContactResponse update(UUID customerId, UUID contactId, ContactRequest req) {
        Contact c = find(customerId, contactId);
        c.update(req.name(), req.phone(), req.whatsappNumber(), req.email(),
                 req.designation(), req.isPrimary());
        return ContactResponse.of(c);
    }

    @Transactional
    public void delete(UUID customerId, UUID contactId) {
        contacts.delete(find(customerId, contactId));
    }

    private void requireCustomer(UUID customerId) {
        customers.findById(customerId)
            .orElseThrow(() -> new NotFoundException("customer not found"));
    }

    private Contact find(UUID customerId, UUID contactId) {
        Contact c = contacts.findById(contactId)
            .orElseThrow(() -> new NotFoundException("contact not found"));
        if (!c.getCustomerId().equals(customerId)) {
            throw new NotFoundException("contact not found");
        }
        return c;
    }
}
```

`ContactController.java`:
```java
package com.easycrm.crm.web;

import com.easycrm.crm.ContactService;
import com.easycrm.crm.web.dto.ContactRequest;
import com.easycrm.crm.web.dto.ContactResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers/{customerId}/contacts")
public class ContactController {

    private final ContactService service;

    public ContactController(ContactService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ContactResponse> add(@PathVariable UUID customerId,
                                               @Valid @RequestBody ContactRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.add(customerId, req));
    }

    @GetMapping
    public List<ContactResponse> list(@PathVariable UUID customerId) {
        return service.list(customerId);
    }

    @PutMapping("/{contactId}")
    public ContactResponse update(@PathVariable UUID customerId, @PathVariable UUID contactId,
                                  @Valid @RequestBody ContactRequest req) {
        return service.update(customerId, contactId, req);
    }

    @DeleteMapping("/{contactId}")
    public ResponseEntity<Void> delete(@PathVariable UUID customerId, @PathVariable UUID contactId) {
        service.delete(customerId, contactId);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.crm.web.ContactControllerTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/crm src/test/java/com/easycrm/crm/web/ContactControllerTest.java
git commit -m "feat: Contact nested REST CRUD under customer"
```

---

## Task 10: `PriceList` entity + migration + repository

**Files:**
- Create: `backend/src/main/java/com/easycrm/catalog/PriceList.java`
- Create: `backend/src/main/java/com/easycrm/catalog/PriceListRepository.java`
- Create: `backend/src/main/resources/db/migration/V12__price_list.sql`
- Test: `backend/src/test/java/com/easycrm/catalog/PriceListRepositoryTest.java`

**Interfaces:**
- Produces: `PriceList(String name)`; `rename(String name)`; `activate()/deactivate()`; getters `getName`, `isActive`. `PriceListRepository` with `Optional<PriceList> findByName(String)` and `Page<PriceList> findByActive(boolean, Pageable)`.

- [ ] **Step 1: Write the failing test**

```java
package com.easycrm.catalog;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PriceListRepositoryTest extends IntegrationTest {
    @Autowired PriceListRepository priceLists;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void savesAndFindsByNameWithinTenant() {
        TenantContext.set(new TenantContext.TenantPrincipal(UUID.randomUUID(), UUID.randomUUID(), "OWNER"));
        priceLists.save(new PriceList("Dealer"));
        assertTrue(priceLists.findByName("Dealer").isPresent());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.catalog.PriceListRepositoryTest"`
Expected: FAIL — classes/table do not exist.

- [ ] **Step 3: Write minimal implementation**

`PriceList.java`:
```java
package com.easycrm.catalog;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "price_list",
       uniqueConstraints = @UniqueConstraint(name = "uq_price_list_tenant_name",
                                             columnNames = {"tenant_id", "name"}))
public class PriceList extends TenantScopedEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected PriceList() {}

    public PriceList(String name) {
        this.name = name;
        this.active = true;
    }

    public void rename(String name) { this.name = name; }
    public void activate() { this.active = true; }
    public void deactivate() { this.active = false; }

    public String getName() { return name; }
    public boolean isActive() { return active; }
}
```

`PriceListRepository.java`:
```java
package com.easycrm.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface PriceListRepository extends JpaRepository<PriceList, UUID> {

    @Transactional(readOnly = true)
    Optional<PriceList> findByName(String name);

    @Transactional(readOnly = true)
    Page<PriceList> findByActive(boolean active, Pageable pageable);
}
```

`V12__price_list.sql`:
```sql
CREATE TABLE price_list (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    name       VARCHAR(255) NOT NULL,
    is_active  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    version    BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_price_list_tenant_name UNIQUE (tenant_id, name)
);
CREATE INDEX idx_price_list_tenant ON price_list (tenant_id, id);

ALTER TABLE price_list ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON price_list
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.catalog.PriceListRepositoryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/catalog/PriceList.java src/main/java/com/easycrm/catalog/PriceListRepository.java src/main/resources/db/migration/V12__price_list.sql src/test/java/com/easycrm/catalog/PriceListRepositoryTest.java
git commit -m "feat: PriceList entity, migration with RLS, and repository"
```

---

## Task 11: `PriceList` service + controller

**Files:**
- Create: `backend/src/main/java/com/easycrm/catalog/PriceListService.java`
- Create: `backend/src/main/java/com/easycrm/catalog/web/PriceListController.java`
- Create: `backend/src/main/java/com/easycrm/catalog/web/dto/PriceListRequest.java`
- Create: `backend/src/main/java/com/easycrm/catalog/web/dto/PriceListResponse.java`
- Test: `backend/src/test/java/com/easycrm/catalog/web/PriceListControllerTest.java`

**Interfaces:**
- Consumes: `PriceListRepository`, `ConflictException`, `NotFoundException`, `PageResponse`, `TestTokens`.
- Produces: `PriceListService.create/get/list/rename/deactivate/activate`; REST under `/api/v1/price-lists`.

- [ ] **Step 1: Write the failing test**

```java
package com.easycrm.catalog.web;

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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PriceListControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void createThenRejectDuplicateName() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String body = "{\"name\":\"Dealer\"}";
        mvc.perform(post("/api/v1/price-lists").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Dealer"));
        mvc.perform(post("/api/v1/price-lists").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.catalog.web.PriceListControllerTest"`
Expected: FAIL — service/controller/DTOs do not exist.

- [ ] **Step 3: Write minimal implementation**

`PriceListRequest.java`:
```java
package com.easycrm.catalog.web.dto;

import jakarta.validation.constraints.NotBlank;

public record PriceListRequest(@NotBlank String name) {}
```

`PriceListResponse.java`:
```java
package com.easycrm.catalog.web.dto;

import com.easycrm.catalog.PriceList;

import java.util.UUID;

public record PriceListResponse(UUID id, String name, boolean active) {

    public static PriceListResponse of(PriceList p) {
        return new PriceListResponse(p.getId(), p.getName(), p.isActive());
    }
}
```

`PriceListService.java`:
```java
package com.easycrm.catalog;

import com.easycrm.catalog.web.dto.PriceListRequest;
import com.easycrm.catalog.web.dto.PriceListResponse;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PriceListService {

    private final PriceListRepository priceLists;

    public PriceListService(PriceListRepository priceLists) { this.priceLists = priceLists; }

    @Transactional
    public PriceListResponse create(PriceListRequest req) {
        priceLists.findByName(req.name()).ifPresent(p -> {
            throw new ConflictException("a price list with this name already exists");
        });
        return PriceListResponse.of(priceLists.save(new PriceList(req.name())));
    }

    @Transactional(readOnly = true)
    public PriceListResponse get(UUID id) { return PriceListResponse.of(find(id)); }

    @Transactional(readOnly = true)
    public PageResponse<PriceListResponse> list(Boolean active, Pageable pageable) {
        var page = (active == null)
            ? priceLists.findAll(pageable)
            : priceLists.findByActive(active, pageable);
        return PageResponse.of(page.map(PriceListResponse::of));
    }

    @Transactional
    public PriceListResponse rename(UUID id, PriceListRequest req) {
        priceLists.findByName(req.name()).ifPresent(p -> {
            if (!p.getId().equals(id)) throw new ConflictException("a price list with this name already exists");
        });
        PriceList p = find(id);
        p.rename(req.name());
        return PriceListResponse.of(p);
    }

    @Transactional
    public PriceListResponse deactivate(UUID id) {
        PriceList p = find(id); p.deactivate(); return PriceListResponse.of(p);
    }

    @Transactional
    public PriceListResponse activate(UUID id) {
        PriceList p = find(id); p.activate(); return PriceListResponse.of(p);
    }

    private PriceList find(UUID id) {
        return priceLists.findById(id).orElseThrow(() -> new NotFoundException("price list not found"));
    }
}
```

`PriceListController.java`:
```java
package com.easycrm.catalog.web;

import com.easycrm.catalog.PriceListService;
import com.easycrm.catalog.web.dto.PriceListRequest;
import com.easycrm.catalog.web.dto.PriceListResponse;
import com.easycrm.platform.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/price-lists")
public class PriceListController {

    private final PriceListService service;

    public PriceListController(PriceListService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<PriceListResponse> create(@Valid @RequestBody PriceListRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    public PriceListResponse get(@PathVariable UUID id) { return service.get(id); }

    @GetMapping
    public PageResponse<PriceListResponse> list(@RequestParam(required = false) Boolean active,
                                                Pageable pageable) {
        return service.list(active, pageable);
    }

    @PutMapping("/{id}")
    public PriceListResponse rename(@PathVariable UUID id, @Valid @RequestBody PriceListRequest req) {
        return service.rename(id, req);
    }

    @PostMapping("/{id}/deactivate")
    public PriceListResponse deactivate(@PathVariable UUID id) { return service.deactivate(id); }

    @PostMapping("/{id}/activate")
    public PriceListResponse activate(@PathVariable UUID id) { return service.activate(id); }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.catalog.web.PriceListControllerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/catalog src/test/java/com/easycrm/catalog/web/PriceListControllerTest.java
git commit -m "feat: PriceList REST CRUD"
```

---

## Task 12: `PriceListItem` entity + migration (with CHECK) + repository

**Files:**
- Create: `backend/src/main/java/com/easycrm/catalog/PriceListItem.java`
- Create: `backend/src/main/java/com/easycrm/catalog/PriceListItemRepository.java`
- Create: `backend/src/main/resources/db/migration/V13__price_list_item.sql`
- Test: `backend/src/test/java/com/easycrm/catalog/PriceListItemRepositoryTest.java`

**Interfaces:**
- Produces: `PriceListItem(UUID priceListId, UUID productId, BigDecimal overrideRate, BigDecimal discountPct)`; getters `getPriceListId/getProductId/getOverrideRate/getDiscountPct`. `PriceListItemRepository` with `List<PriceListItem> findByPriceListId(UUID)` and `Optional<PriceListItem> findByPriceListIdAndProductId(UUID, UUID)`.

- [ ] **Step 1: Write the failing test**

```java
package com.easycrm.catalog;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PriceListItemRepositoryTest extends IntegrationTest {
    @Autowired PriceListItemRepository items;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void savesAndFindsItemsByPriceList() {
        TenantContext.set(new TenantContext.TenantPrincipal(UUID.randomUUID(), UUID.randomUUID(), "OWNER"));
        UUID priceListId = UUID.randomUUID();
        items.save(new PriceListItem(priceListId, UUID.randomUUID(), new BigDecimal("95.00"), null));
        assertEquals(1, items.findByPriceListId(priceListId).size());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.catalog.PriceListItemRepositoryTest"`
Expected: FAIL — classes/table do not exist.

- [ ] **Step 3: Write minimal implementation**

`PriceListItem.java`:
```java
package com.easycrm.catalog;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "price_list_item",
       uniqueConstraints = @UniqueConstraint(name = "uq_pli_tenant_list_product",
                                             columnNames = {"tenant_id", "price_list_id", "product_id"}))
public class PriceListItem extends TenantScopedEntity {

    @Column(name = "price_list_id", nullable = false)
    private UUID priceListId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "override_rate", precision = 18, scale = 2)
    private BigDecimal overrideRate;

    @Column(name = "discount_pct", precision = 18, scale = 4)
    private BigDecimal discountPct;

    protected PriceListItem() {}

    public PriceListItem(UUID priceListId, UUID productId, BigDecimal overrideRate, BigDecimal discountPct) {
        this.priceListId = priceListId;
        this.productId = productId;
        this.overrideRate = overrideRate;
        this.discountPct = discountPct;
    }

    public UUID getPriceListId() { return priceListId; }
    public UUID getProductId() { return productId; }
    public BigDecimal getOverrideRate() { return overrideRate; }
    public BigDecimal getDiscountPct() { return discountPct; }
}
```

`PriceListItemRepository.java`:
```java
package com.easycrm.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PriceListItemRepository extends JpaRepository<PriceListItem, UUID> {

    @Transactional(readOnly = true)
    List<PriceListItem> findByPriceListId(UUID priceListId);

    @Transactional(readOnly = true)
    Optional<PriceListItem> findByPriceListIdAndProductId(UUID priceListId, UUID productId);
}
```

`V13__price_list_item.sql`:
```sql
CREATE TABLE price_list_item (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    price_list_id UUID NOT NULL,
    product_id    UUID NOT NULL,
    override_rate NUMERIC(18,2),
    discount_pct  NUMERIC(18,4),
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ,
    version       BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_pli_tenant_list_product UNIQUE (tenant_id, price_list_id, product_id),
    CONSTRAINT ck_pli_rate_xor CHECK (num_nonnulls(override_rate, discount_pct) = 1)
);
CREATE INDEX idx_pli_tenant ON price_list_item (tenant_id, id);
CREATE INDEX idx_pli_list ON price_list_item (tenant_id, price_list_id);

ALTER TABLE price_list_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON price_list_item
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.catalog.PriceListItemRepositoryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/catalog/PriceListItem.java src/main/java/com/easycrm/catalog/PriceListItemRepository.java src/main/resources/db/migration/V13__price_list_item.sql src/test/java/com/easycrm/catalog/PriceListItemRepositoryTest.java
git commit -m "feat: PriceListItem entity with override/discount XOR check, migration, repository"
```

---

## Task 13: `PriceListItem` service (XOR validation) + nested controller

**Files:**
- Create: `backend/src/main/java/com/easycrm/catalog/PriceListItemService.java`
- Create: `backend/src/main/java/com/easycrm/catalog/web/PriceListItemController.java`
- Create: `backend/src/main/java/com/easycrm/catalog/web/dto/PriceListItemRequest.java`
- Create: `backend/src/main/java/com/easycrm/catalog/web/dto/PriceListItemResponse.java`
- Test: `backend/src/test/java/com/easycrm/catalog/web/PriceListItemControllerTest.java`

**Interfaces:**
- Consumes: `PriceListItemRepository`, `PriceListRepository` (404 on unknown list), `ProductRepository` (404 on unknown product), `ValidationException`, `ConflictException`, `NotFoundException`, `TestTokens`.
- Produces: `PriceListItemService.add/list/delete`; REST `POST/GET/DELETE /api/v1/price-lists/{priceListId}/items[/{itemId}]`. Exactly one of `overrideRate`/`discountPct` must be set (422 otherwise).

- [ ] **Step 1: Write the failing test**

```java
package com.easycrm.catalog.web;

import com.easycrm.catalog.PriceList;
import com.easycrm.catalog.PriceListRepository;
import com.easycrm.catalog.Product;
import com.easycrm.catalog.ProductRepository;
import com.easycrm.catalog.Uom;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PriceListItemControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired PriceListRepository priceLists;
    @Autowired ProductRepository products;

    @AfterEach void clear() { TenantContext.clear(); }

    private record Fixture(UUID tenant, UUID priceListId, UUID productId) {}

    private Fixture seed() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenant, UUID.randomUUID(), "OWNER"));
        PriceList pl = priceLists.saveAndFlush(new PriceList("Dealer"));
        Product p = products.saveAndFlush(new Product("SKU-PLI", "Widget", "7318", Uom.PCS,
                                          new BigDecimal("18.0000"), new BigDecimal("100.00")));
        TenantContext.clear();
        return new Fixture(tenant, pl.getId(), p.getId());
    }

    @Test
    void addItemWithOverrideRate() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        String body = "{\"productId\":\"" + f.productId() + "\",\"overrideRate\":\"95.00\"}";
        mvc.perform(post("/api/v1/price-lists/" + f.priceListId() + "/items")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.productId").value(f.productId().toString()))
            .andExpect(jsonPath("$.overrideRate").exists());
    }

    @Test
    void rejectsBothRateAndDiscountWith422() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        String body = "{\"productId\":\"" + f.productId()
            + "\",\"overrideRate\":\"95.00\",\"discountPct\":\"10.0\"}";
        mvc.perform(post("/api/v1/price-lists/" + f.priceListId() + "/items")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.fields.overrideRate").exists());
    }

    @Test
    void addItemToUnknownPriceListReturns404() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        String body = "{\"productId\":\"" + f.productId() + "\",\"overrideRate\":\"95.00\"}";
        mvc.perform(post("/api/v1/price-lists/" + UUID.randomUUID() + "/items")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.catalog.web.PriceListItemControllerTest"`
Expected: FAIL — service/controller/DTOs do not exist.

- [ ] **Step 3: Write minimal implementation**

`PriceListItemRequest.java`:
```java
package com.easycrm.catalog.web.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record PriceListItemRequest(
    @NotNull UUID productId,
    BigDecimal overrideRate,
    BigDecimal discountPct) {}
```

`PriceListItemResponse.java`:
```java
package com.easycrm.catalog.web.dto;

import com.easycrm.catalog.PriceListItem;

import java.math.BigDecimal;
import java.util.UUID;

public record PriceListItemResponse(UUID id, UUID priceListId, UUID productId,
                                    BigDecimal overrideRate, BigDecimal discountPct) {

    public static PriceListItemResponse of(PriceListItem i) {
        return new PriceListItemResponse(i.getId(), i.getPriceListId(), i.getProductId(),
            i.getOverrideRate(), i.getDiscountPct());
    }
}
```

`PriceListItemService.java`:
```java
package com.easycrm.catalog;

import com.easycrm.catalog.web.dto.PriceListItemRequest;
import com.easycrm.catalog.web.dto.PriceListItemResponse;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.error.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PriceListItemService {

    private final PriceListItemRepository items;
    private final PriceListRepository priceLists;
    private final ProductRepository products;

    public PriceListItemService(PriceListItemRepository items, PriceListRepository priceLists,
                                ProductRepository products) {
        this.items = items;
        this.priceLists = priceLists;
        this.products = products;
    }

    @Transactional
    public PriceListItemResponse add(UUID priceListId, PriceListItemRequest req) {
        requirePriceList(priceListId);
        requireProduct(req.productId());
        validateXor(req);
        items.findByPriceListIdAndProductId(priceListId, req.productId()).ifPresent(i -> {
            throw new ConflictException("this product is already priced in this list");
        });
        PriceListItem saved = items.save(new PriceListItem(priceListId, req.productId(),
            req.overrideRate(), req.discountPct()));
        return PriceListItemResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public List<PriceListItemResponse> list(UUID priceListId) {
        requirePriceList(priceListId);
        return items.findByPriceListId(priceListId).stream().map(PriceListItemResponse::of).toList();
    }

    @Transactional
    public void delete(UUID priceListId, UUID itemId) {
        PriceListItem i = items.findById(itemId)
            .orElseThrow(() -> new NotFoundException("price list item not found"));
        if (!i.getPriceListId().equals(priceListId)) {
            throw new NotFoundException("price list item not found");
        }
        items.delete(i);
    }

    private void validateXor(PriceListItemRequest req) {
        boolean hasRate = req.overrideRate() != null;
        boolean hasDiscount = req.discountPct() != null;
        if (hasRate == hasDiscount) { // both set OR both null
            throw new ValidationException("overrideRate",
                "exactly one of overrideRate or discountPct must be set");
        }
    }

    private void requirePriceList(UUID priceListId) {
        priceLists.findById(priceListId)
            .orElseThrow(() -> new NotFoundException("price list not found"));
    }

    private void requireProduct(UUID productId) {
        products.findById(productId)
            .orElseThrow(() -> new NotFoundException("product not found"));
    }
}
```

`PriceListItemController.java`:
```java
package com.easycrm.catalog.web;

import com.easycrm.catalog.PriceListItemService;
import com.easycrm.catalog.web.dto.PriceListItemRequest;
import com.easycrm.catalog.web.dto.PriceListItemResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/price-lists/{priceListId}/items")
public class PriceListItemController {

    private final PriceListItemService service;

    public PriceListItemController(PriceListItemService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<PriceListItemResponse> add(@PathVariable UUID priceListId,
                                                     @Valid @RequestBody PriceListItemRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.add(priceListId, req));
    }

    @GetMapping
    public List<PriceListItemResponse> list(@PathVariable UUID priceListId) {
        return service.list(priceListId);
    }

    @DeleteMapping("/{itemId}")
    public ResponseEntity<Void> delete(@PathVariable UUID priceListId, @PathVariable UUID itemId) {
        service.delete(priceListId, itemId);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.catalog.web.PriceListItemControllerTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/catalog src/test/java/com/easycrm/catalog/web/PriceListItemControllerTest.java
git commit -m "feat: PriceListItem nested REST CRUD with override/discount XOR validation"
```

---

## Task 14: Full-suite verification + docs (challenges + annotations)

**Files:**
- Modify: `docs/superpowers/engineering-challenges.md`
- Modify: `docs/superpowers/annotations-reference.md`
- Modify: `docs/superpowers/HANDOFF.md`

**Interfaces:** none (documentation + verification).

- [ ] **Step 1: Run the full suite from a clean build**

Run: `cd backend && ./gradlew clean test`
Expected: BUILD SUCCESSFUL. All prior 50 tests plus the new P1a tests pass. If any fail, fix before continuing — do not document over a red build.

- [ ] **Step 2: Append engineering-challenges entries**

Append two entries to `docs/superpowers/engineering-challenges.md` (use the template at the file bottom; keep numbering stable — these are #12 and #13):

- **Challenge 12 — Validating a GSTIN checksum (Luhn-mod-36).** Problem: a GSTIN's 15th char is a check digit over the first 14 in base-36; a naive "15 chars + right shape" check accepts transposition/typo errors that later corrupt the state-code → GST split. Solution: the `Gstin` value type computes the check digit with the GSTN algorithm (alternating factor 2/1 from the right, digit-sum folding `d/36 + d%36`, check = `(36 − sum%36) % 36`) and derives `state_code` from the first two chars, so an invalid GSTIN never reaches the DB. Lesson: encode domain check-digits as a parse-don't-validate value type reused across modules (P1a customer entry + P1c import), and confirm it against a known-valid (`27AAPFU0939F1ZV`) and a known-bad-checksum (`…ZZ`) fixture.

- **Challenge 13 — The override-rate / discount-percent XOR, and `BigDecimal` equality.** Problem: a price-list item must carry *exactly one* of an absolute override rate or a discount percent — "both" or "neither" is meaningless — and the disallowed-GST-rate check compares `BigDecimal`s where `new BigDecimal("18").equals(new BigDecimal("18.0"))` is **false** (scale-sensitive). Solution: enforce the XOR at two layers (a Postgres `CHECK (num_nonnulls(override_rate, discount_pct) = 1)` and an app-level `ValidationException`), and compare GST rates with `compareTo(...) == 0`, never `equals`. Lesson: invariants worth a DB `CHECK` are also worth an app-level 422 (defence in depth + a friendly field error); and `BigDecimal` set-membership must use `compareTo`, or "18" silently fails to match "18.0".

- [ ] **Step 3: Update annotations-reference**

Add rows to `docs/superpowers/annotations-reference.md` for any annotation P1a introduced that is not already listed. Check the file first; likely-new: `@UniqueConstraint` (JPA, table-level unique key), `@Enumerated(EnumType.STRING)` (JPA, persist enum by name), `@RequestParam` / `@DeleteMapping` (Spring MVC) if not present. Only add rows genuinely missing; match the file's existing column format (origin, purpose, meta-annotation composition).

- [ ] **Step 4: Update HANDOFF**

In `docs/superpowers/HANDOFF.md`: mark P1a done in §3/§4, note the new test count, add the P1a plan to the "Read these" list, and record the P1b follow-ups carried over (money-as-JSON-string Jackson config; price resolution; visibility filtering on `assigned_to`; cursor pagination).

- [ ] **Step 5: Commit**

```bash
cd /Users/divyam/Documents/easy-crm && git add docs/superpowers
git commit -m "docs: log GSTIN checksum + XOR challenges, update annotations and handoff for P1a"
```

---

## Self-Review (completed during planning)

- **Spec coverage:** product/customer/contact/price_list/price_list_item entities (Tasks 4/6/8/10/12) + CRUD (Tasks 5/7/9/11/13); GSTIN checksum + state derivation (Task 2, applied Task 7); `ValidationException`/422 (Task 1); cross-tenant 404 (Task 7); offset pagination + `PageResponse` (Task 3); XOR invariant (Tasks 12/13). Deferred items (money wire-format, price resolution, visibility, cursor pagination) are listed under Global Constraints and re-recorded in the handoff (Task 14). No spec requirement is unimplemented.
- **Placeholder scan:** every code step contains complete code; no TBD/"similar to"/"add validation" placeholders.
- **Type consistency:** `PageResponse.of`, `Gstin.parse/value/stateCode`, `StateCode.requireValid`, `ValidationException(field,msg)/getFields`, and each entity's constructor/`update` signatures are used identically across the tasks that produce and consume them.
