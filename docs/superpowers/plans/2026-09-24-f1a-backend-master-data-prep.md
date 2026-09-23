# F1a — Backend Master-Data Prep: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the master-data API so F1b's frontend can build search, stable per-field error
translation, and atomic price-list-item edits against a finished contract.

**Architecture:** Five independent extensions to existing controllers and services — `q` search behind
JPA Specifications (never hand-written tenant predicates), a shared sort allowlist, `fieldCodes` on
hand-thrown master-data errors, a price-list-item `PUT`, and contact validation with primary-contact
demotion — plus one Flyway migration for the indexes those queries need. Nothing is renamed or
removed, so the OpenAPI change is purely additive.

**Tech Stack:** Spring Boot 4.1.0, Java 25, Gradle, Postgres + Flyway, Hibernate `@TenantId` + RLS,
Testcontainers, MockMvc, JsonPath.

**Spec:** [`docs/superpowers/specs/2026-09-23-f1-master-data-design.md`](../specs/2026-09-23-f1-master-data-design.md) — Part 1 is what this plan implements. Read it first; every task cites its `F1-n` decisions.

## Global Constraints

- **Tenant isolation is structural.** Never hand-write `WHERE tenant_id = ?`. Scoping comes from
  Hibernate `@TenantId` + Postgres RLS. New entities would need `@TenantId` or `TenantScopingArchTest`
  fails the build; this plan adds none.
- **Money is `BigDecimal` in Java, `NUMERIC` in Postgres, a JSON string on the wire.** Compare with
  `compareTo`, never `equals` (`new BigDecimal("18")` does not `equals` `new BigDecimal("18.0")`).
- **Derived repository finders need `@Transactional(readOnly = true)` on the method.** Spring Data does
  not wrap them, so without it the RLS tenant GUC is unset and the query returns **zero rows** —
  challenge #8. This applies to every finder added in this plan.
- **Error codes are `SCREAMING_SNAKE`, never renamed or reused once shipped**, and every hand-thrown
  `ValidationException`/`ConflictException` a form displays must pass a code and gain a row in
  `docs/api/error-codes.md` **in the same change** (that file's own Rules 1 and 3).
- **`docs/api/openapi.yaml` is generated output.** Never hand-edit. `OpenApiSnapshotTest` compares it
  byte-for-byte, so it regenerates in the same commit as any contract change.
- **CI cannot vet this branch.** The workflow triggers on `push: [main]` and `pull_request`, and this
  repo has never used a PR for real work, so a feature-branch push fires nothing. Run the gates
  locally; the first CI signal arrives post-merge.
- **Baseline to preserve:** 688 backend tests, 0 failures, at `main`. Task 1 Step 1 confirms it before
  anything changes.
- **Commit as `divyam`** with plain `git commit`. Never mention Claude or AI, and add no
  `Co-Authored-By` trailer.
- **Log challenge-worthy problems** to `docs/superpowers/engineering-challenges.md` as part of the same
  change, numbering at commit time as the next free number — never reserved in advance (R38).

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V36__master_data_search_indexes.sql` | `pg_trgm` + the btree sort indexes and GIN trigram search indexes |
| `backend/src/main/java/com/easycrm/platform/web/SortAllowlist.java` | One reusable sort-field guard for every paged endpoint |
| `backend/src/main/java/com/easycrm/catalog/ProductSpecifications.java` | `active` + `q` predicates for products |
| `backend/src/main/java/com/easycrm/catalog/PriceListSpecifications.java` | `active` + `q` predicates for price lists |
| `backend/src/main/java/com/easycrm/catalog/web/dto/PriceListItemUpdateRequest.java` | Rate-only update DTO (no `productId`) |
| `backend/src/test/java/com/easycrm/platform/web/SortAllowlistTest.java` | Unit tests for the guard |
| `backend/src/test/java/com/easycrm/arch/MasterDataErrorCodesTest.java` | Build-failing guard: no fielded master-data error without a code |
| `backend/src/test/java/com/easycrm/platform/MasterDataSearchIndexTest.java` | Asserts the extension and indexes actually exist |

**Modified:**

| File | Change |
|---|---|
| `backend/src/main/java/com/easycrm/crm/CustomerSpecifications.java:13` | `filter(Boolean active)` → `filter(Boolean active, String q)` |
| `backend/src/main/java/com/easycrm/crm/CustomerService.java:59,118,123` | `list` takes `q` + sort guard; two `ValidationException`s gain codes; GSTIN conflict gains a code |
| `backend/src/main/java/com/easycrm/crm/web/CustomerController.java:35-39` | `q` request param |
| `backend/src/main/java/com/easycrm/catalog/ProductRepository.java` | add `JpaSpecificationExecutor<Product>` |
| `backend/src/main/java/com/easycrm/catalog/PriceListRepository.java` | add `JpaSpecificationExecutor<PriceList>` |
| `backend/src/main/java/com/easycrm/catalog/ProductService.java:55,86-98,42` | `list` takes `q` + sort guard; `validate` codes; SKU conflict code |
| `backend/src/main/java/com/easycrm/catalog/PriceListService.java` | `list` takes `q` + sort guard; duplicate-name conflict code |
| `backend/src/main/java/com/easycrm/catalog/PriceListItemService.java:59-76` | codes on XOR/range; new `update` method |
| `backend/src/main/java/com/easycrm/catalog/web/ProductController.java:36` · `PriceListController.java:35` · `PriceListItemController.java` | `q` params; new `PUT` mapping |
| `backend/src/main/java/com/easycrm/crm/web/dto/ContactRequest.java` | `@Email`, `@Size` matching column widths |
| `backend/src/main/java/com/easycrm/crm/ContactService.java:23,45` | primary-contact demotion |
| `backend/src/main/java/com/easycrm/crm/ContactRepository.java` | finder for sibling primaries |
| `backend/src/main/resources/application.yml` | `spring.data.web.pageable` size cap |
| `docs/api/error-codes.md` | new rows for every code added |
| `docs/api/openapi.yaml` | regenerated |

---

## Task 1: Baseline, migration, and index proof

Implements **F1-2**'s index half. The indexes land before the queries that need them so no task
introduces a seq-scanning query even transiently.

**Files:**
- Create: `backend/src/main/resources/db/migration/V36__master_data_search_indexes.sql`
- Create: `backend/src/test/java/com/easycrm/platform/MasterDataSearchIndexTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `idx_customer_business_name`, `idx_customer_business_name_trgm`, `idx_customer_gstin_trgm`, `idx_product_name`, `idx_product_name_trgm`, `idx_product_sku_trgm`, `idx_price_list_name`, `idx_price_list_name_trgm`, and the `pg_trgm` extension. Tasks 3 and 4 rely on these existing.

- [ ] **Step 1: Confirm the baseline before touching anything**

Run: `cd backend && ./gradlew clean check`

Expected: `BUILD SUCCESSFUL`, **688 tests, 0 failures**. Count by summing the `tests="…"` attribute
across every `build/test-results/test/*.xml` in both Gradle modules — do not trust a remembered
number, and do not proceed if it differs. If it differs, stop and report: something landed on `main`
that this plan has not accounted for.

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/easycrm/platform/MasterDataSearchIndexTest.java`:

```java
package com.easycrm.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.easycrm.support.IntegrationTest;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V36 is load-bearing for F1a's search: without the GIN trigram indexes every `q` query seq-scans,
 * and without the btree indexes every list sorts a whole tenant in memory. An index that silently
 * fails to be created is invisible from the application side, so assert it directly.
 */
@SpringBootTest
class MasterDataSearchIndexTest extends IntegrationTest {

    @Autowired
    DataSource dataSource;

    @Test
    void pgTrgmExtensionIsInstalled() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class);
        assertEquals(1, count, "pg_trgm must be installed by V36");
    }

    @Test
    void searchAndSortIndexesExist() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> expected = List.of(
                "idx_customer_business_name",
                "idx_customer_business_name_trgm",
                "idx_customer_gstin_trgm",
                "idx_product_name",
                "idx_product_name_trgm",
                "idx_product_sku_trgm",
                "idx_price_list_name",
                "idx_price_list_name_trgm");
        List<String> actual = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = current_schema()", String.class);
        for (String name : expected) {
            assertTrue(actual.contains(name), "missing index: " + name);
        }
    }

    @Test
    void trigramIndexesAreGin() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> ginIndexes = jdbc.queryForList(
                """
                SELECT c.relname FROM pg_class c
                JOIN pg_index i ON i.indexrelid = c.oid
                JOIN pg_am am ON am.oid = c.relam
                WHERE am.amname = 'gin'
                """,
                String.class);
        assertTrue(ginIndexes.contains("idx_customer_business_name_trgm"), "trgm index must be GIN");
        assertTrue(ginIndexes.contains("idx_product_sku_trgm"), "trgm index must be GIN");
    }
}
```

- [ ] **Step 3: Run it to confirm it fails**

Run: `cd backend && ./gradlew test --tests '*MasterDataSearchIndexTest*'`

Expected: FAIL — `pg_trgm must be installed by V36` and `missing index: idx_customer_business_name`.
This is a real red run, not a compile error: the test class compiles and the assertions are what fail.

- [ ] **Step 4: Write the migration**

Create `backend/src/main/resources/db/migration/V36__master_data_search_indexes.sql`:

```sql
-- F1a adds `q` substring search to the customer, product and price-list list endpoints, plus an
-- explicit default sort on each. Neither had any index behind it: `customer.business_name`,
-- `product.name`, `product.sku` and `price_list.name` are all unindexed today, so the default
-- ORDER BY would sort a whole tenant in memory and a leading-wildcard ILIKE would seq-scan.
--
-- House pattern, stated in V33's own comment: the slice adding the query adds the index.
--
-- Two kinds, because they serve different plans and neither substitutes for the other:
--   btree (tenant_id, <col>) -> the default ORDER BY, and prefix matches
--   GIN + gin_trgm_ops       -> ILIKE '%needle%', which btree cannot serve at all
--
-- pg_trgm is a new extension for this schema. Available on RDS (rds_superuser may create it) and
-- present in the Testcontainers postgres image.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Sort support. tenant_id leads every index in this schema: RLS adds `tenant_id = ...` to every
-- query, so a lone column index cannot serve the composite predicate.
CREATE INDEX idx_customer_business_name ON customer (tenant_id, business_name);
CREATE INDEX idx_product_name           ON product   (tenant_id, name);
CREATE INDEX idx_price_list_name        ON price_list (tenant_id, name);

-- Search support. lower(...) matches the LOWER(col) LIKE LOWER(...) predicate the Specifications
-- build; a trigram index on the raw column would not be used by that expression.
CREATE INDEX idx_customer_business_name_trgm ON customer   USING gin (lower(business_name) gin_trgm_ops);
CREATE INDEX idx_customer_gstin_trgm         ON customer   USING gin (lower(gstin) gin_trgm_ops);
CREATE INDEX idx_product_name_trgm           ON product    USING gin (lower(name) gin_trgm_ops);
CREATE INDEX idx_product_sku_trgm            ON product    USING gin (lower(sku) gin_trgm_ops);
CREATE INDEX idx_price_list_name_trgm        ON price_list  USING gin (lower(name) gin_trgm_ops);
```

- [ ] **Step 5: Run the test to confirm it passes**

Run: `cd backend && ./gradlew test --tests '*MasterDataSearchIndexTest*'`

Expected: PASS, 3 tests.

**If `CREATE EXTENSION` fails with a privileges error** (known stopping point 2 in the spec): the
Testcontainers postgres role should already be superuser, so first check whether the failure is
actually about the *schema* rather than the extension. Do not silently drop the extension and the
trigram indexes — that reverses F1-2. Stop, report, and take the spec's fallback (prefix matching)
only as a declared amendment.

- [ ] **Step 6: Check squawk accepts the migration**

Run: `cd backend && ./gradlew check --tests '*SupplyChainWorkflowTest*'` then confirm the squawk CI step's
rules locally per `backend/squawk.toml`:
`docker run --rm -v "$PWD/src/main/resources/db/migration:/m" sbdchd/squawk squawk /m/V36__master_data_search_indexes.sql`

Expected: no violations. `CREATE INDEX` without `CONCURRENTLY` is the likely flag. This schema has no
live traffic and all migrations run at the SP2 cutover, which is the same argument
`backend/squawk.toml` already records for `require-lock-timeout`. If squawk objects, add the rule to
`squawk.toml` **with that reasoning written next to it** — never with a bare exclusion.

- [ ] **Step 7: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/main/resources/db/migration/V36__master_data_search_indexes.sql \
        backend/src/test/java/com/easycrm/platform/MasterDataSearchIndexTest.java
git commit -m "feat(catalog,crm): index master-data search and sort columns (V36)

F1a's q search and explicit default sort have no index behind them today:
business_name, product.name, product.sku and price_list.name are all
unindexed. Adds btree (tenant_id, col) for the ORDER BY and GIN trigram
for the leading-wildcard ILIKE, which btree cannot serve.

pg_trgm is new to this schema. MasterDataSearchIndexTest asserts the
extension and every index exist, since a missing index is invisible from
the application side."
```

---

## Task 2: Sort allowlist and page-size cap

Implements **F1-3**. Closes a live 500 on user-controllable input across every paged list.

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/web/SortAllowlist.java`
- Create: `backend/src/test/java/com/easycrm/platform/web/SortAllowlistTest.java`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- Consumes: `com.easycrm.platform.error.ValidationException(String field, String message, String code)` — the three-arg constructor already exists.
- Produces: `SortAllowlist.require(Pageable pageable, Set<String> allowed)`, returning `void` and throwing `ValidationException` with field `sort` and code `SORT_INVALID`. Tasks 3 and 4 call it.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/platform/web/SortAllowlistTest.java`:

```java
package com.easycrm.platform.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.easycrm.platform.error.ValidationException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

class SortAllowlistTest {

    private static final Set<String> ALLOWED = Set.of("businessName", "createdAt");

    @Test
    void acceptsAnAllowedField() {
        assertDoesNotThrow(() ->
                SortAllowlist.require(PageRequest.of(0, 20, Sort.by("businessName")), ALLOWED));
    }

    @Test
    void acceptsAnUnsortedPageable() {
        assertDoesNotThrow(() -> SortAllowlist.require(PageRequest.of(0, 20), ALLOWED));
    }

    @Test
    void acceptsEveryFieldOfAMultiFieldSort() {
        assertDoesNotThrow(() -> SortAllowlist.require(
                PageRequest.of(0, 20, Sort.by("businessName").and(Sort.by("createdAt"))), ALLOWED));
    }

    @Test
    void rejectsAnUnknownField() {
        ValidationException e = assertThrows(
                ValidationException.class,
                () -> SortAllowlist.require(PageRequest.of(0, 20, Sort.by("creditDays")), ALLOWED));
        assertEquals("SORT_INVALID", e.getCodes().get("sort"));
    }

    @Test
    void rejectsWhenOnlyTheSecondFieldIsUnknown() {
        // A per-order loop that returns early after the first valid order would pass this wrongly.
        assertThrows(
                ValidationException.class,
                () -> SortAllowlist.require(
                        PageRequest.of(0, 20, Sort.by("businessName").and(Sort.by("nope"))), ALLOWED));
    }

    @Test
    void namesTheOffendingFieldButNotTheAllowlist() {
        // The message helps a developer; it must not enumerate internals back to a caller.
        ValidationException e = assertThrows(
                ValidationException.class,
                () -> SortAllowlist.require(PageRequest.of(0, 20, Sort.by("secretColumn")), ALLOWED));
        assertEquals("sort", e.getFields().keySet().iterator().next());
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd backend && ./gradlew test --tests '*SortAllowlistTest*'`

Expected: FAIL — compilation error, `SortAllowlist` does not exist. (A compile failure proves nothing
about the assertions; Step 4's run is the one that matters.)

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/easycrm/platform/web/SortAllowlist.java`:

```java
package com.easycrm.platform.web;

import com.easycrm.platform.error.ValidationException;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Rejects a `sort` field no endpoint declared.
 *
 * <p>Why this exists: `sort` binds straight into a Spring `Sort` with no validation, so an unknown
 * property used to reach JPA and throw there — a 500 on user-controllable input on every paged
 * endpoint. Binding succeeds, which is why none of the 400 bean-validation machinery is on this path;
 * see the spec's F1-3 note on the resulting 422.
 */
public final class SortAllowlist {

    private SortAllowlist() {}

    public static void require(Pageable pageable, Set<String> allowed) {
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                throw new ValidationException(
                        "sort", "unsupported sort field: " + order.getProperty(), "SORT_INVALID");
            }
        }
    }
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `cd backend && ./gradlew test --tests '*SortAllowlistTest*'`

Expected: PASS, 6 tests.

- [ ] **Step 5: Cap the page size**

In `backend/src/main/resources/application.yml`, under the existing `spring:` block, add:

```yaml
  data:
    web:
      pageable:
        default-page-size: 20
        max-page-size: 100
```

If a `spring.data` block already exists, merge into it rather than adding a second key — a duplicate
mapping key is a YAML error and Boot will fail to start.

- [ ] **Step 6: Register SORT_INVALID in the code registry**

In `docs/api/error-codes.md`, add to the **Domain codes** table:

```markdown
| `SORT_INVALID` | `sort` | `SortAllowlist.require` | a `sort` field the endpoint does not allow |
```

- [ ] **Step 7: Verify the whole suite still passes**

Run: `cd backend && ./gradlew check`

Expected: `BUILD SUCCESSFUL`. Test count is 688 + 6 = **694**.

- [ ] **Step 8: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/main/java/com/easycrm/platform/web/SortAllowlist.java \
        backend/src/test/java/com/easycrm/platform/web/SortAllowlistTest.java \
        backend/src/main/resources/application.yml docs/api/error-codes.md
git commit -m "feat(platform): allowlist sort fields and cap page size

sort bound straight into a Spring Sort with no validation, so an unknown
property reached JPA and threw there -- a 500 on user-controllable input
on every paged endpoint. Binding itself succeeds, so no 400 bean-
validation machinery is on this path; SortAllowlist throws the existing
ValidationException (422) with code SORT_INVALID instead of adding an
exception type for one call-site family. See spec F1-3.

Page size was effectively unbounded; capped at 100."
```

---

## Task 3: Customer search

Implements **F1-1** for customers, and wires Task 2's guard into the customer list.

**Files:**
- Modify: `backend/src/main/java/com/easycrm/crm/CustomerSpecifications.java`
- Modify: `backend/src/main/java/com/easycrm/crm/CustomerService.java:59-63`
- Modify: `backend/src/main/java/com/easycrm/crm/web/CustomerController.java:35-39`
- Test: `backend/src/test/java/com/easycrm/crm/web/CustomerControllerTest.java` (append)

**Interfaces:**
- Consumes: `SortAllowlist.require(Pageable, Set<String>)` from Task 2; `CustomerVisibility.page(Specification<Customer>, Pageable)`, which already exists and is what keeps scoping intact.
- Produces: `CustomerSpecifications.filter(Boolean active, String q)`; `CustomerService.list(Boolean active, String q, Pageable)`. Task 10 regenerates the contract from the controller signature.

- [ ] **Step 1: Write the failing tests**

Append to `backend/src/test/java/com/easycrm/crm/web/CustomerControllerTest.java` (match the existing
class's imports and `TestTokens`/`MockMvc` fields; read the file first):

```java
    @Test
    void searchMatchesBusinessNameSubstringCaseInsensitively() throws Exception {
        UUID tenant = UUID.randomUUID();
        String auth = "Bearer " + tokens.owner(tenant);
        createCustomer(auth, "Shri Ram Traders", "27AAPFU0939F1ZV");
        createCustomer(auth, "Gupta Hardware", null);

        // "ram" is in the MIDDLE of the name: a prefix-only implementation passes every other
        // assertion in this test and fails only this one, which is why the needle is not "shri".
        mvc.perform(get("/api/v1/customers?q=ram").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].businessName").value("Shri Ram Traders"));
    }

    @Test
    void searchMatchesGstin() throws Exception {
        UUID tenant = UUID.randomUUID();
        String auth = "Bearer " + tokens.owner(tenant);
        createCustomer(auth, "Shri Ram Traders", "27AAPFU0939F1ZV");

        mvc.perform(get("/api/v1/customers?q=AAPFU").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void searchReturnsEmptyRatherThanEverythingWhenNothingMatches() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        createCustomer(auth, "Gupta Hardware", null);

        // A predicate accidentally dropped from the conjunction shows all rows instead of none.
        mvc.perform(get("/api/v1/customers?q=zzzznomatch").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void blankSearchIsTreatedAsAbsent() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        createCustomer(auth, "Gupta Hardware", null);

        mvc.perform(get("/api/v1/customers?q=%20%20").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void searchComposesWithTheActiveFilter() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String id = createCustomer(auth, "Shri Ram Traders", null);
        mvc.perform(post("/api/v1/customers/" + id + "/deactivate").header("Authorization", auth))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/customers?q=ram&active=true").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/customers?q=ram&active=false").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void rejectsAnUnknownSortFieldWith422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());

        mvc.perform(get("/api/v1/customers?sort=creditDays,asc").header("Authorization", auth))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.sort").value("SORT_INVALID"));
    }

    @Test
    void acceptsAnAllowedSortField() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());

        mvc.perform(get("/api/v1/customers?sort=businessName,asc").header("Authorization", auth))
                .andExpect(status().isOk());
    }

    /** Returns the created customer's id. */
    private String createCustomer(String auth, String businessName, String gstin) throws Exception {
        String gstinJson = gstin == null ? "null" : "\"" + gstin + "\"";
        String body =
                """
                {"businessName":"%s","gstin":%s,"stateCode":%s,"source":"WALK_IN"}"""
                        .formatted(businessName, gstinJson, gstin == null ? "\"27\"" : "null");
        String response = mvc.perform(post("/api/v1/customers")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.id");
    }
```

- [ ] **Step 2: Run them to confirm they fail**

Run: `cd backend && ./gradlew test --tests '*CustomerControllerTest*'`

Expected: the four search tests FAIL because `q` is ignored (`totalElements` is 2 where 1 is expected,
and 1 where 0 is expected — the "returns everything" shape), and
`rejectsAnUnknownSortFieldWith422` FAILS with a 500 rather than a 422. That 500 **is** the live defect
F1-3 closes; note the actual status in the task report.

- [ ] **Step 3: Add the search predicate**

Replace the body of `backend/src/main/java/com/easycrm/crm/CustomerSpecifications.java`:

```java
package com.easycrm.crm;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class CustomerSpecifications {

    private CustomerSpecifications() {}

    /** AND-composes whichever filters are non-null. Tenant scoping comes from RLS, not here. */
    public static Specification<Customer> filter(Boolean active, String q) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (active != null) ps.add(cb.equal(root.get("active"), active));
            String needle = q == null ? null : q.trim();
            if (needle != null && !needle.isEmpty()) {
                // lower(col) LIKE lower(%needle%) matches V36's lower(col) gin_trgm_ops indexes.
                // An index on the raw column would not be used by this expression.
                String pattern = "%" + needle.toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("businessName")), pattern),
                        cb.like(cb.lower(root.get("gstin")), pattern)));
            }
            return cb.and(ps.toArray(new Predicate[0])); // empty -> always-true conjunction
        };
    }
}
```

Note the `cb.or(...)` is added to `ps` as a **single** predicate, so it is AND-ed with `active` rather
than flattened into the outer conjunction — flattening would turn `active=true AND (name OR gstin)`
into `active=true AND name OR gstin`, which Postgres reads as `(active AND name) OR gstin` and which
would leak deactivated rows. `searchComposesWithTheActiveFilter` is the test that catches it.

- [ ] **Step 4: Thread `q` through the service, with the sort guard**

In `backend/src/main/java/com/easycrm/crm/CustomerService.java`, add the imports
`java.util.Set` and `com.easycrm.platform.web.SortAllowlist`, then replace `list`:

```java
    /** Sort fields a client may name. Anything else is a 422, not a 500 from JPA (F1-3). */
    private static final Set<String> SORTABLE = Set.of("businessName", "createdAt", "updatedAt");

    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> list(Boolean active, String q, Pageable pageable) {
        SortAllowlist.require(pageable, SORTABLE);
        return PageResponse.of(customerVisibility
                .page(CustomerSpecifications.filter(active, q), pageable)
                .map(CustomerResponse::of));
    }
```

The search predicate goes **into** the Specification handed to `customerVisibility.page(...)`, which
is what keeps `CustomerVisibility`'s `assignedTo = me OR assignedTo IS NULL` restriction composed
around it. Never filter after the fact.

- [ ] **Step 5: Add the request parameter**

In `backend/src/main/java/com/easycrm/crm/web/CustomerController.java`, replace `list`:

```java
    @GetMapping
    public PageResponse<CustomerResponse> list(
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) @Size(max = 100) String q,
            @ParameterObject Pageable pageable) {
        return service.list(active, q, pageable);
    }
```

Add `import jakarta.validation.constraints.Size;` and annotate the class with `@Validated`
(`org.springframework.validation.annotation.Validated`) so the `@Size` on a request parameter is
actually enforced — on a method parameter it is inert without it.

- [ ] **Step 6: Run the tests to confirm they pass**

Run: `cd backend && ./gradlew test --tests '*CustomerControllerTest*'`

Expected: PASS, including all seven new tests.

- [ ] **Step 7: Prove the search respects visibility scoping**

Add to the same test class:

```java
    @Test
    void searchDoesNotSurfaceCustomersOutsideTheCallersVisibility() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        String ownerAuth = "Bearer " + tokens.owner(tenant);
        String execAuth = "Bearer " + tokens.salesExec(tenant);

        String body =
                """
                {"businessName":"Shri Ram Traders","stateCode":"27","source":"WALK_IN",
                 "assignedTo":"%s"}"""
                        .formatted(otherUser);
        mvc.perform(post("/api/v1/customers")
                        .header("Authorization", ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // The owner can see it; a SALES_EXEC it is not assigned to must not, and adding a search
        // predicate must not become a way around that.
        mvc.perform(get("/api/v1/customers?q=ram").header("Authorization", ownerAuth))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/customers?q=ram").header("Authorization", execAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }
```

**Before running:** check `TestTokens` for the actual method that mints a `SALES_EXEC` token and for
how `assignedTo` must be a real assignable user — `AssignableUsers.require` will reject a random UUID.
Read `backend/src/test/java/com/easycrm/support/TestTokens.java` and an existing
`CustomerVisibility*Test` for the established way to set this up, and follow it rather than inventing
one.

Run: `cd backend && ./gradlew test --tests '*CustomerControllerTest*'`
Expected: PASS.

- [ ] **Step 8: Prove the visibility assertion can fail**

Temporarily change `CustomerService.list` to bypass scoping — `customers.findAll(CustomerSpecifications.filter(active, q), pageable)`
instead of `customerVisibility.page(...)`. Run the test. **Expected: RED** on the `execAuth` assertion
(`totalElements` 1, expected 0). Revert immediately and re-run to green.

Record both runs in the task report. An assertion that cannot fail is the thing this project has been
bitten by fourteen times; a visibility assertion that cannot fail is the worst of them.

- [ ] **Step 9: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/main/java/com/easycrm/crm/ backend/src/test/java/com/easycrm/crm/
git commit -m "feat(crm): substring search on the customer list

q matches business_name or gstin, case-insensitively, as a substring --
a user looking for 'Shri Ram Traders' types 'ram', so prefix matching
would fail the common case. The predicate composes INSIDE the
Specification handed to CustomerVisibility.page, so a SALES_EXEC cannot
search their way past assigned_to scoping; a test proves that by
bypassing the scoping and going red.

The name/gstin OR is added as one predicate, not flattened, or
active=true AND (name OR gstin) would parse as (active AND name) OR
gstin and leak deactivated rows.

Also wires the F1-3 sort allowlist into this endpoint: an unknown sort
field was a 500 from JPA and is now a 422 with SORT_INVALID."
```

---

## Task 4: Product and price-list search

Implements **F1-1** for the two catalog lists. Separate from Task 3 because these repositories do not
yet support Specifications at all, which is its own reviewable change.

**Files:**
- Create: `backend/src/main/java/com/easycrm/catalog/ProductSpecifications.java`
- Create: `backend/src/main/java/com/easycrm/catalog/PriceListSpecifications.java`
- Modify: `backend/src/main/java/com/easycrm/catalog/ProductRepository.java`, `PriceListRepository.java`
- Modify: `backend/src/main/java/com/easycrm/catalog/ProductService.java:55-58`, `PriceListService.java`
- Modify: `backend/src/main/java/com/easycrm/catalog/web/ProductController.java:36`, `PriceListController.java:35`
- Test: `backend/src/test/java/com/easycrm/catalog/web/ProductControllerTest.java`, `PriceListControllerTest.java` (append)

**Interfaces:**
- Consumes: `SortAllowlist.require` from Task 2.
- Produces: `ProductSpecifications.filter(Boolean active, String q)`, `PriceListSpecifications.filter(Boolean active, String q)`, `ProductService.list(Boolean, String, Pageable)`, `PriceListService.list(Boolean, String, Pageable)`.

- [ ] **Step 1: Write the failing tests**

Append to `ProductControllerTest` (it already has a `createThenGet` test showing the create JSON shape
and a `tokens.owner(...)` pattern — reuse them):

```java
    @Test
    void searchMatchesNameSubstring() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        createProduct(auth, "SKU-100", "Hex Bolt M8");
        createProduct(auth, "SKU-200", "Washer");

        mvc.perform(get("/api/v1/products?q=bolt").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("SKU-100"));
    }

    @Test
    void searchMatchesSku() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        createProduct(auth, "SKU-100", "Hex Bolt M8");

        mvc.perform(get("/api/v1/products?q=100").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void searchReturnsEmptyWhenNothingMatches() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        createProduct(auth, "SKU-100", "Hex Bolt M8");

        mvc.perform(get("/api/v1/products?q=zzzznomatch").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void searchComposesWithActive() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String id = createProduct(auth, "SKU-100", "Hex Bolt M8");
        mvc.perform(post("/api/v1/products/" + id + "/deactivate").header("Authorization", auth))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/products?q=bolt&active=true").header("Authorization", auth))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/products?q=bolt&active=false").header("Authorization", auth))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void rejectsAnUnknownSortFieldWith422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());

        mvc.perform(get("/api/v1/products?sort=baseRate,asc").header("Authorization", auth))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.sort").value("SORT_INVALID"));
    }

    /** Returns the created product's id. */
    private String createProduct(String auth, String sku, String name) throws Exception {
        String body =
                """
                {"sku":"%s","name":"%s","hsnCode":"7318","uom":"PCS",
                 "gstRate":"18","baseRate":"12.50"}"""
                        .formatted(sku, name);
        String response = mvc.perform(post("/api/v1/products")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.id");
    }
```

Append the equivalent three to `PriceListControllerTest` — `searchMatchesNameSubstring`,
`searchReturnsEmptyWhenNothingMatches`, `rejectsAnUnknownSortFieldWith422` — using the create body
`{"name":"Monsoon 2026"}` and searching `q=monsoon`. Read that file first for its existing helpers.

`baseRate` is deliberately chosen as the rejected sort field: it is a real column, so a test that
passed only because the field did not exist would not catch a broken allowlist.

- [ ] **Step 2: Run them to confirm they fail**

Run: `cd backend && ./gradlew test --tests '*ProductControllerTest*' --tests '*PriceListControllerTest*'`

Expected: FAIL — `q` ignored (counts of 2 and 1 where 1 and 0 are expected), and a 500 rather than 422
on the sort test.

- [ ] **Step 3: Give both repositories Specification support**

In `backend/src/main/java/com/easycrm/catalog/ProductRepository.java`, add
`import org.springframework.data.jpa.repository.JpaSpecificationExecutor;` and extend it:

```java
public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {
```

Same for `PriceListRepository` with `JpaSpecificationExecutor<PriceList>`. Leave the existing derived
finders alone — `findBySku`, `findByName` and `findByActive` are still used elsewhere, and their
`@Transactional(readOnly = true)` annotations are load-bearing (challenge #8).

- [ ] **Step 4: Write both Specifications**

Create `backend/src/main/java/com/easycrm/catalog/ProductSpecifications.java`:

```java
package com.easycrm.catalog;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class ProductSpecifications {

    private ProductSpecifications() {}

    /** AND-composes whichever filters are non-null. Tenant scoping comes from RLS, not here. */
    public static Specification<Product> filter(Boolean active, String q) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (active != null) ps.add(cb.equal(root.get("active"), active));
            String needle = q == null ? null : q.trim();
            if (needle != null && !needle.isEmpty()) {
                String pattern = "%" + needle.toLowerCase() + "%";
                // One predicate, not two: flattening the OR into the outer AND would let a
                // matching sku resurrect a deactivated product.
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(root.get("sku")), pattern)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }
}
```

Create `backend/src/main/java/com/easycrm/catalog/PriceListSpecifications.java` identically, but with a
single `cb.like(cb.lower(root.get("name")), pattern)` predicate and `Specification<PriceList>`.

- [ ] **Step 5: Thread `q` through both services**

In `ProductService`, add imports `java.util.Set` and `com.easycrm.platform.web.SortAllowlist`, then
replace `list`:

```java
    private static final Set<String> SORTABLE = Set.of("name", "sku", "createdAt", "updatedAt");

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> list(Boolean active, String q, Pageable pageable) {
        SortAllowlist.require(pageable, SORTABLE);
        return PageResponse.of(
                products.findAll(ProductSpecifications.filter(active, q), pageable).map(ProductResponse::of));
    }
```

This replaces the old `findAll(pageable)` / `findByActive(active, pageable)` branch: the Specification
expresses both cases, and an always-true conjunction is what an absent filter produces.

Do the same in `PriceListService` with `SORTABLE = Set.of("name", "createdAt", "updatedAt")` and
`PriceListSpecifications.filter(active, q)`.

- [ ] **Step 6: Add both request parameters**

In `ProductController` and `PriceListController`, mirror Task 3 Step 5 exactly: add
`@RequestParam(required = false) @Size(max = 100) String q` as the second parameter, pass it through,
add `import jakarta.validation.constraints.Size;`, and annotate the class `@Validated`.

- [ ] **Step 7: Run the tests to confirm they pass**

Run: `cd backend && ./gradlew test --tests '*ProductControllerTest*' --tests '*PriceListControllerTest*'`

Expected: PASS.

- [ ] **Step 8: Confirm nothing else depended on the old list signatures**

Run: `cd backend && ./gradlew check`

Expected: `BUILD SUCCESSFUL`. If a call site fails to compile, fix the caller — do not add an
overload. A two-arg `list` left in place is how a caller silently keeps the unsearchable path alive.

- [ ] **Step 9: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/main/java/com/easycrm/catalog/ backend/src/test/java/com/easycrm/catalog/
git commit -m "feat(catalog): substring search on product and price-list lists

q matches product name or sku, and price-list name, case-insensitively.
Both repositories gain JpaSpecificationExecutor; the active/q predicates
move into Specifications, replacing the findAll-vs-findByActive branch,
since an absent filter is just an always-true conjunction.

The name/sku OR is one predicate rather than two, or a matching sku
would resurrect a deactivated product past active=true.

Both endpoints also take the F1-3 sort allowlist."
```

---

## Task 5: `fieldCodes` on customer errors

Implements **F1-4** for `crm`. Without this, F1b's four-level error resolution falls through to raw
English server prose on the customer form's most common failures.

**Files:**
- Modify: `backend/src/main/java/com/easycrm/crm/CustomerService.java:37,118,123`
- Modify: `docs/api/error-codes.md`
- Test: `backend/src/test/java/com/easycrm/crm/web/CustomerControllerTest.java` (append)

**Interfaces:**
- Consumes: `ValidationException(String field, String message, String code)` and `ConflictException(String message, Map<String,Object> fields, Map<String,String> fieldCodes)` — both already exist.
- Produces: the codes `STATE_CODE_GSTIN_MISMATCH` (reused), `STATE_CODE_REQUIRED`, `GSTIN_DUPLICATE`.

- [ ] **Step 1: Write the failing tests**

Append to `CustomerControllerTest`:

```java
    @Test
    void stateCodeDivergingFromGstinCarriesTheSharedMismatchCode() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String body = """
            {"businessName":"Shri Ram Traders","gstin":"27AAPFU0939F1ZV",
             "stateCode":"29","source":"WALK_IN"}""";

        mvc.perform(post("/api/v1/customers")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                // Reused, not a new code: AuthService.signup already emits this for the same
                // meaning, and one meaning must not acquire two codes (error-codes.md rule 1).
                .andExpect(jsonPath("$.error.fieldCodes.stateCode").value("STATE_CODE_GSTIN_MISMATCH"));
    }

    @Test
    void missingStateCodeWithoutGstinCarriesItsOwnCode() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String body = """
            {"businessName":"Gupta Hardware","source":"WALK_IN"}""";

        mvc.perform(post("/api/v1/customers")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.stateCode").value("STATE_CODE_REQUIRED"));
    }

    @Test
    void duplicateGstinConflictCarriesAFieldAndACode() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        createCustomer(auth, "Shri Ram Traders", "27AAPFU0939F1ZV");

        String body = """
            {"businessName":"Another Firm","gstin":"27AAPFU0939F1ZV","source":"WALK_IN"}""";

        mvc.perform(post("/api/v1/customers")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                // A 409 with prose only cannot be attached to a form field at all: the frontend
                // has nothing to key a message or a focus target on.
                .andExpect(jsonPath("$.error.fieldCodes.gstin").value("GSTIN_DUPLICATE"));
    }
```

- [ ] **Step 2: Run them to confirm they fail**

Run: `cd backend && ./gradlew test --tests '*CustomerControllerTest*'`

Expected: FAIL — `$.error.fieldCodes` is absent in all three (`ApiError` is `@JsonInclude(NON_NULL)`,
so the key is omitted entirely rather than being null). The statuses (422, 422, 409) already pass;
only the codes are missing.

- [ ] **Step 3: Add the codes**

In `CustomerService`, add `import java.util.Map;` and make three edits.

`resolveGstinAndState`, the mismatch throw (was line 118):

```java
                throw new ValidationException(
                        "stateCode", "must match the GSTIN state code", "STATE_CODE_GSTIN_MISMATCH");
```

The missing-state throw (was line 123):

```java
            throw new ValidationException(
                    "stateCode", "state code is required when GSTIN is absent", "STATE_CODE_REQUIRED");
```

`create`, the duplicate-GSTIN conflict (was line 37):

```java
            customers.findByGstin(r.gstin()).ifPresent(c -> {
                throw new ConflictException(
                        "customer with this GSTIN already exists",
                        Map.of("gstin", "customer with this GSTIN already exists"),
                        Map.of("gstin", "GSTIN_DUPLICATE"));
            });
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `cd backend && ./gradlew test --tests '*CustomerControllerTest*'`

Expected: PASS.

- [ ] **Step 5: Register the new codes**

In `docs/api/error-codes.md`'s **Domain codes** table add:

```markdown
| `STATE_CODE_REQUIRED` | `stateCode` | `CustomerService.resolveGstinAndState` | no GSTIN supplied and no state code to fall back on |
| `GSTIN_DUPLICATE` | `gstin` | `CustomerService.create` | another customer in this tenant already has this GSTIN |
```

And extend the existing `STATE_CODE_GSTIN_MISMATCH` row's "Thrown by" cell to
`AuthService.signup`, `CustomerService.resolveGstinAndState` — the table is the registry, so a second
thrower belongs in it.

- [ ] **Step 6: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/main/java/com/easycrm/crm/CustomerService.java \
        backend/src/test/java/com/easycrm/crm/web/CustomerControllerTest.java \
        docs/api/error-codes.md
git commit -m "feat(crm): stable fieldCodes on customer validation and conflict errors

The customer form's three most common failures arrived as English prose
with no code, so a client could not translate them. STATE_CODE_GSTIN_
MISMATCH is reused from AuthService.signup rather than duplicated --
one meaning, one code. The duplicate-GSTIN 409 previously carried no
field at all, so a frontend had nothing to attach the message or the
focus target to."
```

---

## Task 6: `fieldCodes` on catalog errors

Implements **F1-4** for `catalog`. Same shape as Task 5, different services.

**Files:**
- Modify: `backend/src/main/java/com/easycrm/catalog/ProductService.java:42,86-98`
- Modify: `backend/src/main/java/com/easycrm/catalog/PriceListService.java`
- Modify: `backend/src/main/java/com/easycrm/catalog/PriceListItemService.java:35,59-76`
- Modify: `docs/api/error-codes.md`
- Test: `ProductControllerTest`, `PriceListControllerTest`, `PriceListItemControllerTest` (append)

**Interfaces:**
- Produces: `HSN_CODE_INVALID`, `GST_RATE_INVALID`, `BASE_RATE_NEGATIVE`, `SKU_DUPLICATE`, `NAME_DUPLICATE`, `RATE_RULE_XOR`, `OVERRIDE_RATE_NEGATIVE`, `DISCOUNT_PCT_RANGE`, `PRODUCT_DUPLICATE`.

- [ ] **Step 1: Write the failing tests**

`ProductControllerTest` already has `rejectsDisallowedGstRateWith422` — read it and extend it with a
`fieldCodes` assertion rather than duplicating it. Then append:

```java
    @Test
    void multiFieldValidationCarriesACodePerField() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String body = """
            {"sku":"SKU-1","name":"Bolt","hsnCode":"12","uom":"PCS",
             "gstRate":"7","baseRate":"-1"}""";

        // All three rules fail at once: ProductService.validate accumulates into one map, so a
        // per-field codes map is the only shape that can describe this response.
        mvc.perform(post("/api/v1/products")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.hsnCode").value("HSN_CODE_INVALID"))
                .andExpect(jsonPath("$.error.fieldCodes.gstRate").value("GST_RATE_INVALID"))
                .andExpect(jsonPath("$.error.fieldCodes.baseRate").value("BASE_RATE_NEGATIVE"));
    }

    @Test
    void duplicateSkuConflictCarriesAFieldAndACode() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        createProduct(auth, "SKU-DUP", "Bolt");

        String body = """
            {"sku":"SKU-DUP","name":"Other","hsnCode":"7318","uom":"PCS",
             "gstRate":"18","baseRate":"1"}""";

        mvc.perform(post("/api/v1/products")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.fieldCodes.sku").value("SKU_DUPLICATE"));
    }
```

In `PriceListItemControllerTest`, append (read the file for its existing setup of a price list and a
product — reuse it, do not re-derive it):

```java
    @Test
    void neitherRateNorDiscountCarriesTheXorCode() throws Exception {
        // ... create a price list and a product per this class's existing helper ...
        mvc.perform(post("/api/v1/price-lists/" + priceListId + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.overrideRate").value("RATE_RULE_XOR"));
    }

    @Test
    void bothRateAndDiscountCarriesTheSameXorCode() throws Exception {
        mvc.perform(post("/api/v1/price-lists/" + priceListId + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId
                                + "\",\"overrideRate\":\"10\",\"discountPct\":\"5\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.overrideRate").value("RATE_RULE_XOR"));
    }

    @Test
    void discountOutOfRangeCarriesItsOwnCode() throws Exception {
        mvc.perform(post("/api/v1/price-lists/" + priceListId + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"discountPct\":\"101\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.discountPct").value("DISCOUNT_PCT_RANGE"));
    }

    @Test
    void negativeOverrideRateCarriesItsOwnCode() throws Exception {
        mvc.perform(post("/api/v1/price-lists/" + priceListId + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"overrideRate\":\"-1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.overrideRate").value("OVERRIDE_RATE_NEGATIVE"));
    }

    @Test
    void duplicateProductInListCarriesAFieldAndACode() throws Exception {
        // add the product once, then again
        mvc.perform(post("/api/v1/price-lists/" + priceListId + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"overrideRate\":\"10\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/price-lists/" + priceListId + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"overrideRate\":\"12\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.fieldCodes.productId").value("PRODUCT_DUPLICATE"));
    }
```

And in `PriceListControllerTest`, a duplicate-name test asserting
`$.error.fieldCodes.name` is `NAME_DUPLICATE`.

- [ ] **Step 2: Run them to confirm they fail**

Run: `cd backend && ./gradlew test --tests '*catalog*'`

Expected: FAIL — every `fieldCodes` path absent.

- [ ] **Step 3: Add codes to `ProductService`**

Replace `validate` and the SKU conflict. `validate` accumulates a multi-field map, so it needs the
two-map `ValidationException(fields, codes)` constructor rather than the three-arg single-field one:

```java
    private void validate(String hsnCode, BigDecimal gstRate, BigDecimal baseRate) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, String> codes = new LinkedHashMap<>();
        if (hsnCode != null && !hsnCode.isBlank() && !hsnCode.matches("\\d{4}|\\d{6}|\\d{8}")) {
            errors.put("hsnCode", "HSN code must be 4, 6, or 8 digits");
            codes.put("hsnCode", "HSN_CODE_INVALID");
        }
        if (gstRate != null && !isAllowedRate(gstRate)) {
            errors.put("gstRate", "GST rate must be one of 0, 0.25, 3, 5, 12, 18, 28");
            codes.put("gstRate", "GST_RATE_INVALID");
        }
        if (baseRate != null && baseRate.compareTo(BigDecimal.ZERO) < 0) {
            errors.put("baseRate", "base rate must not be negative");
            codes.put("baseRate", "BASE_RATE_NEGATIVE");
        }
        if (!errors.isEmpty()) throw new ValidationException(errors, codes);
    }
```

```java
        products.findBySku(req.sku()).ifPresent(p -> {
            throw new ConflictException(
                    "product with this SKU already exists",
                    Map.of("sku", "product with this SKU already exists"),
                    Map.of("sku", "SKU_DUPLICATE"));
        });
```

`LinkedHashMap` for both, deliberately: `ValidationException` copies into a `LinkedHashMap` to keep
serialized key order deterministic, and building the input from an immutable `Map.of` would randomize
it per JVM boot. That reasoning is in `ConflictException`'s own javadoc.

- [ ] **Step 4: Add codes to `PriceListItemService` and `PriceListService`**

`PriceListItemService` — each throw is single-field, so use the three-arg constructor:

```java
    private void validateXor(PriceListItemRequest req) {
        boolean hasRate = req.overrideRate() != null;
        boolean hasDiscount = req.discountPct() != null;
        if (hasRate == hasDiscount) { // both set OR both null
            throw new ValidationException(
                    "overrideRate", "exactly one of overrideRate or discountPct must be set", "RATE_RULE_XOR");
        }
    }

    private void validateRange(PriceListItemRequest req) {
        if (req.overrideRate() != null && req.overrideRate().compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationException(
                    "overrideRate", "override rate must not be negative", "OVERRIDE_RATE_NEGATIVE");
        }
        if (req.discountPct() != null
                && (req.discountPct().compareTo(BigDecimal.ZERO) < 0
                        || req.discountPct().compareTo(new BigDecimal("100")) > 0)) {
            throw new ValidationException(
                    "discountPct", "discount percent must be between 0 and 100", "DISCOUNT_PCT_RANGE");
        }
    }
```

And the duplicate-product conflict in `add`:

```java
        items.findByPriceListIdAndProductId(priceListId, req.productId()).ifPresent(i -> {
            throw new ConflictException(
                    "this product is already priced in this list",
                    Map.of("productId", "this product is already priced in this list"),
                    Map.of("productId", "PRODUCT_DUPLICATE"));
        });
```

In `PriceListService`, give the duplicate-name `ConflictException` the same treatment with field
`name` and code `NAME_DUPLICATE`. Read the file — there are **two** such throws (create and rename);
both need it.

- [ ] **Step 5: Run the tests to confirm they pass**

Run: `cd backend && ./gradlew test --tests '*catalog*'`

Expected: PASS.

- [ ] **Step 6: Register all nine codes**

Add a row per code to `docs/api/error-codes.md`'s Domain codes table, each naming its field and its
throwing method.

- [ ] **Step 7: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/main/java/com/easycrm/catalog/ backend/src/test/java/com/easycrm/catalog/ \
        docs/api/error-codes.md
git commit -m "feat(catalog): stable fieldCodes on product and price-list errors

Nine hand-thrown errors carried English prose only. ProductService.
validate accumulates several failures at once, so it uses the two-map
ValidationException constructor and builds LinkedHashMaps -- an
immutable Map.of would randomize serialized key order per JVM boot, the
reason ConflictException's javadoc already gives.

The three duplicate conflicts (SKU, price-list name, product-in-list)
previously carried no field, so a form had nothing to attach them to."
```

---

## Task 7: The guard that keeps `fieldCodes` from regressing

Implements **F1-4**'s build-failing half. Tasks 5 and 6 fix today's call sites; this stops tomorrow's
from regressing, which is the only reason the fix stays fixed.

**Files:**
- Create: `backend/src/test/java/com/easycrm/arch/MasterDataErrorCodesTest.java`

**Interfaces:**
- Consumes: the codes added in Tasks 5 and 6.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Read how the existing arch tests are written**

Read `backend/src/test/java/com/easycrm/arch/` — in particular whichever test enforces the visibility
or module-direction rules — and note two things this repo insists on: ArchUnit rules live here, and
**every rule has a non-vacuity assertion beside it** so a rule that matches nothing fails rather than
passing silently.

An ArchUnit rule cannot see constructor *arguments*, so a pure "did they pass a code" rule is not
expressible that way. Use source inspection instead: read the four service files and assert on their
text. State that limitation in the test's javadoc so the next person does not try to convert it.

- [ ] **Step 2: Write the guard**

Create `backend/src/test/java/com/easycrm/arch/MasterDataErrorCodesTest.java`:

```java
package com.easycrm.arch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every hand-thrown master-data validation or conflict error must carry a reason code, so a client
 * can translate it instead of showing English prose (docs/api/error-codes.md rule 3).
 *
 * <p>This is source inspection, not ArchUnit, and deliberately so: ArchUnit sees types and call
 * edges, never constructor <em>arguments</em>, so it cannot distinguish
 * {@code new ValidationException(field, message)} from
 * {@code new ValidationException(field, message, CODE)}. Do not "upgrade" this to an ArchUnit rule;
 * it would silently stop checking anything.
 */
class MasterDataErrorCodesTest {

    private static final List<Path> GUARDED = List.of(
            Path.of("src/main/java/com/easycrm/crm/CustomerService.java"),
            Path.of("src/main/java/com/easycrm/catalog/ProductService.java"),
            Path.of("src/main/java/com/easycrm/catalog/PriceListService.java"),
            Path.of("src/main/java/com/easycrm/catalog/PriceListItemService.java"));

    /** `new ValidationException(` or `new ConflictException(` through to the closing paren. */
    private static final Pattern THROW_SITE =
            Pattern.compile("new (ValidationException|ConflictException)\\s*\\(([^;]*?)\\)\\s*;", Pattern.DOTALL);

    @Test
    void everyGuardedFileExists() {
        // Non-vacuity: a renamed or moved service must fail loudly, not quietly stop being checked.
        for (Path p : GUARDED) {
            assertTrue(Files.exists(p), "guarded file is missing -- update GUARDED: " + p);
        }
    }

    @Test
    void theGuardActuallyFindsThrowSites() {
        // Non-vacuity: if the regex stops matching, every other assertion here passes for free.
        int found = 0;
        for (Path p : GUARDED) {
            found += countMatches(read(p));
        }
        assertTrue(found >= 9, "expected to find the known throw sites, found " + found);
    }

    @Test
    void noFieldedErrorIsThrownWithoutACode() {
        List<String> offenders = new ArrayList<>();
        for (Path p : GUARDED) {
            Matcher m = THROW_SITE.matcher(read(p));
            while (m.find()) {
                String args = m.group(2);
                boolean carriesACode = args.contains("_") && args.matches("(?s).*\"[A-Z][A-Z0-9_]+\".*");
                if (!carriesACode) {
                    offenders.add(p.getFileName() + ": new " + m.group(1) + "(" + oneLine(args) + ")");
                }
            }
        }
        assertTrue(offenders.isEmpty(), "master-data errors thrown without a reason code:\n" + String.join("\n", offenders));
    }

    @Test
    void everyCodeThrownIsRegistered() throws IOException {
        String registry = Files.readString(Path.of("../docs/api/error-codes.md"));
        Pattern code = Pattern.compile("\"([A-Z][A-Z0-9_]{3,})\"");
        List<String> unregistered = new ArrayList<>();
        for (Path p : GUARDED) {
            Matcher m = THROW_SITE.matcher(read(p));
            while (m.find()) {
                Matcher c = code.matcher(m.group(2));
                while (c.find()) {
                    if (!registry.contains("`" + c.group(1) + "`")) unregistered.add(c.group(1));
                }
            }
        }
        assertTrue(unregistered.isEmpty(), "codes thrown but absent from docs/api/error-codes.md: " + unregistered);
    }

    @Test
    void theRegistryCheckCanFail() {
        // Non-vacuity for the assertion above: prove the registry is actually being read and that a
        // bogus code would not be found in it.
        assertFalse(readRegistryQuietly().contains("`DEFINITELY_NOT_A_REAL_CODE`"));
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + p, e);
        }
    }

    private static String readRegistryQuietly() {
        return read(Path.of("../docs/api/error-codes.md"));
    }

    private static int countMatches(String src) {
        Matcher m = THROW_SITE.matcher(src);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
```

**Two things to verify while implementing, not assume:** the working directory Gradle uses for the
`backend` module's tests (the relative paths above assume it is `backend/`, which is why the registry
path is `../docs/api/error-codes.md` — confirm it and fix the paths if not), and that
`countMatches`'s `>= 9` floor matches what Tasks 5 and 6 actually produced.

- [ ] **Step 3: Run it — expect green**

Run: `cd backend && ./gradlew test --tests '*MasterDataErrorCodesTest*'`

Expected: PASS, 5 tests. Green on the first run is correct here: Tasks 5 and 6 already fixed every
call site. Which is exactly why Step 4 is not optional.

- [ ] **Step 4: Prove the guard bites — three separate mutations**

This is the step that makes the gate worth having. Do all three, one at a time, reverting between:

1. In `PriceListItemService.validateXor`, drop the third argument:
   `new ValidationException("overrideRate", "exactly one of ...")`.
   Run the test. **Expected: RED** on `noFieldedErrorIsThrownWithoutACode`, naming that line. Revert.
2. In `CustomerService`, change `"GSTIN_DUPLICATE"` to `"GSTIN_DUPE"` (a code not in the registry).
   Run. **Expected: RED** on `everyCodeThrownIsRegistered`. Revert.
3. Break the regex — change `THROW_SITE`'s pattern to match something that does not occur, e.g.
   `new NoSuchException\\(`. Run. **Expected: RED** on `theGuardActuallyFindsThrowSites`, not a
   silent pass of the other assertions. Revert.

Record all three red runs, verbatim, in the task report. If any of the three stays green, the guard is
decoration — fix it before moving on.

- [ ] **Step 5: Verify the whole suite**

Run: `cd backend && ./gradlew clean check`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/test/java/com/easycrm/arch/MasterDataErrorCodesTest.java
git commit -m "test(arch): fail the build on a master-data error thrown without a code

Tasks fixing today's call sites do not stop tomorrow's from regressing,
and a missing fieldCode is invisible from the backend side -- it shows
up as English text in a Hindi UI, months later.

Source inspection rather than ArchUnit on purpose: ArchUnit sees types
and call edges, never constructor arguments, so it cannot tell
ValidationException(field, message) from (field, message, CODE). Three
non-vacuity assertions guard the guard, and all three were proven red by
mutation: a dropped code argument, an unregistered code, and a regex
that matches nothing."
```

---

## Task 8: Price-list-item update

Implements **F1-5**. Today changing a rate is DELETE + POST: two requests, not atomic, no idempotency
key, and a crash between them loses the line.

**Files:**
- Create: `backend/src/main/java/com/easycrm/catalog/web/dto/PriceListItemUpdateRequest.java`
- Modify: `backend/src/main/java/com/easycrm/catalog/PriceListItemService.java`
- Modify: `backend/src/main/java/com/easycrm/catalog/web/PriceListItemController.java`
- Test: `backend/src/test/java/com/easycrm/catalog/web/PriceListItemControllerTest.java` (append)

**Interfaces:**
- Consumes: `PriceListItem`'s existing mutator — **read `PriceListItem.java` first**; if it has no rate setter, add one following the shape `Product.update(...)` and `Customer.update(...)` already use.
- Produces: `PriceListItemService.update(UUID priceListId, UUID itemId, PriceListItemUpdateRequest req)` returning `PriceListItemResponse`.

- [ ] **Step 1: Write the failing tests**

Append to `PriceListItemControllerTest`:

```java
    @Test
    void updatesTheRateInOneRequest() throws Exception {
        // create an item with overrideRate 10 using this class's existing helpers
        mvc.perform(put("/api/v1/price-lists/" + priceListId + "/items/" + itemId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrideRate\":\"15.50\"}"))
                .andExpect(status().isOk())
                // Money is a JSON string on the wire, and 15.50 must not come back as 15.5.
                .andExpect(jsonPath("$.overrideRate").value("15.50"));
    }

    @Test
    void switchesFromRateToDiscount() throws Exception {
        mvc.perform(put("/api/v1/price-lists/" + priceListId + "/items/" + itemId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"discountPct\":\"12\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountPct").value("12"))
                // The XOR is an invariant of the stored row, not just of the request: the old
                // overrideRate must be cleared, or the row now violates it.
                .andExpect(jsonPath("$.overrideRate").doesNotExist());
    }

    @Test
    void updateEnforcesTheXorRule() throws Exception {
        mvc.perform(put("/api/v1/price-lists/" + priceListId + "/items/" + itemId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrideRate\":\"10\",\"discountPct\":\"5\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.overrideRate").value("RATE_RULE_XOR"));
    }

    @Test
    void updateEnforcesTheRangeRules() throws Exception {
        mvc.perform(put("/api/v1/price-lists/" + priceListId + "/items/" + itemId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"discountPct\":\"101\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.discountPct").value("DISCOUNT_PCT_RANGE"));
    }

    @Test
    void updateRejectsAnItemBelongingToAnotherPriceList() throws Exception {
        // create a SECOND price list, then address the first list's item through it
        mvc.perform(put("/api/v1/price-lists/" + otherPriceListId + "/items/" + itemId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrideRate\":\"1\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateIsInvisibleAcrossTenants() throws Exception {
        String otherTenantAuth = "Bearer " + tokens.owner(UUID.randomUUID());
        mvc.perform(put("/api/v1/price-lists/" + priceListId + "/items/" + itemId)
                        .header("Authorization", otherTenantAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrideRate\":\"1\"}"))
                .andExpect(status().isNotFound());
    }
```

Fill in the setup using this class's existing helpers — read it first. Add
`import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;` if the
wildcard import is not already in place.

- [ ] **Step 2: Run them to confirm they fail**

Run: `cd backend && ./gradlew test --tests '*PriceListItemControllerTest*'`

Expected: FAIL — 405 Method Not Allowed on every one, since no `PUT` mapping exists.

- [ ] **Step 3: Create the update DTO**

Create `backend/src/main/java/com/easycrm/catalog/web/dto/PriceListItemUpdateRequest.java`:

```java
package com.easycrm.catalog.web.dto;

import java.math.BigDecimal;

/**
 * Rate-only update. No {@code productId}: changing which product a line refers to is a delete plus a
 * create, not an edit — the same reasoning that keeps {@code sku} out of ProductUpdateRequest.
 *
 * <p>Exactly one of the two must be set; enforced in PriceListItemService, not by annotations,
 * because the rule is a relationship between fields rather than a constraint on either one.
 */
public record PriceListItemUpdateRequest(BigDecimal overrideRate, BigDecimal discountPct) {}
```

- [ ] **Step 4: Add the service method**

The existing `validateXor`/`validateRange` take `PriceListItemRequest`, so they cannot be reused
as-is. Change both to take the two `BigDecimal`s directly and have the existing `add` path pass
`req.overrideRate(), req.discountPct()` — one refactor, both callers served, no duplicated rule:

```java
    @Transactional
    public PriceListItemResponse update(UUID priceListId, UUID itemId, PriceListItemUpdateRequest req) {
        validateXor(req.overrideRate(), req.discountPct());
        validateRange(req.overrideRate(), req.discountPct());
        PriceListItem i = items.findById(itemId).orElseThrow(() -> new NotFoundException("price list item not found"));
        // Same gate as delete(): an item addressed through the wrong parent is "not found", never
        // silently updated. Cross-tenant rows are already invisible to RLS.
        if (!i.getPriceListId().equals(priceListId)) {
            throw new NotFoundException("price list item not found");
        }
        i.updateRates(req.overrideRate(), req.discountPct());
        return PriceListItemResponse.of(i);
    }
```

Then add `updateRates` to `PriceListItem` — setting **both** fields, so switching from a rate to a
discount clears the rate rather than leaving a row that violates the XOR:

```java
    public void updateRates(BigDecimal overrideRate, BigDecimal discountPct) {
        this.overrideRate = overrideRate;
        this.discountPct = discountPct;
    }
```

Read `PriceListItem.java` first and match its existing field names and style.

- [ ] **Step 5: Add the mapping**

In `PriceListItemController`:

```java
    @PutMapping("/{itemId}")
    public PriceListItemResponse update(
            @PathVariable UUID priceListId,
            @PathVariable UUID itemId,
            @Valid @RequestBody PriceListItemUpdateRequest req) {
        return service.update(priceListId, itemId, req);
    }
```

- [ ] **Step 6: Run the tests to confirm they pass**

Run: `cd backend && ./gradlew test --tests '*PriceListItemControllerTest*'`

Expected: PASS.

- [ ] **Step 7: Prove the wrong-parent gate bites**

Delete the `if (!i.getPriceListId().equals(priceListId))` block. Run
`updateRejectsAnItemBelongingToAnotherPriceList`. **Expected: RED** — a 200 where 404 is expected.
Restore it and re-run to green. Record both.

This is the assertion that stops one price list's rates being editable through another's URL, so it
must be shown to fail.

- [ ] **Step 8: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/main/java/com/easycrm/catalog/ backend/src/test/java/com/easycrm/catalog/
git commit -m "feat(catalog): PUT a price-list item's rate

Changing a rate meant DELETE then POST: two requests, not atomic, no
idempotency key, and a crash between them lost the line. PUT takes rate
fields only -- changing which product a line refers to is a delete plus
a create, the same reasoning that keeps sku out of ProductUpdateRequest.

updateRates sets both fields so switching from a rate to a discount
clears the rate; otherwise the stored row would violate the XOR the
request had just satisfied. validateXor/validateRange now take the two
values rather than a request, so add and update share one rule.

The wrong-parent 404 gate was proven red by deleting it."
```

---

## Task 9: Contact validation and primary-contact demotion

Implements **F1-6**. `ContactRequest` currently constrains `name` only, so F1b's client-side rules
would have no server mirror, and `isPrimary` has no uniqueness enforcement at all.

**Files:**
- Modify: `backend/src/main/java/com/easycrm/crm/web/dto/ContactRequest.java`
- Modify: `backend/src/main/java/com/easycrm/crm/ContactRepository.java`
- Modify: `backend/src/main/java/com/easycrm/crm/ContactService.java:22-34,44-55`
- Test: `backend/src/test/java/com/easycrm/crm/web/ContactControllerTest.java` (append)

**Interfaces:**
- Consumes: `ContactRepository.findByCustomerId(UUID)`, which already exists.
- Produces: `ContactRepository.findByCustomerIdAndIsPrimaryTrue(UUID)`; demotion behaviour on both `add` and `update`.

- [ ] **Step 1: Write the failing tests**

Append to `ContactControllerTest`:

```java
    @Test
    void rejectsAMalformedEmail() throws Exception {
        // Bean validation, so this is a 400 with an automatic EMAIL code -- not a 422.
        mvc.perform(post("/api/v1/customers/" + customerId + "/contacts")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ramesh\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldCodes.email").value("EMAIL"));
    }

    @Test
    void rejectsAnOverlongPhone() throws Exception {
        mvc.perform(post("/api/v1/customers/" + customerId + "/contacts")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ramesh\",\"phone\":\"012345678901234567890\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldCodes.phone").value("SIZE"));
    }

    @Test
    void acceptsATwentyCharacterPhone() throws Exception {
        // Boundary in the allowed direction: the column is VARCHAR(20), so 20 must pass. A test
        // that only checks 21 fails cannot tell max=20 from max=19.
        mvc.perform(post("/api/v1/customers/" + customerId + "/contacts")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ramesh\",\"phone\":\"01234567890123456789\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void addingAPrimaryContactDemotesTheExistingOne() throws Exception {
        String first = createContact(auth, customerId, "Ramesh", true);
        String second = createContact(auth, customerId, "Suresh", true);

        mvc.perform(get("/api/v1/customers/" + customerId + "/contacts").header("Authorization", auth))
                .andExpect(status().isOk())
                // Exactly one primary, and it is the newest. Without demotion this is 2.
                .andExpect(jsonPath("$[?(@.isPrimary == true)].length()").value(1))
                .andExpect(jsonPath("$[?(@.isPrimary == true)][0].name").value("Suresh"));
    }

    @Test
    void promotingViaUpdateDemotesTheExistingPrimary() throws Exception {
        String first = createContact(auth, customerId, "Ramesh", true);
        String second = createContact(auth, customerId, "Suresh", false);

        mvc.perform(put("/api/v1/customers/" + customerId + "/contacts/" + second)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Suresh\",\"isPrimary\":true}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/customers/" + customerId + "/contacts").header("Authorization", auth))
                .andExpect(jsonPath("$[?(@.isPrimary == true)].length()").value(1))
                .andExpect(jsonPath("$[?(@.isPrimary == true)][0].name").value("Suresh"));
    }

    @Test
    void demotionDoesNotReachAnotherCustomersContacts() throws Exception {
        // The bug this prevents: demoting "every primary contact" instead of "every primary
        // contact of THIS customer" silently unsets a colleague's data, tenant-wide.
        String otherCustomerId = createCustomer(auth, "Second Firm");
        createContact(auth, otherCustomerId, "Untouched", true);
        createContact(auth, customerId, "Ramesh", true);

        mvc.perform(get("/api/v1/customers/" + otherCustomerId + "/contacts").header("Authorization", auth))
                .andExpect(jsonPath("$[?(@.isPrimary == true)].length()").value(1))
                .andExpect(jsonPath("$[?(@.isPrimary == true)][0].name").value("Untouched"));
    }

    @Test
    void anUpdateThatDoesNotPromoteLeavesThePrimaryAlone() throws Exception {
        String first = createContact(auth, customerId, "Ramesh", true);
        String second = createContact(auth, customerId, "Suresh", false);

        mvc.perform(put("/api/v1/customers/" + customerId + "/contacts/" + second)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Suresh Kumar\",\"isPrimary\":false}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/customers/" + customerId + "/contacts").header("Authorization", auth))
                .andExpect(jsonPath("$[?(@.isPrimary == true)][0].name").value("Ramesh"));
    }
```

Write the `createContact(auth, customerId, name, isPrimary)` and `createCustomer(auth, name)` helpers
following the existing class's style, returning the created id via `JsonPath.read`.

- [ ] **Step 2: Run them to confirm they fail**

Run: `cd backend && ./gradlew test --tests '*ContactControllerTest*'`

Expected: FAIL — the two validation tests get 201 instead of 400 (no constraints yet), and the three
demotion tests report 2 primaries where 1 is expected. `acceptsATwentyCharacterPhone` and
`anUpdateThatDoesNotPromoteLeavesThePrimaryAlone` should already PASS; that is fine and expected —
they are the guards against over-correcting in Step 3.

- [ ] **Step 3: Add the constraints**

Replace `backend/src/main/java/com/easycrm/crm/web/dto/ContactRequest.java`:

```java
package com.easycrm.crm.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Sizes mirror V1x__contact.sql's column widths exactly; a longer value would fail at the DB. */
public record ContactRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 20) String phone,
        @Size(max = 20) String whatsappNumber,
        @Email @Size(max = 255) String email,
        @Size(max = 128) String designation,
        Boolean isPrimary) {}
```

These are bean-validation constraints, so they yield 400 with automatic `EMAIL` / `SIZE` /
`NOT_BLANK` codes — no registry entry needed (`error-codes.md` rule 2). But rule 4 applies: each
`@Size` field a form displays needs a per-field translation key in F1b, since bare `SIZE` states no
limit. Note that in the task report as an F1b input.

- [ ] **Step 4: Add the sibling finder**

In `ContactRepository`:

```java
    /**
     * @Transactional(readOnly = true) is load-bearing: Spring Data does not wrap derived finders, so
     * without it the RLS tenant GUC is unset and this returns zero rows (challenge #8) — which would
     * make demotion silently do nothing.
     */
    @Transactional(readOnly = true)
    List<Contact> findByCustomerIdAndIsPrimaryTrue(UUID customerId);
```

- [ ] **Step 5: Demote on both write paths**

In `ContactService`, both `add` and `update` set primary, so both must demote. Add a private helper
and call it from each **before** saving the promoted row:

```java
    /**
     * At most one primary contact per customer. Scoped to this customer only: a tenant-wide demotion
     * would quietly unset another customer's primary contact.
     *
     * <p>Runs inside the caller's @Transactional, so the demotion and the promotion commit together
     * or not at all — a half-applied promotion would leave zero primaries.
     */
    private void demoteOtherPrimaries(UUID customerId, UUID exceptContactId) {
        for (Contact existing : contacts.findByCustomerIdAndIsPrimaryTrue(customerId)) {
            if (!existing.getId().equals(exceptContactId)) {
                existing.demote();
            }
        }
    }
```

In `add`, after `requireCustomer(customerId)` and before `contacts.save(...)`:

```java
        if (Boolean.TRUE.equals(req.isPrimary())) {
            demoteOtherPrimaries(customerId, null);
        }
```

In `update`, after `Contact c = find(customerId, contactId);`:

```java
        if (Boolean.TRUE.equals(req.isPrimary())) {
            demoteOtherPrimaries(customerId, contactId);
        }
```

The `exceptContactId` argument is why `update` does not demote the row it is about to promote — passing
`null` there would demote it and then promote it, which happens to work but reads as a bug. Add
`demote()` to `Contact` (`this.isPrimary = false;`), matching its existing mutator style.

- [ ] **Step 6: Run the tests to confirm they pass**

Run: `cd backend && ./gradlew test --tests '*ContactControllerTest*'`

Expected: PASS, all seven.

- [ ] **Step 7: Prove the customer scoping bites**

Change `demoteOtherPrimaries` to iterate `contacts.findAll()` instead of
`findByCustomerIdAndIsPrimaryTrue(customerId)`. Run
`demotionDoesNotReachAnotherCustomersContacts`. **Expected: RED** — `Untouched` is no longer primary.
Revert and re-run green. Record both.

- [ ] **Step 8: Verify the whole suite**

Run: `cd backend && ./gradlew clean check`

Expected: `BUILD SUCCESSFUL`. If an existing contact test asserted that two primaries were possible,
read it before changing it — it may be documenting the old behaviour deliberately, in which case
update it and say so in the commit.

- [ ] **Step 9: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/main/java/com/easycrm/crm/ backend/src/test/java/com/easycrm/crm/
git commit -m "feat(crm): validate contacts and keep one primary per customer

ContactRequest constrained name only: a malformed email was accepted, and
phone could exceed its VARCHAR(20) column. Sizes now mirror the column
widths exactly.

isPrimary had no enforcement, so a customer could have three primary
contacts and a UI had to pick one arbitrarily. Both write paths now
demote siblings inside the caller's transaction, so promotion and
demotion commit together -- a half-applied promotion would leave zero
primaries. Scoped to the one customer: a tenant-wide demotion would
unset another customer's primary, and a test proves that by going red
against findAll()."
```

---

## Task 10: Regenerate the contract and verify the slice

**Files:**
- Modify: `docs/api/openapi.yaml` (generated)
- Verify: everything

**Interfaces:**
- Consumes: every change from Tasks 1–9.
- Produces: the contract F1b generates its TypeScript client from.

- [ ] **Step 1: Regenerate**

Run the project's generation task — find it rather than guessing: `cd backend && ./gradlew tasks --all | grep -i openapi`.
`OpenApiSnapshotTest` is the byte-for-byte guard, so its snapshot and `docs/api/openapi.yaml` must
agree exactly.

- [ ] **Step 2: Confirm the diff is additive only**

Run: `cd /Users/divyam/Documents/easy-crm && git diff --stat docs/api/openapi.yaml` then read the diff.

Expected additions only: the `q` parameter on three list operations, the new
`PUT /api/v1/price-lists/{priceListId}/items/{itemId}` operation and its
`PriceListItemUpdateRequest` schema, and the new `ContactRequest` constraints.

**If anything is removed or renamed, stop.** A removal is a breaking change the frontend would inherit
silently, since oasdiff is still non-blocking (Phase 1 item 7). Report it rather than committing it.

- [ ] **Step 3: Full verification from clean**

Run: `cd backend && ./gradlew clean check`

Expected: `BUILD SUCCESSFUL`. Count the tests by summing `tests="…"` across every
`build/test-results/test/*.xml` in both modules and **report the actual number** — not a prediction.
The floor is 688 plus everything this plan added; a count below that means a test was lost.

- [ ] **Step 4: Confirm the migration applies from empty**

`clean check` uses Testcontainers, so V36 has already applied to a fresh database. Confirm
`MasterDataSearchIndexTest` is in the run's results rather than assuming it ran.

- [ ] **Step 5: Log the engineering challenges**

Append to `docs/superpowers/engineering-challenges.md`, using the template at the bottom of that file
and assigning the next free numbers at commit time (R38 — never reserved in advance). Candidates from
this slice, each of which meets the bar because the naive approach is subtly wrong:

1. **The OR that must not be flattened.** Adding `cb.or(name, gstin)` as two predicates rather than
   one turns `active=true AND (name OR gstin)` into `(active AND name) OR gstin`, leaking deactivated
   rows. The test that catches it has to search for a term that matches a *deactivated* row.
2. **Search must compose inside the visibility Specification, not around it.** Filtering after
   `CustomerVisibility.page` would let a `SALES_EXEC` search past `assignedTo` scoping. Fails open,
   and no existing gate sees it.
3. **A guard that cannot be written with the obvious tool.** ArchUnit sees types and call edges but
   never constructor arguments, so it cannot distinguish a two-arg from a three-arg
   `ValidationException`. Source inspection is the correct instrument, and it needs its own
   non-vacuity assertions or the regex silently stops matching.
4. **`lower(col) gin_trgm_ops` must match the predicate's shape.** A trigram index on the raw column
   is simply unused by `LOWER(col) LIKE ...`, so the index exists, the query is slow, and nothing
   reports a problem.

Judge each against the bar in `CLAUDE.md` before writing it up; log the ones that clear it, not all
four by default.

- [ ] **Step 6: Update the handoff**

Add a new top section to `docs/superpowers/HANDOFF.md` recording: the measured test count, the red runs
from Tasks 3, 7, 8 and 9, the `pg_trgm` outcome, the F1b inputs this slice produced (the per-field
`SIZE` translation keys from Task 9 Step 3, the full code list for F1b's `errors.fields` blocks), and
anything that did not go to plan. Update `docs/ROADMAP.md`'s item 4 line and §1.1 test count.

**Also correct ROADMAP's H5 row** while you are there: it claims there is no index behind
`assigned_to = :me OR assigned_to IS NULL`, but `V33__assigned_to_indexes.sql` shipped
`idx_customer_assigned` and `idx_enquiry_assigned`. Only the status-only order-list half of H5 is still
true. That error was found while planning this slice.

- [ ] **Step 7: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add docs/api/openapi.yaml docs/superpowers/engineering-challenges.md \
        docs/superpowers/HANDOFF.md docs/ROADMAP.md
git commit -m "docs: regenerate the contract for F1a and record the slice

Additive only: q on three list operations, PUT for a price-list item,
and the new ContactRequest constraints. Also corrects ROADMAP's H5,
which claimed the assigned_to indexes were missing -- V33 shipped them;
only the status-only order-list half of H5 still stands."
```

---

## Self-Review

**Spec coverage — Part 1, section by section:**

| Spec | Task |
|---|---|
| §1.1 search + both index kinds + visibility composition + no hand-written tenant predicate | 1, 3, 4 |
| §1.2 sort allowlist, default sort, size cap, the 422 rationale | 2, 3, 4 |
| §1.3 all twelve codes + the build-failing guard | 5, 6, 7 |
| §1.4 price-list-item `PUT`, `productId` immutable, XOR on update | 8 |
| §1.5 contact constraints + primary demotion | 9 |
| §1.6 contract regeneration + snapshot | 10 |
| §4.6 backend test list | covered across 1–9; visibility 3·7, migration 1 |
| Part 6 operational notes | Global Constraints |
| Part 7 stopping point 2 (`pg_trgm` privileges) | 1 Step 5 |

Part 7's stopping points 1 and 3 are F1b's (jsdom `showModal`, the Rollup chunk map); stopping point 4
(sort allowlist awkwardness) is resolved by Task 2's design — a plain static helper called from each
service, rather than resolver-level machinery.

**Type consistency:** `filter(Boolean, String)` is the signature in all three Specifications;
`list(Boolean, String, Pageable)` in all three services; `SortAllowlist.require(Pageable, Set<String>)`
at all three call sites; `PriceListItemUpdateRequest(BigDecimal, BigDecimal)` in the DTO, service and
controller. `validateXor`/`validateRange` are refactored once, in Task 8 Step 4, to take two
`BigDecimal`s — Task 6 edits their bodies first, so **Task 8 must be done after Task 6**, and its step
says so by showing the refactor rather than the original signature.

**Deliberate reads-before-writes, not placeholders.** Five steps say "read this file first" —
`PriceListItem.java`'s mutator style (8), `TestTokens`' `SALES_EXEC` minting and `AssignableUsers`
setup (3 Step 7), the arch-test conventions (7 Step 1), and the existing helpers in
`PriceListItemControllerTest` / `ContactControllerTest`. These are genuine unknowns I did not verify
while planning, and inventing the code would have been worse than naming the file.
