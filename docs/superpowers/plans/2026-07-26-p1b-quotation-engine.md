# P1b Quotation Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the tenant-scoped quotation engine — versioned, GST-correct quotations that can be created, edited, sent (frozen + gaplessly numbered), and revised — reading the P1a master data.

**Architecture:** New flat module `com.easycrm.sales` (mirrors `catalog`/`crm`): entities + `*Repository` + `QuotationService` + `web/QuotationController` + `web/dto/*`, plus pure helpers `GstCalculator`, `PriceResolver`, `DocumentNumberService`. A global `com.easycrm.platform.money` Jackson-3 module serializes every `BigDecimal` as a JSON string. A `quotation` is a mutable pointer + status; its `quotation_version` rows are mutable while DRAFT and frozen on SEND; `quotation_item` rows carry a product snapshot. `quote_no` is assigned gaplessly on first SEND via a pessimistically-locked `document_counter` row.

**Tech Stack:** Spring Boot 4.1.0, Java 25, Hibernate 7, PostgreSQL 16 (+ RLS), Jackson 3.1.4 (`tools.jackson.*`), Testcontainers, JUnit 5, jayway JsonPath.

## Global Constraints

- **Money is never `double`.** `BigDecimal` in Java, `NUMERIC` in Postgres, JSON **string** on the wire. Amounts `NUMERIC(18,2)`, rates/percents `NUMERIC(18,4)`, qty `NUMERIC(18,3)`. Round **per line** to 2 dp (`RoundingMode.HALF_UP`), then sum. Server recomputes; client preview is never trusted. (challenge #2)
- **Tenant isolation is structural.** Every new `@Entity` extends `TenantScopedEntity` (never a `GLOBAL_TABLES` allowlist entry here); never hand-write `WHERE tenant_id`; rely on Hibernate `@TenantId` + Postgres RLS. `TenantScopingArchTest` fails the build otherwise.
- **FK columns are bare `UUID`s with no DB foreign-key constraints** (matches every shipped table). Same-tenant integrity is structural (RLS + non-enumerable UUIDv7 ids).
- **`ddl-auto: validate` is ON.** Migration column types must match entity mappings exactly (`VARCHAR` for `String`, `NUMERIC(18,2)` for a 2-scale `BigDecimal`, etc.). Every table gets `created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0` and an RLS policy.
- **RLS-scoped derived finders need `@Transactional(readOnly = true)`** or they silently return zero rows (challenge #8). Repo/RLS tests run in a tenant-bound transaction.
- **Boot 4 / Jackson 3 package moves** (challenge #10): Jackson is under `tools.jackson.*`, not `com.fasterxml.jackson.*`. `@AutoConfigureMockMvc` is in `org.springframework.boot.webmvc.test.autoconfigure`. If an import "does not exist", search the resolved jars for the class's new package rather than assuming this plan is wrong.
- **Commits:** author `divyam <divyam.0444@gmail.com>` (plain `git commit`, repo config already set). **Never** mention Claude/AI or add a `Co-Authored-By` trailer. One task per commit; TDD (failing test → confirm-fail → minimal code → pass → commit).
- **Test command:** `cd backend && ./gradlew test` (Docker must be running for Testcontainers). Single test: `./gradlew test --tests 'com.easycrm.sales.GstCalculatorTest'`.
- **Log engineering challenges** in `docs/superpowers/engineering-challenges.md` and **update `docs/superpowers/annotations-reference.md`** in the same change that introduces them (see Task 12 for the required entries; add them in the task that first hits them if earlier).

**Reference implementations to mirror (read before starting):** `Product`/`ProductService`/`ProductController`/`ProductResponse` (catalog CRUD shape), `ProductRepositoryTest`/`ProductControllerTest` (test shape), `ApiExceptionHandler` (422/409/404 mapping), `Tenant` (has `getStateCode()`), `TenantContext` (`tenantId()`, `set`, `runAs`), `PageResponse`.

---

## Task 1: Money-as-JSON-string wire format (global)

Introduces a global Jackson-3 module so every `BigDecimal` serializes as a JSON **string** (`toPlainString()`, no scientific notation). Closes the challenge-#2 wire gap and retrofits P1a's existing money responses. Deserialization already accepts strings (Jackson coerces string→`BigDecimal`), so only serialization changes.

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/money/BigDecimalStringModule.java`
- Create: `backend/src/main/java/com/easycrm/platform/money/MoneyJacksonConfig.java`
- Test: `backend/src/test/java/com/easycrm/platform/money/MoneyWireFormatTest.java`

**Interfaces:**
- Produces: a Spring `@Bean` of type `tools.jackson.databind.JacksonModule` that Boot 4's Jackson auto-config registers app-wide. No public Java API for other tasks — its effect is on the wire.

- [ ] **Step 1: Write the failing test** (asserts a `BigDecimal` field serializes as a quoted string end-to-end through a real controller)

`backend/src/test/java/com/easycrm/platform/money/MoneyWireFormatTest.java`:
```java
package com.easycrm.platform.money;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MoneyWireFormatTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void bigDecimalSerializesAsQuotedString() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String create = """
            {"sku":"SKU-MONEY","name":"Bolt","hsnCode":"7318","uom":"PCS",
             "gstRate":"18","baseRate":"12.50"}""";
        String body = mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        String getBody = mvc.perform(get("/api/v1/products/" + id).header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // Raw-JSON assertion: the money fields must be JSON strings, not numbers.
        assertThat(getBody).contains("\"baseRate\":\"12.50\"");
        assertThat(getBody).contains("\"gstRate\":\"18\"");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.platform.money.MoneyWireFormatTest'`
Expected: FAIL — response currently contains `"baseRate":12.50` (a JSON number), so the `contains("\"baseRate\":\"12.50\"")` assertion fails.

- [ ] **Step 3: Write the serializer module**

`backend/src/main/java/com/easycrm/platform/money/BigDecimalStringModule.java`:
```java
package com.easycrm.platform.money;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.math.BigDecimal;

/**
 * Serializes every BigDecimal as a JSON string in plain notation. JS numbers are IEEE-754
 * doubles, so money on the wire as a number re-introduces the rounding error BigDecimal
 * exists to prevent (challenge #2). Deserialization is unchanged — Jackson already coerces
 * a JSON string back to BigDecimal. Jackson 3 lives under tools.jackson.* (challenge #10).
 */
public class BigDecimalStringModule extends SimpleModule {

    public BigDecimalStringModule() {
        addSerializer(BigDecimal.class, new BigDecimalStringSerializer());
    }

    static final class BigDecimalStringSerializer extends ValueSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString(value.toPlainString());
        }
    }
}
```

- [ ] **Step 4: Register it as a bean**

`backend/src/main/java/com/easycrm/platform/money/MoneyJacksonConfig.java`:
```java
package com.easycrm.platform.money;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JacksonModule;

@Configuration
public class MoneyJacksonConfig {

    // Boot 4's Jackson auto-config discovers JacksonModule beans and registers them on the
    // application ObjectMapper. SimpleModule implements JacksonModule.
    @Bean
    JacksonModule bigDecimalStringModule() {
        return new BigDecimalStringModule();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.platform.money.MoneyWireFormatTest'`
Expected: PASS.
If it fails to compile on a `tools.jackson.*` import, list the resolved jar to find the exact class: `find ~/.gradle -name 'jackson-databind-3*.jar' ! -name '*sources*' ! -name '*javadoc*' | head -1 | xargs -I{} unzip -l {} | grep -Ei 'ValueSerializer|SerializationContext|SimpleModule'` and adjust the import (challenge #10 pattern).

- [ ] **Step 6: Run the full suite to confirm nothing regressed** (this changes ALL money responses)

Run: `cd backend && ./gradlew test`
Expected: PASS. Existing P1a controller tests assert money via `.value("18")` / string literals or non-money fields and remain green; if any asserted a numeric money value, update it to the string form.

- [ ] **Step 7: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/platform/money src/test/java/com/easycrm/platform/money && git commit -m "feat(money): serialize BigDecimal as JSON string globally"
```

---

## Task 2: `GstCalculator` (pure GST computation)

A dependency-free, statically-testable calculator for the per-line CGST/SGST/IGST split and round-then-sum totals. No Spring, no DB — the interstate flag is passed in.

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/GstCalculator.java`
- Test: `backend/src/test/java/com/easycrm/sales/GstCalculatorTest.java`

**Interfaces:**
- Produces:
  - `GstCalculator.LineInput(BigDecimal qty, BigDecimal rate, BigDecimal discountPct, BigDecimal gstRate)`
  - `GstCalculator.LineResult(BigDecimal taxableValue, BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal lineTotal)`
  - `GstCalculator.Totals(BigDecimal subTotal, BigDecimal totalTax, BigDecimal grandTotal)`
  - `static LineResult computeLine(LineInput in, boolean interState)`
  - `static Totals totals(List<LineResult> lines)`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/easycrm/sales/GstCalculatorTest.java`:
```java
package com.easycrm.sales;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GstCalculatorTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test
    void intraStateSplitsCgstSgstEqually() {
        var in = new GstCalculator.LineInput(bd("2"), bd("100.00"), BigDecimal.ZERO, bd("18"));
        var r = GstCalculator.computeLine(in, false); // intra-state
        assertThat(r.taxableValue()).isEqualByComparingTo("200.00");
        assertThat(r.cgst()).isEqualByComparingTo("18.00");
        assertThat(r.sgst()).isEqualByComparingTo("18.00");
        assertThat(r.igst()).isEqualByComparingTo("0.00");
        assertThat(r.lineTotal()).isEqualByComparingTo("236.00");
    }

    @Test
    void interStateChargesIgstOnly() {
        var in = new GstCalculator.LineInput(bd("2"), bd("100.00"), BigDecimal.ZERO, bd("18"));
        var r = GstCalculator.computeLine(in, true); // inter-state
        assertThat(r.cgst()).isEqualByComparingTo("0.00");
        assertThat(r.sgst()).isEqualByComparingTo("0.00");
        assertThat(r.igst()).isEqualByComparingTo("36.00");
        assertThat(r.lineTotal()).isEqualByComparingTo("236.00");
    }

    @Test
    void appliesLineDiscountToTaxableValue() {
        var in = new GstCalculator.LineInput(bd("1"), bd("100.00"), bd("10"), bd("18"));
        var r = GstCalculator.computeLine(in, true);
        assertThat(r.taxableValue()).isEqualByComparingTo("90.00"); // 100 - 10%
        assertThat(r.igst()).isEqualByComparingTo("16.20");
    }

    @Test
    void zeroRatedProducesNoTax() {
        var in = new GstCalculator.LineInput(bd("3"), bd("50.00"), BigDecimal.ZERO, bd("0"));
        var r = GstCalculator.computeLine(in, false);
        assertThat(r.taxableValue()).isEqualByComparingTo("150.00");
        assertThat(r.cgst()).isEqualByComparingTo("0.00");
        assertThat(r.lineTotal()).isEqualByComparingTo("150.00");
    }

    @Test
    void roundsAtLineThenSums_notSumThenRound() {
        // Two lines each taxable 0.125 → rounds to 0.13 each; round-then-sum = 0.26.
        // Sum-then-round of 0.25 would give 0.25 (or 0.26 depending) — this pins the Tally rule.
        var a = GstCalculator.computeLine(
            new GstCalculator.LineInput(bd("1"), bd("0.125"), BigDecimal.ZERO, bd("0")), false);
        var b = GstCalculator.computeLine(
            new GstCalculator.LineInput(bd("1"), bd("0.125"), BigDecimal.ZERO, bd("0")), false);
        assertThat(a.taxableValue()).isEqualByComparingTo("0.13"); // 0.125 HALF_UP → 0.13
        var totals = GstCalculator.totals(List.of(a, b));
        assertThat(totals.subTotal()).isEqualByComparingTo("0.26");
        assertThat(totals.grandTotal()).isEqualByComparingTo("0.26");
    }

    @Test
    void totalsSumRoundedLineValues() {
        var a = GstCalculator.computeLine(
            new GstCalculator.LineInput(bd("2"), bd("100.00"), BigDecimal.ZERO, bd("18")), false);
        var b = GstCalculator.computeLine(
            new GstCalculator.LineInput(bd("1"), bd("50.00"), BigDecimal.ZERO, bd("12")), true);
        var t = GstCalculator.totals(List.of(a, b));
        assertThat(t.subTotal()).isEqualByComparingTo("250.00");   // 200 + 50
        assertThat(t.totalTax()).isEqualByComparingTo("42.00");    // (18+18) + 6
        assertThat(t.grandTotal()).isEqualByComparingTo("292.00");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.GstCalculatorTest'`
Expected: FAIL — `GstCalculator` does not exist (compile error).

- [ ] **Step 3: Write the implementation**

`backend/src/main/java/com/easycrm/sales/GstCalculator.java`:
```java
package com.easycrm.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure GST computation. Rounds at the line to 2 dp (HALF_UP), then sums the rounded line
 * values — matching Tally and avoiding the accumulated-rounding error of sum-then-round
 * (challenge #2). Whether a line is inter-state (IGST) or intra-state (CGST+SGST) is decided
 * by the caller (customer place-of-supply vs tenant/supplier state) and passed in.
 */
public final class GstCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO = new BigDecimal("2");

    private GstCalculator() {}

    public record LineInput(BigDecimal qty, BigDecimal rate, BigDecimal discountPct, BigDecimal gstRate) {}

    public record LineResult(BigDecimal taxableValue, BigDecimal cgst, BigDecimal sgst,
                             BigDecimal igst, BigDecimal lineTotal) {}

    public record Totals(BigDecimal subTotal, BigDecimal totalTax, BigDecimal grandTotal) {}

    public static LineResult computeLine(LineInput in, boolean interState) {
        BigDecimal discount = in.discountPct() == null ? BigDecimal.ZERO : in.discountPct();
        BigDecimal gross = in.qty().multiply(in.rate());
        BigDecimal discountFactor = BigDecimal.ONE.subtract(discount.divide(HUNDRED));
        BigDecimal taxable = round(gross.multiply(discountFactor));

        BigDecimal tax = round(taxable.multiply(in.gstRate()).divide(HUNDRED));
        BigDecimal cgst = BigDecimal.ZERO.setScale(2);
        BigDecimal sgst = BigDecimal.ZERO.setScale(2);
        BigDecimal igst = BigDecimal.ZERO.setScale(2);
        if (interState) {
            igst = tax;
        } else {
            // Split the total tax so cgst + sgst == tax exactly (avoid a stray 0.01 from
            // rounding half twice): round one half, derive the other as remainder.
            cgst = round(taxable.multiply(in.gstRate()).divide(TWO).divide(HUNDRED));
            sgst = tax.subtract(cgst);
        }
        BigDecimal lineTotal = taxable.add(cgst).add(sgst).add(igst);
        return new LineResult(taxable, cgst, sgst, igst, lineTotal);
    }

    public static Totals totals(List<LineResult> lines) {
        BigDecimal sub = BigDecimal.ZERO.setScale(2);
        BigDecimal tax = BigDecimal.ZERO.setScale(2);
        for (LineResult l : lines) {
            sub = sub.add(l.taxableValue());
            tax = tax.add(l.cgst()).add(l.sgst()).add(l.igst());
        }
        return new Totals(sub, tax, sub.add(tax));
    }

    private static BigDecimal round(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.GstCalculatorTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/sales/GstCalculator.java src/test/java/com/easycrm/sales/GstCalculatorTest.java && git commit -m "feat(sales): GST line calculator with round-at-line-then-sum"
```

---

## Task 3: Gapless document numbering (`DocumentCounter` + `DocumentNumberService`)

A tenant-scoped counter table read under a pessimistic row lock so concurrent sends within a tenant/FY get gapless, consecutive numbers, and a rolled-back send consumes none.

**Files:**
- Create: `backend/src/main/resources/db/migration/V14__document_counter.sql`
- Create: `backend/src/main/java/com/easycrm/sales/DocumentCounter.java`
- Create: `backend/src/main/java/com/easycrm/sales/DocumentCounterRepository.java`
- Create: `backend/src/main/java/com/easycrm/sales/DocumentNumberService.java`
- Test: `backend/src/test/java/com/easycrm/sales/DocumentNumberServiceTest.java`

**Interfaces:**
- Produces:
  - `DocumentNumberService.nextQuoteNo(LocalDate onDate) -> String` (e.g. `QT/25-26/0001`), `@Transactional` (joins the caller's send tx).
  - `static DocumentNumberService.financialYear(LocalDate) -> String` (e.g. `25-26`).

- [ ] **Step 1: Write the migration**

`backend/src/main/resources/db/migration/V14__document_counter.sql`:
```sql
CREATE TABLE document_counter (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    doc_type   VARCHAR(16) NOT NULL,
    fy         VARCHAR(7) NOT NULL,
    next_val   BIGINT NOT NULL,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    version    BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_doc_counter_tenant_type_fy UNIQUE (tenant_id, doc_type, fy)
);
CREATE INDEX idx_doc_counter_tenant ON document_counter (tenant_id, id);

ALTER TABLE document_counter ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document_counter
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
```

- [ ] **Step 2: Write the entity**

`backend/src/main/java/com/easycrm/sales/DocumentCounter.java`:
```java
package com.easycrm.sales;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "document_counter",
       uniqueConstraints = @UniqueConstraint(name = "uq_doc_counter_tenant_type_fy",
                                             columnNames = {"tenant_id", "doc_type", "fy"}))
public class DocumentCounter extends TenantScopedEntity {

    @Column(name = "doc_type", nullable = false, length = 16)
    private String docType;

    @Column(nullable = false, length = 7)
    private String fy;

    @Column(name = "next_val", nullable = false)
    private long nextVal;

    protected DocumentCounter() {}

    public DocumentCounter(String docType, String fy) {
        this.docType = docType;
        this.fy = fy;
        this.nextVal = 1;
    }

    public long getNextVal() { return nextVal; }
    public void increment() { this.nextVal++; }
    public String getDocType() { return docType; }
    public String getFy() { return fy; }
}
```

- [ ] **Step 3: Write the repository (pessimistic lock finder)**

`backend/src/main/java/com/easycrm/sales/DocumentCounterRepository.java`:
```java
package com.easycrm.sales;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DocumentCounterRepository extends JpaRepository<DocumentCounter, UUID> {

    // PESSIMISTIC_WRITE → SELECT ... FOR UPDATE. Serializes concurrent sends within a
    // tenant/FY so the sequence is gapless. Runs inside the caller's send transaction, which
    // sets the RLS tenant GUC (challenge #8).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from DocumentCounter c where c.docType = :docType and c.fy = :fy")
    Optional<DocumentCounter> findForUpdate(@Param("docType") String docType, @Param("fy") String fy);
}
```

- [ ] **Step 4: Write the service**

`backend/src/main/java/com/easycrm/sales/DocumentNumberService.java`:
```java
package com.easycrm.sales;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class DocumentNumberService {

    private final DocumentCounterRepository counters;

    public DocumentNumberService(DocumentCounterRepository counters) { this.counters = counters; }

    /**
     * Assigns the next gapless quote number for the tenant/FY of {@code onDate}. Must run in
     * the caller's transaction (default REQUIRED propagation) so the FOR UPDATE lock and the
     * increment commit or roll back atomically with the send. A rolled-back send releases the
     * lock without consuming a number.
     */
    @Transactional
    public String nextQuoteNo(LocalDate onDate) {
        String fy = financialYear(onDate);
        DocumentCounter counter = counters.findForUpdate("QUOTE", fy)
            .orElseGet(() -> counters.save(new DocumentCounter("QUOTE", fy)));
        long value = counter.getNextVal();
        counter.increment();
        return String.format("QT/%s/%04d", fy, value);
    }

    /** Indian financial year label (Apr 1 – Mar 31), e.g. 2025-06-01 → "25-26". */
    public static String financialYear(LocalDate d) {
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return String.format("%02d-%02d", start % 100, (start + 1) % 100);
    }
}
```

- [ ] **Step 5: Write the failing test**

`backend/src/test/java/com/easycrm/sales/DocumentNumberServiceTest.java`:
```java
package com.easycrm.sales;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentNumberServiceTest extends IntegrationTest {
    @Autowired DocumentNumberService service;
    @Autowired PlatformTransactionManager txManager;

    private static final LocalDate FY_25_26 = LocalDate.of(2025, 6, 1);

    @AfterEach void clear() { TenantContext.clear(); }

    private void asTenant(UUID t) {
        TenantContext.set(new TenantContext.TenantPrincipal(t, UUID.randomUUID(), "OWNER"));
    }

    private int suffix(String quoteNo) { // "QT/25-26/0007" -> 7
        return Integer.parseInt(quoteNo.substring(quoteNo.lastIndexOf('/') + 1));
    }

    @Test
    void financialYearLabelsAprToMar() {
        assertThat(DocumentNumberService.financialYear(LocalDate.of(2025, 6, 1))).isEqualTo("25-26");
        assertThat(DocumentNumberService.financialYear(LocalDate.of(2026, 3, 31))).isEqualTo("25-26");
        assertThat(DocumentNumberService.financialYear(LocalDate.of(2026, 4, 1))).isEqualTo("26-27");
    }

    @Test
    void numbersAreSequentialWithinTenantAndFy() {
        asTenant(UUID.randomUUID());
        assertThat(service.nextQuoteNo(FY_25_26)).isEqualTo("QT/25-26/0001");
        assertThat(service.nextQuoteNo(FY_25_26)).isEqualTo("QT/25-26/0002");
        assertThat(service.nextQuoteNo(LocalDate.of(2026, 4, 1))).isEqualTo("QT/26-27/0001"); // FY rollover
    }

    @Test
    void rolledBackSendConsumesNoNumber() {
        UUID tenant = UUID.randomUUID();
        asTenant(tenant);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        String inRolledBack = tx.execute(status -> {
            String n = service.nextQuoteNo(FY_25_26);
            status.setRollbackOnly();
            return n;
        });
        assertThat(suffix(inRolledBack)).isEqualTo(1);
        // The rolled-back number is NOT burned — the next successful call reuses it. No gap.
        assertThat(suffix(service.nextQuoteNo(FY_25_26))).isEqualTo(1);
    }

    @Test
    void concurrentSendsGetDistinctConsecutiveNumbers() throws Exception {
        UUID tenant = UUID.randomUUID();
        asTenant(tenant);
        service.nextQuoteNo(FY_25_26); // seed the counter row (now at 0002) so the race is on the lock

        Callable<Integer> send = () -> {
            asTenant(tenant); // ThreadLocal per worker thread
            try {
                return suffix(service.nextQuoteNo(FY_25_26));
            } finally {
                TenantContext.clear();
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> f1 = pool.submit(send);
            Future<Integer> f2 = pool.submit(send);
            Set<Integer> got = Set.of(f1.get(), f2.get());
            assertThat(got).containsExactlyInAnyOrderElementsOf(List.of(2, 3)); // distinct, gapless
        } finally {
            pool.shutdownNow();
        }
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.DocumentNumberServiceTest'`
Expected: PASS. (Migration V14 + entity + repo + service already written in Steps 1–4, so this is the confirm-pass; if it errors on `ddl-auto: validate`, the migration column types disagree with the entity — reconcile them.)
Note: if you prefer strict TDD ordering, write Step 5's test first and run it to see the compile/`NoSuchBean` failure before Steps 1–4; the deliverable is identical.

- [ ] **Step 7: Commit**

```bash
cd backend && git add src/main/resources/db/migration/V14__document_counter.sql src/main/java/com/easycrm/sales/DocumentCounter.java src/main/java/com/easycrm/sales/DocumentCounterRepository.java src/main/java/com/easycrm/sales/DocumentNumberService.java src/test/java/com/easycrm/sales/DocumentNumberServiceTest.java && git commit -m "feat(sales): gapless per-tenant/FY document numbering"
```

---

## Task 4: `PriceResolver` (customer + product → effective rate + snapshot)

Resolves the default line rate from the customer's price list and returns the product snapshot fields the quotation item copies. The result is a default the caller may override.

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/PriceResolver.java`
- Test: `backend/src/test/java/com/easycrm/sales/PriceResolverTest.java`

**Interfaces:**
- Consumes: `CustomerRepository.findById`, `ProductRepository.findById`, `PriceListItemRepository.findByPriceListIdAndProductId` (all existing).
- Produces:
  - `PriceResolver.Resolved(BigDecimal rate, String name, String hsn, String uom, BigDecimal gstRate)`
  - `Resolved resolve(UUID customerId, UUID productId)` — throws `NotFoundException` if the customer or product is missing.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/easycrm/sales/PriceResolverTest.java`:
```java
package com.easycrm.sales;

import com.easycrm.catalog.PriceList;
import com.easycrm.catalog.PriceListItem;
import com.easycrm.catalog.PriceListItemRepository;
import com.easycrm.catalog.PriceListRepository;
import com.easycrm.catalog.Product;
import com.easycrm.catalog.ProductRepository;
import com.easycrm.catalog.Uom;
import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PriceResolverTest extends IntegrationTest {
    @Autowired PriceResolver resolver;
    @Autowired ProductRepository products;
    @Autowired CustomerRepository customers;
    @Autowired PriceListRepository priceLists;
    @Autowired PriceListItemRepository priceListItems;
    @Autowired PlatformTransactionManager txManager;

    @AfterEach void clear() { TenantContext.clear(); }

    private void asTenant(UUID t) {
        TenantContext.set(new TenantContext.TenantPrincipal(t, UUID.randomUUID(), "OWNER"));
    }

    private Product newProduct(String sku, String base) {
        return new Product(sku, "Widget", "84818090", Uom.PCS, new BigDecimal("18.0000"), new BigDecimal(base));
    }

    private Customer newCustomer(UUID priceListId) {
        return new Customer("Acme", null, "27", null, null, 0, null, priceListId, CustomerSource.MANUAL);
    }

    @Test
    void fallsBackToBaseRateWhenNoPriceList() {
        asTenant(UUID.randomUUID());
        var tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> {
            Product p = products.save(newProduct("SKU-A", "100.00"));
            Customer c = customers.save(newCustomer(null));
            PriceResolver.Resolved r = resolver.resolve(c.getId(), p.getId());
            assertThat(r.rate()).isEqualByComparingTo("100.00");
            assertThat(r.name()).isEqualTo("Widget");
            assertThat(r.gstRate()).isEqualByComparingTo("18.0000");
        });
    }

    @Test
    void overrideRateWins() {
        asTenant(UUID.randomUUID());
        var tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> {
            Product p = products.save(newProduct("SKU-B", "100.00"));
            PriceList pl = priceLists.save(new PriceList("Dealer"));
            priceListItems.save(new PriceListItem(pl.getId(), p.getId(), new BigDecimal("80.00"), null));
            Customer c = customers.save(newCustomer(pl.getId()));
            assertThat(resolver.resolve(c.getId(), p.getId()).rate()).isEqualByComparingTo("80.00");
        });
    }

    @Test
    void discountPercentAppliesToBaseRate() {
        asTenant(UUID.randomUUID());
        var tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> {
            Product p = products.save(newProduct("SKU-C", "100.00"));
            PriceList pl = priceLists.save(new PriceList("Retail"));
            priceListItems.save(new PriceListItem(pl.getId(), p.getId(), null, new BigDecimal("10.0000")));
            Customer c = customers.save(newCustomer(pl.getId()));
            assertThat(resolver.resolve(c.getId(), p.getId()).rate()).isEqualByComparingTo("90.00");
        });
    }

    @Test
    void fallsBackWhenPriceListLacksProduct() {
        asTenant(UUID.randomUUID());
        var tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> {
            Product p = products.save(newProduct("SKU-D", "55.00"));
            PriceList pl = priceLists.save(new PriceList("Empty"));
            Customer c = customers.save(newCustomer(pl.getId()));
            assertThat(resolver.resolve(c.getId(), p.getId()).rate()).isEqualByComparingTo("55.00");
        });
    }
}
```
Note: confirm the `Customer` and `PriceList` constructor signatures against the shipped entities before running — mirror `CustomerService.create` / `PriceListService`. Adjust the `newCustomer`/`new PriceList(...)` calls if the real constructors differ.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.PriceResolverTest'`
Expected: FAIL — `PriceResolver` does not exist.

- [ ] **Step 3: Write the implementation**

`backend/src/main/java/com/easycrm/sales/PriceResolver.java`:
```java
package com.easycrm.sales;

import com.easycrm.catalog.PriceListItem;
import com.easycrm.catalog.PriceListItemRepository;
import com.easycrm.catalog.Product;
import com.easycrm.catalog.ProductRepository;
import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.platform.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Resolves the default line rate for (customer, product): the customer's price-list override
 * or discount applied to the product base rate, else the base rate. Returns the product
 * snapshot fields the quotation item copies. The rate is a DEFAULT — the caller may override it.
 */
@Service
public class PriceResolver {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CustomerRepository customers;
    private final ProductRepository products;
    private final PriceListItemRepository priceListItems;

    public PriceResolver(CustomerRepository customers, ProductRepository products,
                         PriceListItemRepository priceListItems) {
        this.customers = customers;
        this.products = products;
        this.priceListItems = priceListItems;
    }

    public record Resolved(BigDecimal rate, String name, String hsn, String uom, BigDecimal gstRate) {}

    @Transactional(readOnly = true)
    public Resolved resolve(UUID customerId, UUID productId) {
        Customer customer = customers.findById(customerId)
            .orElseThrow(() -> new NotFoundException("customer not found"));
        Product product = products.findById(productId)
            .orElseThrow(() -> new NotFoundException("product not found"));

        BigDecimal rate = product.getBaseRate();
        UUID priceListId = customer.getPriceListId();
        if (priceListId != null) {
            PriceListItem item = priceListItems
                .findByPriceListIdAndProductId(priceListId, productId).orElse(null);
            if (item != null) {
                if (item.getOverrideRate() != null) {
                    rate = item.getOverrideRate();
                } else if (item.getDiscountPct() != null) {
                    BigDecimal factor = BigDecimal.ONE.subtract(item.getDiscountPct().divide(HUNDRED));
                    rate = product.getBaseRate().multiply(factor).setScale(2, RoundingMode.HALF_UP);
                }
            }
        }
        return new Resolved(rate, product.getName(), product.getHsnCode(),
                            product.getUom().name(), product.getGstRate());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.PriceResolverTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/sales/PriceResolver.java src/test/java/com/easycrm/sales/PriceResolverTest.java && git commit -m "feat(sales): price resolution from customer price list"
```

---

## Task 5: Quotation aggregate persistence (entities, migrations, repositories)

The three-table aggregate (`quotation`, `quotation_version`, `quotation_item`) + status enums + repositories, with repo and RLS zero-rows tests. No service/API yet.

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/QuotationStatus.java`
- Create: `backend/src/main/java/com/easycrm/sales/VersionStatus.java`
- Create: `backend/src/main/resources/db/migration/V15__quotation.sql`
- Create: `backend/src/main/resources/db/migration/V16__quotation_version.sql`
- Create: `backend/src/main/resources/db/migration/V17__quotation_item.sql`
- Create: `backend/src/main/java/com/easycrm/sales/Quotation.java`
- Create: `backend/src/main/java/com/easycrm/sales/QuotationVersion.java`
- Create: `backend/src/main/java/com/easycrm/sales/QuotationItem.java`
- Create: `backend/src/main/java/com/easycrm/sales/QuotationRepository.java`
- Create: `backend/src/main/java/com/easycrm/sales/QuotationVersionRepository.java`
- Create: `backend/src/main/java/com/easycrm/sales/QuotationItemRepository.java`
- Test: `backend/src/test/java/com/easycrm/sales/QuotationRepositoryTest.java`

**Interfaces:**
- Produces (entities + methods consumed by Task 6+):
  - `Quotation(UUID customerId, UUID enquiryId)` → status DRAFT; `setCurrentVersionId(UUID)`, `assignQuoteNo(String)`, `markSent()`, `reject()`, `expire()`, getters `getQuoteNo/getCustomerId/getEnquiryId/getCurrentVersionId/getStatus`.
  - `QuotationVersion(UUID quotationId, int versionNo, String placeOfSupply)` → status DRAFT; `setHeader(LocalDate validUntil, String paymentTerms, String deliveryTerms, String notes)`, `setTotals(BigDecimal subTotal, BigDecimal totalTax, BigDecimal grandTotal)`, `markSent(Instant sentAt)`, getters incl. `getVersionNo/getStatus/getPlaceOfSupply/getSubTotal/getTotalTax/getGrandTotal/getSentAt` and header getters.
  - `QuotationItem(UUID versionId, UUID productId, String nameSnapshot, String hsnSnapshot, String uomSnapshot, BigDecimal qty, BigDecimal rate, BigDecimal discountPct, BigDecimal gstRate, BigDecimal taxableValue, BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal lineTotal)` + getters.
  - `QuotationRepository extends JpaRepository<Quotation, UUID>` with `Page<Quotation> findByStatus(QuotationStatus, Pageable)`, `Page<Quotation> findByCustomerId(UUID, Pageable)` (both `@Transactional(readOnly = true)`).
  - `QuotationVersionRepository` with `List<QuotationVersion> findByQuotationIdOrderByVersionNoAsc(UUID)`, `Optional<QuotationVersion> findByQuotationIdAndVersionNo(UUID, int)` (`@Transactional(readOnly = true)`).
  - `QuotationItemRepository` with `List<QuotationItem> findByVersionId(UUID)`, `void deleteByVersionId(UUID)` (`@Transactional(readOnly = true)` on the finder).

- [ ] **Step 1: Write the enums**

`backend/src/main/java/com/easycrm/sales/QuotationStatus.java`:
```java
package com.easycrm.sales;

// ACCEPTED is intentionally absent — it arrives with the order/accept slice.
public enum QuotationStatus { DRAFT, SENT, REJECTED, EXPIRED }
```
`backend/src/main/java/com/easycrm/sales/VersionStatus.java`:
```java
package com.easycrm.sales;

public enum VersionStatus { DRAFT, SENT }
```

- [ ] **Step 2: Write the migrations**

`backend/src/main/resources/db/migration/V15__quotation.sql`:
```sql
CREATE TABLE quotation (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL,
    quote_no           VARCHAR(32),
    customer_id        UUID NOT NULL,
    enquiry_id         UUID,
    current_version_id UUID,
    status             VARCHAR(16) NOT NULL,
    created_at         TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ,
    version            BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_quotation_tenant_no UNIQUE (tenant_id, quote_no)
);
CREATE INDEX idx_quotation_tenant ON quotation (tenant_id, id);
CREATE INDEX idx_quotation_customer ON quotation (tenant_id, customer_id);

ALTER TABLE quotation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quotation
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
```
`backend/src/main/resources/db/migration/V16__quotation_version.sql`:
```sql
CREATE TABLE quotation_version (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    quotation_id    UUID NOT NULL,
    version_no      INT NOT NULL,
    status          VARCHAR(16) NOT NULL,
    valid_until     DATE,
    payment_terms   TEXT,
    delivery_terms  TEXT,
    notes           TEXT,
    place_of_supply VARCHAR(2) NOT NULL,
    sub_total       NUMERIC(18,2) NOT NULL,
    total_tax       NUMERIC(18,2) NOT NULL,
    grand_total     NUMERIC(18,2) NOT NULL,
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_qv_tenant_quotation_no UNIQUE (tenant_id, quotation_id, version_no)
);
CREATE INDEX idx_qv_tenant ON quotation_version (tenant_id, id);
CREATE INDEX idx_qv_quotation ON quotation_version (tenant_id, quotation_id);

ALTER TABLE quotation_version ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quotation_version
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
```
`backend/src/main/resources/db/migration/V17__quotation_item.sql`:
```sql
CREATE TABLE quotation_item (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL,
    version_id     UUID NOT NULL,
    product_id     UUID,
    name_snapshot  VARCHAR(255) NOT NULL,
    hsn_snapshot   VARCHAR(8),
    uom_snapshot   VARCHAR(16) NOT NULL,
    qty            NUMERIC(18,3) NOT NULL,
    rate           NUMERIC(18,2) NOT NULL,
    discount_pct   NUMERIC(18,4),
    gst_rate       NUMERIC(18,4) NOT NULL,
    taxable_value  NUMERIC(18,2) NOT NULL,
    cgst           NUMERIC(18,2) NOT NULL,
    sgst           NUMERIC(18,2) NOT NULL,
    igst           NUMERIC(18,2) NOT NULL,
    line_total     NUMERIC(18,2) NOT NULL,
    created_at     TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ,
    version        BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_qi_tenant ON quotation_item (tenant_id, id);
CREATE INDEX idx_qi_version ON quotation_item (tenant_id, version_id);

ALTER TABLE quotation_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quotation_item
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
```

- [ ] **Step 3: Write the `Quotation` entity**

`backend/src/main/java/com/easycrm/sales/Quotation.java`:
```java
package com.easycrm.sales;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

@Entity
@Table(name = "quotation",
       uniqueConstraints = @UniqueConstraint(name = "uq_quotation_tenant_no",
                                             columnNames = {"tenant_id", "quote_no"}))
public class Quotation extends TenantScopedEntity {

    @Column(name = "quote_no", length = 32)
    private String quoteNo;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "enquiry_id")
    private UUID enquiryId;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private QuotationStatus status;

    protected Quotation() {}

    public Quotation(UUID customerId, UUID enquiryId) {
        this.customerId = customerId;
        this.enquiryId = enquiryId;
        this.status = QuotationStatus.DRAFT;
    }

    public void setCurrentVersionId(UUID id) { this.currentVersionId = id; }
    public void assignQuoteNo(String no) { this.quoteNo = no; }
    public void markSent() { this.status = QuotationStatus.SENT; }
    public void reject() { this.status = QuotationStatus.REJECTED; }
    public void expire() { this.status = QuotationStatus.EXPIRED; }

    public String getQuoteNo() { return quoteNo; }
    public UUID getCustomerId() { return customerId; }
    public UUID getEnquiryId() { return enquiryId; }
    public UUID getCurrentVersionId() { return currentVersionId; }
    public QuotationStatus getStatus() { return status; }
}
```

- [ ] **Step 4: Write the `QuotationVersion` entity**

`backend/src/main/java/com/easycrm/sales/QuotationVersion.java`:
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "quotation_version",
       uniqueConstraints = @UniqueConstraint(name = "uq_qv_tenant_quotation_no",
                                             columnNames = {"tenant_id", "quotation_id", "version_no"}))
public class QuotationVersion extends TenantScopedEntity {

    @Column(name = "quotation_id", nullable = false)
    private UUID quotationId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VersionStatus status;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "payment_terms")
    private String paymentTerms;

    @Column(name = "delivery_terms")
    private String deliveryTerms;

    @Column
    private String notes;

    @Column(name = "place_of_supply", nullable = false, length = 2)
    private String placeOfSupply;

    @Column(name = "sub_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal subTotal = BigDecimal.ZERO;

    @Column(name = "total_tax", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalTax = BigDecimal.ZERO;

    @Column(name = "grand_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal grandTotal = BigDecimal.ZERO;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected QuotationVersion() {}

    public QuotationVersion(UUID quotationId, int versionNo, String placeOfSupply) {
        this.quotationId = quotationId;
        this.versionNo = versionNo;
        this.placeOfSupply = placeOfSupply;
        this.status = VersionStatus.DRAFT;
    }

    public void setHeader(LocalDate validUntil, String paymentTerms, String deliveryTerms, String notes) {
        this.validUntil = validUntil;
        this.paymentTerms = paymentTerms;
        this.deliveryTerms = deliveryTerms;
        this.notes = notes;
    }

    public void setTotals(BigDecimal subTotal, BigDecimal totalTax, BigDecimal grandTotal) {
        this.subTotal = subTotal;
        this.totalTax = totalTax;
        this.grandTotal = grandTotal;
    }

    public void markSent(Instant sentAt) {
        this.status = VersionStatus.SENT;
        this.sentAt = sentAt;
    }

    public UUID getQuotationId() { return quotationId; }
    public int getVersionNo() { return versionNo; }
    public VersionStatus getStatus() { return status; }
    public LocalDate getValidUntil() { return validUntil; }
    public String getPaymentTerms() { return paymentTerms; }
    public String getDeliveryTerms() { return deliveryTerms; }
    public String getNotes() { return notes; }
    public String getPlaceOfSupply() { return placeOfSupply; }
    public BigDecimal getSubTotal() { return subTotal; }
    public BigDecimal getTotalTax() { return totalTax; }
    public BigDecimal getGrandTotal() { return grandTotal; }
    public Instant getSentAt() { return sentAt; }
}
```

- [ ] **Step 5: Write the `QuotationItem` entity**

`backend/src/main/java/com/easycrm/sales/QuotationItem.java`:
```java
package com.easycrm.sales;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "quotation_item")
public class QuotationItem extends TenantScopedEntity {

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "name_snapshot", nullable = false)
    private String nameSnapshot;

    @Column(name = "hsn_snapshot", length = 8)
    private String hsnSnapshot;

    @Column(name = "uom_snapshot", nullable = false, length = 16)
    private String uomSnapshot;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal qty;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal rate;

    @Column(name = "discount_pct", precision = 18, scale = 4)
    private BigDecimal discountPct;

    @Column(name = "gst_rate", nullable = false, precision = 18, scale = 4)
    private BigDecimal gstRate;

    @Column(name = "taxable_value", nullable = false, precision = 18, scale = 2)
    private BigDecimal taxableValue;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal cgst;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal sgst;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal igst;

    @Column(name = "line_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal lineTotal;

    protected QuotationItem() {}

    public QuotationItem(UUID versionId, UUID productId, String nameSnapshot, String hsnSnapshot,
                         String uomSnapshot, BigDecimal qty, BigDecimal rate, BigDecimal discountPct,
                         BigDecimal gstRate, BigDecimal taxableValue, BigDecimal cgst, BigDecimal sgst,
                         BigDecimal igst, BigDecimal lineTotal) {
        this.versionId = versionId;
        this.productId = productId;
        this.nameSnapshot = nameSnapshot;
        this.hsnSnapshot = hsnSnapshot;
        this.uomSnapshot = uomSnapshot;
        this.qty = qty;
        this.rate = rate;
        this.discountPct = discountPct;
        this.gstRate = gstRate;
        this.taxableValue = taxableValue;
        this.cgst = cgst;
        this.sgst = sgst;
        this.igst = igst;
        this.lineTotal = lineTotal;
    }

    public UUID getVersionId() { return versionId; }
    public UUID getProductId() { return productId; }
    public String getNameSnapshot() { return nameSnapshot; }
    public String getHsnSnapshot() { return hsnSnapshot; }
    public String getUomSnapshot() { return uomSnapshot; }
    public BigDecimal getQty() { return qty; }
    public BigDecimal getRate() { return rate; }
    public BigDecimal getDiscountPct() { return discountPct; }
    public BigDecimal getGstRate() { return gstRate; }
    public BigDecimal getTaxableValue() { return taxableValue; }
    public BigDecimal getCgst() { return cgst; }
    public BigDecimal getSgst() { return sgst; }
    public BigDecimal getIgst() { return igst; }
    public BigDecimal getLineTotal() { return lineTotal; }
}
```

- [ ] **Step 6: Write the repositories**

`backend/src/main/java/com/easycrm/sales/QuotationRepository.java`:
```java
package com.easycrm.sales;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface QuotationRepository extends JpaRepository<Quotation, UUID> {

    @Transactional(readOnly = true)
    Page<Quotation> findByStatus(QuotationStatus status, Pageable pageable);

    @Transactional(readOnly = true)
    Page<Quotation> findByCustomerId(UUID customerId, Pageable pageable);
}
```
`backend/src/main/java/com/easycrm/sales/QuotationVersionRepository.java`:
```java
package com.easycrm.sales;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuotationVersionRepository extends JpaRepository<QuotationVersion, UUID> {

    @Transactional(readOnly = true)
    List<QuotationVersion> findByQuotationIdOrderByVersionNoAsc(UUID quotationId);

    @Transactional(readOnly = true)
    Optional<QuotationVersion> findByQuotationIdAndVersionNo(UUID quotationId, int versionNo);
}
```
`backend/src/main/java/com/easycrm/sales/QuotationItemRepository.java`:
```java
package com.easycrm.sales;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface QuotationItemRepository extends JpaRepository<QuotationItem, UUID> {

    @Transactional(readOnly = true)
    List<QuotationItem> findByVersionId(UUID versionId);

    void deleteByVersionId(UUID versionId);
}
```

- [ ] **Step 7: Write the repository + RLS test**

`backend/src/test/java/com/easycrm/sales/QuotationRepositoryTest.java`:
```java
package com.easycrm.sales;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuotationRepositoryTest extends IntegrationTest {
    @Autowired QuotationRepository quotations;
    @Autowired QuotationVersionRepository versions;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    @AfterEach void clear() { TenantContext.clear(); }

    private void asTenant(UUID t) {
        TenantContext.set(new TenantContext.TenantPrincipal(t, UUID.randomUUID(), "OWNER"));
    }

    @Test
    void savesQuotationAndVersionWithinTenant() {
        asTenant(UUID.randomUUID());
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Quotation q = quotations.save(new Quotation(UUID.randomUUID(), null));
            QuotationVersion v = versions.save(new QuotationVersion(q.getId(), 1, "27"));
            q.setCurrentVersionId(v.getId());
            assertThat(quotations.findById(q.getId())).isPresent();
            assertThat(versions.findByQuotationIdAndVersionNo(q.getId(), 1)).isPresent();
        });
    }

    @Test
    void findIsTenantScoped() {
        UUID a = UUID.randomUUID();
        asTenant(a);
        UUID savedId = new TransactionTemplate(txManager).execute(s ->
            quotations.save(new Quotation(UUID.randomUUID(), null)).getId());
        asTenant(UUID.randomUUID()); // different tenant
        assertThat(quotations.findById(savedId)).isEmpty();
    }

    @Test
    void rlsReturnsZeroRowsWithNoTenantSet() {
        UUID tenant = UUID.randomUUID();
        asTenant(tenant);
        new TransactionTemplate(txManager).executeWithoutResult(s ->
            quotations.save(new Quotation(UUID.randomUUID(), null)));
        TenantContext.clear();
        // Raw query with no app.current_tenant GUC set → RLS filters everything out.
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Number count = (Number) em.createNativeQuery("select count(*) from quotation").getSingleResult();
            assertThat(count.longValue()).isZero();
        });
    }
}
```
Note: mirror the exact RLS-zero-rows idiom used in the existing `CrossTenantIsolationIntegrationTest` / `RlsIntegrationTest` if the `EntityManager` native-query approach needs the non-owner datasource explicitly; those tests are the reference for reading "raw, no tenant" through the app role.

- [ ] **Step 8: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.QuotationRepositoryTest'`
Expected: PASS. If `ddl-auto: validate` fails at startup, a migration column type disagrees with an entity mapping — reconcile (e.g. `VARCHAR` vs `TEXT`, scale mismatches).

- [ ] **Step 9: Confirm ArchUnit still passes** (new entities must extend `TenantScopedEntity`)

Run: `cd backend && ./gradlew test --tests 'com.easycrm.arch.TenantScopingArchTest'`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
cd backend && git add src/main/resources/db/migration/V15__quotation.sql src/main/resources/db/migration/V16__quotation_version.sql src/main/resources/db/migration/V17__quotation_item.sql src/main/java/com/easycrm/sales/QuotationStatus.java src/main/java/com/easycrm/sales/VersionStatus.java src/main/java/com/easycrm/sales/Quotation.java src/main/java/com/easycrm/sales/QuotationVersion.java src/main/java/com/easycrm/sales/QuotationItem.java src/main/java/com/easycrm/sales/QuotationRepository.java src/main/java/com/easycrm/sales/QuotationVersionRepository.java src/main/java/com/easycrm/sales/QuotationItemRepository.java src/test/java/com/easycrm/sales/QuotationRepositoryTest.java && git commit -m "feat(sales): quotation aggregate persistence + RLS"
```

---

## Task 6: Create quotation (service + DTOs + controller create/get)

`POST /quotations` builds a DRAFT v1: resolves each line's default rate, snapshots product fields, computes the GST split against the customer's place-of-supply vs the tenant's state, stores totals. `GET /quotations/{id}` returns the quotation with its current version and items.

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/ItemRequest.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/QuotationCreateRequest.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/ItemResponse.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/QuotationVersionResponse.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/QuotationResponse.java`
- Create: `backend/src/main/java/com/easycrm/sales/QuotationService.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/QuotationController.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/QuotationControllerTest.java`

**Interfaces:**
- Consumes: `PriceResolver.resolve`, `GstCalculator.computeLine/totals`, `CustomerRepository.findById`, `TenantRepository.findById`, `TenantContext.tenantId()`, the three sales repositories.
- Produces (for Tasks 7–11): `QuotationService` with `create`, `get`, plus a shared private `QuotationResponse toResponse(Quotation)` mapping helper (loads current version + items); DTO records listed above.

- [ ] **Step 1: Write the request DTOs**

`backend/src/main/java/com/easycrm/sales/web/dto/ItemRequest.java`:
```java
package com.easycrm.sales.web.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

// rate is optional: null → resolved from the customer's price list. discountPct optional (0 if null).
public record ItemRequest(@NotNull UUID productId, @NotNull BigDecimal qty,
                          BigDecimal rate, BigDecimal discountPct) {}
```
`backend/src/main/java/com/easycrm/sales/web/dto/QuotationCreateRequest.java`:
```java
package com.easycrm.sales.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record QuotationCreateRequest(@NotNull UUID customerId, UUID enquiryId,
                                     LocalDate validUntil, String paymentTerms,
                                     String deliveryTerms, String notes,
                                     @NotEmpty @Valid List<ItemRequest> items) {}
```

- [ ] **Step 2: Write the response DTOs**

`backend/src/main/java/com/easycrm/sales/web/dto/ItemResponse.java`:
```java
package com.easycrm.sales.web.dto;

import com.easycrm.sales.QuotationItem;

import java.math.BigDecimal;
import java.util.UUID;

public record ItemResponse(UUID id, UUID productId, String name, String hsn, String uom,
                           BigDecimal qty, BigDecimal rate, BigDecimal discountPct, BigDecimal gstRate,
                           BigDecimal taxableValue, BigDecimal cgst, BigDecimal sgst, BigDecimal igst,
                           BigDecimal lineTotal) {

    public static ItemResponse of(QuotationItem i) {
        return new ItemResponse(i.getId(), i.getProductId(), i.getNameSnapshot(), i.getHsnSnapshot(),
            i.getUomSnapshot(), i.getQty(), i.getRate(), i.getDiscountPct(), i.getGstRate(),
            i.getTaxableValue(), i.getCgst(), i.getSgst(), i.getIgst(), i.getLineTotal());
    }
}
```
`backend/src/main/java/com/easycrm/sales/web/dto/QuotationVersionResponse.java`:
```java
package com.easycrm.sales.web.dto;

import com.easycrm.sales.QuotationItem;
import com.easycrm.sales.QuotationVersion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record QuotationVersionResponse(UUID id, int versionNo, String status, LocalDate validUntil,
                                       String paymentTerms, String deliveryTerms, String notes,
                                       String placeOfSupply, BigDecimal subTotal, BigDecimal totalTax,
                                       BigDecimal grandTotal, Instant sentAt, List<ItemResponse> items) {

    public static QuotationVersionResponse of(QuotationVersion v, List<QuotationItem> items) {
        return new QuotationVersionResponse(v.getId(), v.getVersionNo(), v.getStatus().name(),
            v.getValidUntil(), v.getPaymentTerms(), v.getDeliveryTerms(), v.getNotes(),
            v.getPlaceOfSupply(), v.getSubTotal(), v.getTotalTax(), v.getGrandTotal(), v.getSentAt(),
            items.stream().map(ItemResponse::of).toList());
    }
}
```
`backend/src/main/java/com/easycrm/sales/web/dto/QuotationResponse.java`:
```java
package com.easycrm.sales.web.dto;

import com.easycrm.sales.Quotation;

import java.util.UUID;

public record QuotationResponse(UUID id, String quoteNo, UUID customerId, UUID enquiryId,
                                String status, QuotationVersionResponse currentVersion) {

    public static QuotationResponse of(Quotation q, QuotationVersionResponse currentVersion) {
        return new QuotationResponse(q.getId(), q.getQuoteNo(), q.getCustomerId(), q.getEnquiryId(),
            q.getStatus().name(), currentVersion);
    }
}
```

- [ ] **Step 3: Write the failing controller test**

`backend/src/test/java/com/easycrm/sales/web/QuotationControllerTest.java`:
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class QuotationControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    // Creates a customer (state 27) and a product via the real APIs, returns {customerId, productId}.
    private String[] seed(String auth, String customerState) throws Exception {
        String cust = """
            {"businessName":"Acme","stateCode":"%s","source":"MANUAL"}""".formatted(customerState);
        String cBody = mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(cust))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String prod = """
            {"sku":"SKU-%s","name":"Widget","hsnCode":"84818090","uom":"PCS",
             "gstRate":"18","baseRate":"100.00"}""".formatted(UUID.randomUUID().toString().substring(0, 8));
        String pBody = mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(prod))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new String[]{JsonPath.read(cBody, "$.id"), JsonPath.read(pBody, "$.id")};
    }

    @Test
    void createsDraftWithResolvedRateAndIntraStateGst() throws Exception {
        UUID tenant = UUID.randomUUID();
        String auth = "Bearer " + tokens.owner(tenant); // tenant state defaults — see note below
        String[] ids = seed(auth, "27");
        String body = """
            {"customerId":"%s","items":[{"productId":"%s","qty":"2"}]}"""
            .formatted(ids[0], ids[1]);

        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.quoteNo").doesNotExist())
            .andExpect(jsonPath("$.currentVersion.versionNo").value(1))
            .andExpect(jsonPath("$.currentVersion.items[0].rate").value("100.00")) // resolved from base rate
            .andExpect(jsonPath("$.currentVersion.subTotal").value("200.00"));
    }

    @Test
    void rejectsEmptyItemsWith400() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String[] ids = seed(auth, "27");
        String body = """{"customerId":"%s","items":[]}""".formatted(ids[0]);
        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getReturns404ForOtherTenant() throws Exception {
        String authA = "Bearer " + tokens.owner(UUID.randomUUID());
        String[] ids = seed(authA, "27");
        String body = """{"customerId":"%s","items":[{"productId":"%s","qty":"1"}]}"""
            .formatted(ids[0], ids[1]);
        String qBody = mvc.perform(post("/api/v1/quotations").header("Authorization", authA)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(qBody, "$.id");

        String authB = "Bearer " + tokens.owner(UUID.randomUUID());
        mvc.perform(get("/api/v1/quotations/" + id).header("Authorization", authB))
            .andExpect(status().isNotFound());
    }
}
```
Note on tenant state: the GST split compares the customer's `state_code` against the tenant's. Confirm what `state_code` the tenant created by `tokens.owner(tenant)` has (inspect `TestTokens` / how the tenant row is provisioned in tests). If the test tenant's state is not `27`, set the customer's `stateCode` in `seed(...)` equal to the tenant's state so this test exercises the intra-state (CGST/SGST) path; add a second customer with a different state for the inter-state assertion. The subTotal assertion (200.00) is state-independent; the split assertions must match the chosen states.

- [ ] **Step 4: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationControllerTest'`
Expected: FAIL — no `QuotationController` / `QuotationService`.

- [ ] **Step 5: Write the service (create + get + shared mapping)**

`backend/src/main/java/com/easycrm/sales/QuotationService.java`:
```java
package com.easycrm.sales;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.web.dto.ItemRequest;
import com.easycrm.sales.web.dto.QuotationCreateRequest;
import com.easycrm.sales.web.dto.QuotationResponse;
import com.easycrm.sales.web.dto.QuotationVersionResponse;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class QuotationService {

    private final QuotationRepository quotations;
    private final QuotationVersionRepository versions;
    private final QuotationItemRepository items;
    private final CustomerRepository customers;
    private final TenantRepository tenants;
    private final PriceResolver priceResolver;

    public QuotationService(QuotationRepository quotations, QuotationVersionRepository versions,
                            QuotationItemRepository items, CustomerRepository customers,
                            TenantRepository tenants, PriceResolver priceResolver) {
        this.quotations = quotations;
        this.versions = versions;
        this.items = items;
        this.customers = customers;
        this.tenants = tenants;
        this.priceResolver = priceResolver;
    }

    @Transactional
    public QuotationResponse create(QuotationCreateRequest req) {
        Customer customer = customers.findById(req.customerId())
            .orElseThrow(() -> new NotFoundException("customer not found"));
        boolean interState = isInterState(customer.getStateCode());

        Quotation quotation = quotations.save(new Quotation(req.customerId(), req.enquiryId()));
        QuotationVersion version = versions.save(
            new QuotationVersion(quotation.getId(), 1, customer.getStateCode()));
        version.setHeader(req.validUntil(), req.paymentTerms(), req.deliveryTerms(), req.notes());
        buildItems(version, req.customerId(), req.items(), interState);
        quotation.setCurrentVersionId(version.getId());
        return toResponse(quotation);
    }

    @Transactional(readOnly = true)
    public QuotationResponse get(UUID id) {
        return toResponse(findQuotation(id));
    }

    // --- shared helpers used by later tasks (edit/send/revise) ---

    /** Recomputes item lines + version totals from the given item requests. Assumes DRAFT. */
    void buildItems(QuotationVersion version, UUID customerId, List<ItemRequest> itemReqs, boolean interState) {
        List<GstCalculator.LineResult> lineResults = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        int idx = 0;
        for (ItemRequest ir : itemReqs) {
            if (ir.qty() == null || ir.qty().compareTo(BigDecimal.ZERO) <= 0) {
                errors.put("items[" + idx + "].qty", "quantity must be greater than zero");
            }
            if (ir.discountPct() != null
                    && (ir.discountPct().compareTo(BigDecimal.ZERO) < 0
                        || ir.discountPct().compareTo(new BigDecimal("100")) > 0)) {
                errors.put("items[" + idx + "].discountPct", "discount must be between 0 and 100");
            }
            idx++;
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);

        for (ItemRequest ir : itemReqs) {
            PriceResolver.Resolved r = priceResolver.resolve(customerId, ir.productId());
            BigDecimal rate = ir.rate() != null ? ir.rate() : r.rate();
            BigDecimal discount = ir.discountPct() != null ? ir.discountPct() : BigDecimal.ZERO;
            GstCalculator.LineResult lr = GstCalculator.computeLine(
                new GstCalculator.LineInput(ir.qty(), rate, discount, r.gstRate()), interState);
            lineResults.add(lr);
            items.save(new QuotationItem(version.getId(), ir.productId(), r.name(), r.hsn(), r.uom(),
                ir.qty(), rate, discount, r.gstRate(), lr.taxableValue(), lr.cgst(), lr.sgst(),
                lr.igst(), lr.lineTotal()));
        }
        GstCalculator.Totals t = GstCalculator.totals(lineResults);
        version.setTotals(t.subTotal(), t.totalTax(), t.grandTotal());
    }

    boolean isInterState(String customerStateCode) {
        Tenant tenant = tenants.findById(TenantContext.tenantId())
            .orElseThrow(() -> new NotFoundException("tenant not found"));
        return !tenant.getStateCode().equals(customerStateCode);
    }

    Quotation findQuotation(UUID id) {
        return quotations.findById(id).orElseThrow(() -> new NotFoundException("quotation not found"));
    }

    QuotationResponse toResponse(Quotation q) {
        QuotationVersion v = versions.findById(q.getCurrentVersionId())
            .orElseThrow(() -> new NotFoundException("quotation version not found"));
        var itemList = items.findByVersionId(v.getId());
        return QuotationResponse.of(q, QuotationVersionResponse.of(v, itemList));
    }
}
```
Note: `buildItems` takes an explicit `UUID customerId` so both `create` (Task 6) and `replaceItems` (Task 8) can call it with the customer already in hand — no lookup indirection. It is package-private (not `private`) precisely so Task 8 reuses it.

- [ ] **Step 6: Write the controller**

`backend/src/main/java/com/easycrm/sales/web/QuotationController.java`:
```java
package com.easycrm.sales.web;

import com.easycrm.sales.QuotationService;
import com.easycrm.sales.web.dto.QuotationCreateRequest;
import com.easycrm.sales.web.dto.QuotationResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/quotations")
public class QuotationController {

    private final QuotationService service;

    public QuotationController(QuotationService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<QuotationResponse> create(@Valid @RequestBody QuotationCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    public QuotationResponse get(@PathVariable UUID id) { return service.get(id); }
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationControllerTest'`
Expected: PASS. Adjust the state-split assertions per the Step 3 note once you know the test tenant's `state_code`.

- [ ] **Step 8: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/sales/web src/main/java/com/easycrm/sales/QuotationService.java src/test/java/com/easycrm/sales/web/QuotationControllerTest.java && git commit -m "feat(sales): create + get quotation with GST and price resolution"
```

---

## Task 7: List quotations + version history

`GET /quotations` (paginated, `status`/`customerId` filters) and version-history reads.

**Files:**
- Modify: `backend/src/main/java/com/easycrm/sales/QuotationService.java` (add `list`, `getVersions`, `getVersion`)
- Modify: `backend/src/main/java/com/easycrm/sales/web/QuotationController.java` (add the three endpoints)
- Test: `backend/src/test/java/com/easycrm/sales/web/QuotationListTest.java`

**Interfaces:**
- Produces: `QuotationService.list(QuotationStatus status, UUID customerId, Pageable) -> PageResponse<QuotationResponse>`, `getVersions(UUID) -> List<QuotationVersionResponse>`, `getVersion(UUID, int) -> QuotationVersionResponse`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/easycrm/sales/web/QuotationListTest.java`:
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class QuotationListTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String createQuotation(String auth) throws Exception {
        String cust = """{"businessName":"Acme","stateCode":"27","source":"MANUAL"}""";
        String cBody = mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(cust))
            .andReturn().getResponse().getContentAsString();
        String prod = """
            {"sku":"SKU-%s","name":"W","hsnCode":"84818090","uom":"PCS","gstRate":"18","baseRate":"100.00"}"""
            .formatted(UUID.randomUUID().toString().substring(0, 8));
        String pBody = mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(prod))
            .andReturn().getResponse().getContentAsString();
        String body = """{"customerId":"%s","items":[{"productId":"%s","qty":"1"}]}"""
            .formatted((String) JsonPath.read(cBody, "$.id"), (String) JsonPath.read(pBody, "$.id"));
        return mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getContentAsString();
    }

    @Test
    void listsQuotationsForTenant() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        createQuotation(auth);
        mvc.perform(get("/api/v1/quotations").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getsVersionByNumber() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String qBody = createQuotation(auth);
        String id = JsonPath.read(qBody, "$.id");
        mvc.perform(get("/api/v1/quotations/" + id + "/versions/1").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.versionNo").value(1))
            .andExpect(jsonPath("$.items[0].qty").value("1.000"));
        mvc.perform(get("/api/v1/quotations/" + id + "/versions").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].versionNo").value(1));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationListTest'`
Expected: FAIL — endpoints/methods don't exist.

- [ ] **Step 3: Add the service methods** (append to `QuotationService`, imports: `com.easycrm.platform.web.PageResponse`, `org.springframework.data.domain.Page`, `Pageable`, `java.util.List`)

```java
    @Transactional(readOnly = true)
    public PageResponse<QuotationResponse> list(QuotationStatus status, UUID customerId, Pageable pageable) {
        Page<Quotation> page;
        if (status != null) page = quotations.findByStatus(status, pageable);
        else if (customerId != null) page = quotations.findByCustomerId(customerId, pageable);
        else page = quotations.findAll(pageable);
        return PageResponse.of(page.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public List<QuotationVersionResponse> getVersions(UUID quotationId) {
        findQuotation(quotationId); // 404 if not visible
        return versions.findByQuotationIdOrderByVersionNoAsc(quotationId).stream()
            .map(v -> QuotationVersionResponse.of(v, items.findByVersionId(v.getId())))
            .toList();
    }

    @Transactional(readOnly = true)
    public QuotationVersionResponse getVersion(UUID quotationId, int versionNo) {
        findQuotation(quotationId);
        QuotationVersion v = versions.findByQuotationIdAndVersionNo(quotationId, versionNo)
            .orElseThrow(() -> new NotFoundException("quotation version not found"));
        return QuotationVersionResponse.of(v, items.findByVersionId(v.getId()));
    }
```

- [ ] **Step 4: Add the controller endpoints** (append to `QuotationController`, imports: `PageResponse`, `QuotationVersionResponse`, `QuotationStatus`, `RequestParam`, `Pageable`, `List`)

```java
    @GetMapping
    public PageResponse<QuotationResponse> list(
            @RequestParam(required = false) QuotationStatus status,
            @RequestParam(required = false) UUID customerId,
            Pageable pageable) {
        return service.list(status, customerId, pageable);
    }

    @GetMapping("/{id}/versions")
    public List<QuotationVersionResponse> versions(@PathVariable UUID id) {
        return service.getVersions(id);
    }

    @GetMapping("/{id}/versions/{versionNo}")
    public QuotationVersionResponse version(@PathVariable UUID id, @PathVariable int versionNo) {
        return service.getVersion(id, versionNo);
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationListTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/sales/QuotationService.java src/main/java/com/easycrm/sales/web/QuotationController.java src/test/java/com/easycrm/sales/web/QuotationListTest.java && git commit -m "feat(sales): list quotations + version history endpoints"
```

---

## Task 8: Edit DRAFT (header + items) with the frozen-version guard

`PATCH /quotations/{id}` edits header fields; `PUT /quotations/{id}/items` replaces the current version's line items and recomputes totals. Both reject a non-DRAFT parent with 422 (the immutability invariant).

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/QuotationHeaderRequest.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/ItemsRequest.java`
- Modify: `backend/src/main/java/com/easycrm/sales/QuotationService.java` (add `patchHeader`, `replaceItems`, a `requireDraft` guard)
- Modify: `backend/src/main/java/com/easycrm/sales/web/QuotationController.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/QuotationEditTest.java`

**Interfaces:**
- Produces: `QuotationService.patchHeader(UUID, QuotationHeaderRequest)`, `QuotationService.replaceItems(UUID, ItemsRequest)`, private `requireDraft(Quotation)`.

- [ ] **Step 1: Write the request DTOs**

`backend/src/main/java/com/easycrm/sales/web/dto/QuotationHeaderRequest.java`:
```java
package com.easycrm.sales.web.dto;

import java.time.LocalDate;

public record QuotationHeaderRequest(LocalDate validUntil, String paymentTerms,
                                     String deliveryTerms, String notes) {}
```
`backend/src/main/java/com/easycrm/sales/web/dto/ItemsRequest.java`:
```java
package com.easycrm.sales.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ItemsRequest(@NotEmpty @Valid List<ItemRequest> items) {}
```

- [ ] **Step 2: Write the failing test**

`backend/src/test/java/com/easycrm/sales/web/QuotationEditTest.java`:
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
class QuotationEditTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String[] seedIds(String auth) throws Exception {
        String cust = """{"businessName":"Acme","stateCode":"27","source":"MANUAL"}""";
        String cBody = mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(cust))
            .andReturn().getResponse().getContentAsString();
        String prod = """
            {"sku":"SKU-%s","name":"W","hsnCode":"84818090","uom":"PCS","gstRate":"18","baseRate":"100.00"}"""
            .formatted(UUID.randomUUID().toString().substring(0, 8));
        String pBody = mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(prod))
            .andReturn().getResponse().getContentAsString();
        return new String[]{JsonPath.read(cBody, "$.id"), JsonPath.read(pBody, "$.id")};
    }

    private String createDraft(String auth, String[] ids) throws Exception {
        String body = """{"customerId":"%s","items":[{"productId":"%s","qty":"1"}]}"""
            .formatted(ids[0], ids[1]);
        return JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void replacesItemsAndRecomputesTotals() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String[] ids = seedIds(auth);
        String id = createDraft(auth, ids);
        String items = """{"items":[{"productId":"%s","qty":"5"}]}""".formatted(ids[1]);
        mvc.perform(put("/api/v1/quotations/" + id + "/items").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(items))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentVersion.subTotal").value("500.00"));
    }

    @Test
    void editingItemsOnSentVersionReturns422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String[] ids = seedIds(auth);
        String id = createDraft(auth, ids);
        mvc.perform(post("/api/v1/quotations/" + id + "/send").header("Authorization", auth))
            .andExpect(status().isOk());
        String items = """{"items":[{"productId":"%s","qty":"9"}]}""".formatted(ids[1]);
        mvc.perform(put("/api/v1/quotations/" + id + "/items").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(items))
            .andExpect(status().isUnprocessableEntity());
    }
}
```
Note: `editingItemsOnSentVersionReturns422` depends on the `/send` endpoint from Task 9. If executing strictly in order, either land Task 9 first or temporarily mark this one test `@Disabled("needs Task 9 send")` and re-enable it in Task 9's step. The `replacesItemsAndRecomputesTotals` test stands alone.

- [ ] **Step 3: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationEditTest'`
Expected: FAIL — no PATCH/PUT endpoints.

- [ ] **Step 4: Add service methods** (append to `QuotationService`)

```java
    @Transactional
    public QuotationResponse patchHeader(UUID id, QuotationHeaderRequest req) {
        Quotation q = findQuotation(id);
        QuotationVersion v = requireDraft(q);
        v.setHeader(req.validUntil(), req.paymentTerms(), req.deliveryTerms(), req.notes());
        return toResponse(q);
    }

    @Transactional
    public QuotationResponse replaceItems(UUID id, ItemsRequest req) {
        Quotation q = findQuotation(id);
        QuotationVersion v = requireDraft(q);
        items.deleteByVersionId(v.getId());
        Customer customer = customers.findById(q.getCustomerId())
            .orElseThrow(() -> new NotFoundException("customer not found"));
        buildItems(v, q.getCustomerId(), req.items(), isInterState(customer.getStateCode()));
        return toResponse(q);
    }

    /** The current version must be an editable DRAFT; a SENT (frozen) version is immutable. */
    private QuotationVersion requireDraft(Quotation q) {
        if (q.getStatus() != QuotationStatus.DRAFT) {
            throw new ValidationException("status", "only a draft quotation can be edited");
        }
        return versions.findById(q.getCurrentVersionId())
            .orElseThrow(() -> new NotFoundException("quotation version not found"));
    }
```
Note: `buildItems(QuotationVersion, UUID customerId, List<ItemRequest>, boolean)` is the same package-private helper written in Task 6 — reuse it as-is. Add imports `QuotationHeaderRequest`, `ItemsRequest` to the service.

- [ ] **Step 5: Add controller endpoints** (append to `QuotationController`; imports `QuotationHeaderRequest`, `ItemsRequest`, `PatchMapping`, `PutMapping`)

```java
    @PatchMapping("/{id}")
    public QuotationResponse patch(@PathVariable UUID id,
                                   @Valid @RequestBody QuotationHeaderRequest req) {
        return service.patchHeader(id, req);
    }

    @PutMapping("/{id}/items")
    public QuotationResponse replaceItems(@PathVariable UUID id,
                                          @Valid @RequestBody ItemsRequest req) {
        return service.replaceItems(id, req);
    }
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationEditTest'`
Expected: PASS (with the `@Disabled` note from Step 2 if Task 9 isn't landed yet).

- [ ] **Step 7: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/sales src/test/java/com/easycrm/sales/web/QuotationEditTest.java && git commit -m "feat(sales): edit draft quotation header + items with frozen-version guard"
```

---

## Task 9: Send (freeze + gapless number)

`POST /quotations/{id}/send` freezes the current version, assigns a gapless `quote_no`, sets status SENT, stamps `sent_at`. Sending a non-DRAFT quotation is 422.

**Files:**
- Modify: `backend/src/main/java/com/easycrm/sales/QuotationService.java` (add `send`, inject `DocumentNumberService`)
- Modify: `backend/src/main/java/com/easycrm/sales/web/QuotationController.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/QuotationSendTest.java`

**Interfaces:**
- Consumes: `DocumentNumberService.nextQuoteNo(LocalDate)`.
- Produces: `QuotationService.send(UUID) -> QuotationResponse`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/easycrm/sales/web/QuotationSendTest.java`:
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
class QuotationSendTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String createDraft(String auth) throws Exception {
        String cust = """{"businessName":"Acme","stateCode":"27","source":"MANUAL"}""";
        String cId = JsonPath.read(mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(cust))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String prod = """
            {"sku":"SKU-%s","name":"W","hsnCode":"84818090","uom":"PCS","gstRate":"18","baseRate":"100.00"}"""
            .formatted(UUID.randomUUID().toString().substring(0, 8));
        String pId = JsonPath.read(mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(prod))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String body = """{"customerId":"%s","items":[{"productId":"%s","qty":"1"}]}""".formatted(cId, pId);
        return JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void sendAssignsGaplessNumberAndFreezes() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String id1 = createDraft(auth);
        String id2 = createDraft(auth);

        mvc.perform(post("/api/v1/quotations/" + id1 + "/send").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SENT"))
            .andExpect(jsonPath("$.quoteNo").value(matchesPattern("QT/\\d{2}-\\d{2}/0001")))
            .andExpect(jsonPath("$.currentVersion.status").value("SENT"))
            .andExpect(jsonPath("$.currentVersion.sentAt").exists());

        mvc.perform(post("/api/v1/quotations/" + id2 + "/send").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.quoteNo").value(matchesPattern("QT/\\d{2}-\\d{2}/0002")));
    }

    @Test
    void sendingAlreadySentReturns422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String id = createDraft(auth);
        mvc.perform(post("/api/v1/quotations/" + id + "/send").header("Authorization", auth))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/quotations/" + id + "/send").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationSendTest'`
Expected: FAIL — no `/send` endpoint.

- [ ] **Step 3: Add `DocumentNumberService` to the service constructor and add `send`**

In `QuotationService`, add the field/constructor param `private final DocumentNumberService documentNumbers;` (wire it in the constructor), and add imports `java.time.Instant`, `java.time.LocalDate`. Then:
```java
    @Transactional
    public QuotationResponse send(UUID id) {
        Quotation q = findQuotation(id);
        if (q.getStatus() != QuotationStatus.DRAFT) {
            throw new ValidationException("status", "only a draft quotation can be sent");
        }
        QuotationVersion v = versions.findById(q.getCurrentVersionId())
            .orElseThrow(() -> new NotFoundException("quotation version not found"));
        q.assignQuoteNo(documentNumbers.nextQuoteNo(LocalDate.now()));
        q.markSent();
        v.markSent(Instant.now());
        return toResponse(q);
    }
```

- [ ] **Step 4: Add the controller endpoint** (append to `QuotationController`)

```java
    @PostMapping("/{id}/send")
    public QuotationResponse send(@PathVariable UUID id) { return service.send(id); }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationSendTest'`
Expected: PASS. If you `@Disabled` the frozen-version test in Task 8, re-enable it now and run `./gradlew test --tests 'com.easycrm.sales.web.QuotationEditTest'` — expect PASS.

- [ ] **Step 6: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/sales src/test/java/com/easycrm/sales/web/QuotationSendTest.java src/test/java/com/easycrm/sales/web/QuotationEditTest.java && git commit -m "feat(sales): send quotation — freeze version + assign gapless quote number"
```

---

## Task 10: Revise (spawn a new DRAFT version from a SENT quote)

`POST /quotations/{id}/revise` creates version N+1 as a DRAFT, copying the previous version's header + items, repoints `current_version_id`, sets the quotation back to DRAFT. `quote_no` is retained.

**Files:**
- Modify: `backend/src/main/java/com/easycrm/sales/QuotationService.java` (add `revise`)
- Modify: `backend/src/main/java/com/easycrm/sales/web/QuotationController.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/QuotationReviseTest.java`

**Interfaces:**
- Produces: `QuotationService.revise(UUID) -> QuotationResponse`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/easycrm/sales/web/QuotationReviseTest.java`:
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
class QuotationReviseTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String createAndSend(String auth) throws Exception {
        String cust = """{"businessName":"Acme","stateCode":"27","source":"MANUAL"}""";
        String cId = JsonPath.read(mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(cust))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String prod = """
            {"sku":"SKU-%s","name":"W","hsnCode":"84818090","uom":"PCS","gstRate":"18","baseRate":"100.00"}"""
            .formatted(UUID.randomUUID().toString().substring(0, 8));
        String pId = JsonPath.read(mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(prod))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String body = """{"customerId":"%s","items":[{"productId":"%s","qty":"2"}]}""".formatted(cId, pId);
        String id = JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/quotations/" + id + "/send").header("Authorization", auth))
            .andExpect(status().isOk());
        return id;
    }

    @Test
    void reviseSpawnsDraftV2CopyingItemsKeepingQuoteNo() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String id = createAndSend(auth);
        String sentBody = mvc.perform(get("/api/v1/quotations/" + id).header("Authorization", auth))
            .andReturn().getResponse().getContentAsString();
        String quoteNo = JsonPath.read(sentBody, "$.quoteNo");

        mvc.perform(post("/api/v1/quotations/" + id + "/revise").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.quoteNo").value(quoteNo)) // number retained
            .andExpect(jsonPath("$.currentVersion.versionNo").value(2))
            .andExpect(jsonPath("$.currentVersion.status").value("DRAFT"))
            .andExpect(jsonPath("$.currentVersion.items[0].qty").value("2.000")) // copied
            .andExpect(jsonPath("$.currentVersion.subTotal").value("200.00"));

        // v1 is preserved and still SENT.
        mvc.perform(get("/api/v1/quotations/" + id + "/versions/1").header("Authorization", auth))
            .andExpect(jsonPath("$.status").value("SENT"));
    }

    @Test
    void revisingADraftReturns422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String id = createAndSend(auth);
        mvc.perform(post("/api/v1/quotations/" + id + "/revise").header("Authorization", auth))
            .andExpect(status().isOk()); // now DRAFT (v2)
        mvc.perform(post("/api/v1/quotations/" + id + "/revise").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity()); // can't revise a draft
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationReviseTest'`
Expected: FAIL — no `/revise` endpoint.

- [ ] **Step 3: Add the service method** (append to `QuotationService`)

```java
    @Transactional
    public QuotationResponse revise(UUID id) {
        Quotation q = findQuotation(id);
        if (q.getStatus() != QuotationStatus.SENT) {
            throw new ValidationException("status", "only a sent quotation can be revised");
        }
        QuotationVersion prev = versions.findById(q.getCurrentVersionId())
            .orElseThrow(() -> new NotFoundException("quotation version not found"));
        QuotationVersion next = versions.save(
            new QuotationVersion(q.getId(), prev.getVersionNo() + 1, prev.getPlaceOfSupply()));
        next.setHeader(prev.getValidUntil(), prev.getPaymentTerms(),
                       prev.getDeliveryTerms(), prev.getNotes());
        // Copy the previous version's frozen items verbatim (already-computed values).
        for (QuotationItem s : items.findByVersionId(prev.getId())) {
            items.save(new QuotationItem(next.getId(), s.getProductId(), s.getNameSnapshot(),
                s.getHsnSnapshot(), s.getUomSnapshot(), s.getQty(), s.getRate(), s.getDiscountPct(),
                s.getGstRate(), s.getTaxableValue(), s.getCgst(), s.getSgst(), s.getIgst(),
                s.getLineTotal()));
        }
        next.setTotals(prev.getSubTotal(), prev.getTotalTax(), prev.getGrandTotal());
        q.setCurrentVersionId(next.getId());
        q.reviseToDraft();
        return toResponse(q);
    }
```
Add to `Quotation` a `reviseToDraft()` method (status back to DRAFT without clearing `quoteNo`):
```java
    public void reviseToDraft() { this.status = QuotationStatus.DRAFT; }
```

- [ ] **Step 4: Add the controller endpoint** (append to `QuotationController`)

```java
    @PostMapping("/{id}/revise")
    public QuotationResponse revise(@PathVariable UUID id) { return service.revise(id); }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationReviseTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/sales src/test/java/com/easycrm/sales/web/QuotationReviseTest.java && git commit -m "feat(sales): revise sent quotation into a new draft version"
```

---

## Task 11: Reject / expire (manual terminal transitions)

`POST /quotations/{id}/reject` and `POST /quotations/{id}/expire` move a SENT quotation to a terminal state. Any other source state → 422.

**Files:**
- Modify: `backend/src/main/java/com/easycrm/sales/QuotationService.java` (add `reject`, `expire`)
- Modify: `backend/src/main/java/com/easycrm/sales/web/QuotationController.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/QuotationTransitionTest.java`

**Interfaces:**
- Produces: `QuotationService.reject(UUID)`, `QuotationService.expire(UUID)`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/easycrm/sales/web/QuotationTransitionTest.java`:
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
class QuotationTransitionTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String createDraft(String auth) throws Exception {
        String cust = """{"businessName":"Acme","stateCode":"27","source":"MANUAL"}""";
        String cId = JsonPath.read(mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(cust))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String prod = """
            {"sku":"SKU-%s","name":"W","hsnCode":"84818090","uom":"PCS","gstRate":"18","baseRate":"100.00"}"""
            .formatted(UUID.randomUUID().toString().substring(0, 8));
        String pId = JsonPath.read(mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(prod))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String body = """{"customerId":"%s","items":[{"productId":"%s","qty":"1"}]}""".formatted(cId, pId);
        return JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void rejectMovesSentToRejected() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String id = createDraft(auth);
        mvc.perform(post("/api/v1/quotations/" + id + "/send").header("Authorization", auth));
        mvc.perform(post("/api/v1/quotations/" + id + "/reject").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void expireOnDraftReturns422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String id = createDraft(auth);
        mvc.perform(post("/api/v1/quotations/" + id + "/expire").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationTransitionTest'`
Expected: FAIL — no `/reject` `/expire` endpoints.

- [ ] **Step 3: Add the service methods** (append to `QuotationService`)

```java
    @Transactional
    public QuotationResponse reject(UUID id) {
        Quotation q = findQuotation(id);
        requireSent(q, "rejected");
        q.reject();
        return toResponse(q);
    }

    @Transactional
    public QuotationResponse expire(UUID id) {
        Quotation q = findQuotation(id);
        requireSent(q, "expired");
        q.expire();
        return toResponse(q);
    }

    private void requireSent(Quotation q, String verb) {
        if (q.getStatus() != QuotationStatus.SENT) {
            throw new ValidationException("status", "only a sent quotation can be " + verb);
        }
    }
```

- [ ] **Step 4: Add the controller endpoints** (append to `QuotationController`)

```java
    @PostMapping("/{id}/reject")
    public QuotationResponse reject(@PathVariable UUID id) { return service.reject(id); }

    @PostMapping("/{id}/expire")
    public QuotationResponse expire(@PathVariable UUID id) { return service.expire(id); }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationTransitionTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd backend && git add src/main/java/com/easycrm/sales src/test/java/com/easycrm/sales/web/QuotationTransitionTest.java && git commit -m "feat(sales): manual reject/expire transitions"
```

---

## Task 12: Full-suite green, challenges log, annotations reference

Final task: prove the whole module hangs together, then satisfy the working agreements (challenges log + annotations reference) in one commit.

**Files:**
- Modify: `docs/superpowers/engineering-challenges.md`
- Modify: `docs/superpowers/annotations-reference.md`
- Modify: `docs/superpowers/HANDOFF.md`

- [ ] **Step 1: Run the whole suite**

Run: `cd backend && ./gradlew clean test`
Expected: PASS — all P0/P0-auth/P1a tests (86) plus the new P1b tests. Record the new total.

- [ ] **Step 2: Append engineering-challenges entries** (use the template at the bottom of the file; one entry each, Problem → why hard → Solution → Lesson):
  1. **Gapless per-tenant/FY document numbering under concurrency + rollback** — naive `MAX(quote_no)+1` races and a per-tenant DB sequence can't reset per FY nor stay gapless; solved with a locked `document_counter` row (`SELECT … FOR UPDATE`) incremented inside the send transaction, so concurrent sends serialize and a rolled-back send burns no number. Note the residual first-insert race (two concurrent first-ever sends in a tenant/FY → one hits the unique constraint → 409 backstop → retry succeeds; still gapless).
  2. **Global BigDecimal-as-string on Jackson 3 / Boot 4** — money as a JSON number re-introduces the `double` rounding error; solved with a `tools.jackson.databind` `ValueSerializer`/`SimpleModule` registered as a `JacksonModule` bean, `toPlainString()` to avoid scientific notation. Note the Jackson-3 package move (challenge #10 cross-ref).
  3. **Mutable-DRAFT / frozen-SENT version invariant** — "immutable snapshot" vs "traders revise 3–4×" reconciled by freezing a version only on SEND; the aggregate root guards every write path (`requireDraft`) and revise copies the frozen items into a fresh DRAFT vN+1, so the sent record is never mutated. (Only log the ones that were genuinely non-obvious during implementation; skip any that turned out routine.)

- [ ] **Step 3: Update the annotations reference** — add rows for any annotation new to the codebase that P1b introduced: `@Lock` (`org.springframework.data.jpa.repository.Lock`) + `LockModeType.PESSIMISTIC_WRITE`, `@Param`, and (if not already present) `@PatchMapping`. Confirm each is actually new before adding (grep the reference first).

- [ ] **Step 4: Update HANDOFF.md** — mark P1b (quotation engine slice) done/merged-pending, update the test count, and note what remains deferred (enquiry, order/accept, PDF, wa.me, auto-expiry, visibility, cursor pagination).

- [ ] **Step 5: Commit**

```bash
cd backend && cd .. && git add docs/superpowers && git commit -m "docs: log P1b challenges, annotations, handoff update"
```

- [ ] **Step 6: Finish the branch** — invoke `superpowers:finishing-a-development-branch` to choose merge/PR/cleanup.

---

## Self-review notes (author)

- **Spec coverage:** money-wire (T1), GST calc (T2), gapless numbering (T3), price resolution (T4), aggregate + RLS (T5), create/get (T6), list/versions (T7), edit + frozen guard (T8), send (T9), revise (T10), reject/expire (T11), cross-tenant 404 (T6 `getReturns404ForOtherTenant`), immutability (T8), concurrency/rollback numbering (T3), challenges/annotations (T12). All spec §2/§4/§5/§6/§7 items map to a task.
- **Deferred, deliberately absent:** enquiry entity, order/accept/`ACCEPTED`/event/idempotency, PDF, wa.me, scheduled auto-expiry, visibility filtering, cursor pagination — none appear as tasks (matches spec §2 out-of-scope).
- **Type consistency:** `buildItems(QuotationVersion, UUID customerId, List<ItemRequest>, boolean)` is defined once in Task 6 (package-private) and reused verbatim in Task 8. `QuotationVersionResponse.of(v, items)` and `QuotationResponse.of(q, currentVersion)` are used identically across T6/T7/T10. `nextQuoteNo(LocalDate)` and `financialYear(LocalDate)` match T3↔T9. `Quotation` gains `reviseToDraft()` in T10; `markSent/reject/expire/assignQuoteNo/setCurrentVersionId` all defined in T5.
- **Known executor watch-points (flagged inline, not placeholders):** (a) the test tenant's `state_code` must be checked so GST intra/inter-state assertions match (T6 note); (b) confirm `Customer`/`PriceList` constructor signatures before running T4/T6 seeds; (c) the RLS-zero-rows idiom should mirror the existing `RlsIntegrationTest` (T5 note); (d) T8's frozen-version test depends on T9's `/send` (ordering note).
