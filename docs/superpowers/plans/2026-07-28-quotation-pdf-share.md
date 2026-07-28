# Quotation PDF + `wa.me` Share Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render a frozen quotation version as a server-side PDF, and let a salesperson hand it to a customer through a public tokenized link carried in a `wa.me` deep link.

**Architecture:** A Thymeleaf XHTML template is rendered to PDF bytes by openhtmltopdf, from data read out of the already-immutable `QuotationVersion` + `QuotationItem` snapshot — nothing is recomputed and nothing is stored. Sharing mints one row in a **global, RLS-exempt** `share_link` table (token → tenant + version), because a public request carries no JWT and therefore no tenant; the public endpoint resolves that row and then re-enters normal tenant-scoped loading via `TenantContext.runAs`.

**Tech Stack:** Java 25, Spring Boot 4.1, Hibernate 7, PostgreSQL + RLS, Flyway, Thymeleaf, openhtmltopdf (PDFBox backend), Testcontainers, JUnit 5, jayway JsonPath.

**Design spec:** `docs/superpowers/specs/2026-07-28-quotation-pdf-share-design.md` — read it before starting.

## Global Constraints

- **Money is never a `double`.** `BigDecimal` in Java, `NUMERIC` in Postgres, JSON **string** on the wire. Values on `QuotationVersion`/`QuotationItem` are already rounded per-line and summed; the PDF **formats** them and recomputes nothing.
- **Currency renders as `Rs. 1,23,456.78`** — Indian digit grouping, never the `₹` glyph (U+20B9 is absent from the base-14 PDF fonts and no font is embedded in this slice).
- **Tenant isolation is structural.** Never hand-write `WHERE tenant_id = ?`. Rely on Hibernate `@TenantId` + Postgres RLS. Exactly one new table (`share_link`) is global, and it must be added to the ArchUnit `GLOBAL_TABLES` allowlist or the build fails.
- **The share token must never reach logs, access logs, or exception messages.** Keep it out of every `toString()` and every error response body.
- **Only a SENT (frozen) version is renderable or shareable.** A DRAFT has no quote number.
- **Cross-tenant access returns 404**, never 403. An unknown or malformed public token also returns 404.
- **TDD, one task per commit.** Failing test → run to confirm it fails → minimal implementation → run to confirm it passes → commit.
- **Commits author as `divyam <divyam.0444@gmail.com>`** (plain `git commit`). Never add a `Co-Authored-By: Claude` trailer or mention Claude/AI in a commit message.
- **Tests that touch quotation flows must use `tokens.provisionOwner("<stateCode>")`**, not `tokens.owner(UUID.randomUUID())` — the letterhead and the GST split both read a real `Tenant` row.
- **Run tests with** `cd backend && ./gradlew test`. Docker must be running (`open -a Docker`, then wait for `docker info`). Baseline before starting: **187 tests, 0 failures**.
- **Migrations are sequential**: this slice adds `V24__tenant_profile.sql` and `V25__share_link.sql`. `ddl-auto: validate` is on, so migration column types must match entity mappings exactly.

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `platform/pdf/PdfEngine.java` | XHTML string → deterministic PDF bytes. Knows nothing about quotations. |
| `platform/format/IndianFormats.java` | Rupee/quantity/percent/date formatting. Pure functions, no Spring. |
| `platform/error/ForbiddenException.java` | 403, for the OWNER-only tenant profile endpoint. |
| `tenant/TenantService.java` | Read + update the seller profile; enforces OWNER. |
| `tenant/web/TenantController.java` | `GET`/`PATCH /api/v1/tenant`. |
| `tenant/web/dto/TenantProfileRequest.java`, `TenantResponse.java` | Wire DTOs. |
| `sales/pdf/QuotationPdfData.java` | The renderer's input — a flat, self-contained view model. |
| `sales/pdf/QuotationPdfRenderer.java` | `QuotationPdfData` → PDF bytes via template + `PdfEngine`. |
| `sales/pdf/QuotationPdfService.java` | Assembles `QuotationPdfData` from repositories; the two entry points. |
| `resources/templates/quotation.xhtml` | The document layout. |
| `sales/ShareLink.java`, `ShareLinkRepository.java` | Global token → tenant + version mapping. |
| `sales/ShareLinkService.java` | Idempotent mint, token resolution, `wa.me` composition. |
| `sales/web/dto/ShareResponse.java` | `{ publicUrl, waMeUrl }`. |
| `sales/web/PublicShareController.java` | `GET /public/q/{token}` — the only unauthenticated read path. |
| `sales/QuotationSpecifications.java` | AND-composed list filters. |
| `db/migration/V24__tenant_profile.sql`, `V25__share_link.sql` | Schema. |

**Modified:** `build.gradle.kts` (deps) · `application.yml` (public base URL) · `platform/tenancy/TenantContext.java` (`runAs` returning a value) · `platform/security/SecurityConfig.java` (`/public/**`) · `platform/error/ApiExceptionHandler.java` (403) · `tenant/Tenant.java` (profile fields) · `sales/QuotationService.java` + `QuotationRepository.java` (filter fix) · `sales/web/QuotationController.java` (pdf + share endpoints) · `test/.../arch/TenantScopingArchTest.java` (allowlist).

---

## Task 1: PDF engine spike — prove openhtmltopdf works and renders deterministically

This task exists to fail fast. openhtmltopdf is mature but old, and this stack has already been bitten by newness twice (Boot 4's auto-config module split; ArchUnit 1.3.0 silently skipping Java 25 bytecode). Nothing else is built until XHTML → PDF works on JDK 25 **and** two renders of the same input produce identical bytes.

**If openhtmltopdf cannot be made to work here, stop and report.** The fallback is OpenPDF programmatic composition (`org.librepdf:openpdf`), which changes Tasks 4–5 only; do not attempt that switch without checking in.

**Files:**
- Modify: `backend/build.gradle.kts`
- Create: `backend/src/main/java/com/easycrm/platform/pdf/PdfEngine.java`
- Test: `backend/src/test/java/com/easycrm/platform/pdf/PdfEngineTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `PdfEngine` (a `@Component`) with one method:
  `public byte[] render(String xhtml, java.time.Instant timestamp)` — `timestamp` pins the document's creation/modification date so output is a pure function of its inputs.

- [ ] **Step 1: Add the dependencies**

In `backend/build.gradle.kts`, add to the `dependencies` block, after the `spring-boot-starter-flyway` lines:

```kotlin
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    // XHTML -> PDF, pure Java (PDFBox backend). No external binary, so CI and
    // Testcontainers need nothing extra installed.
    implementation("com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10")
```

`1.0.10` (2021) is genuinely the latest published version — the project never cut a `1.1` line, and every module in the `com.openhtmltopdf` group tops out there. It pulls PDFBox 2.0.x, so the `PDDocument.load(byte[])` calls in this plan's tests are the correct API.

A 2021 library on JDK 25 is precisely why this task is a spike rather than an assumption.

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/easycrm/platform/pdf/PdfEngineTest.java`:

```java
package com.easycrm.platform.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class PdfEngineTest {

    private static final String XHTML = """
        <html><head><style>body { font-family: Helvetica; }</style></head>
        <body><h1>Quotation QTN/2026-27/0001</h1><p>Rs. 1,23,456.78</p></body></html>
        """;

    private final PdfEngine engine = new PdfEngine();

    @Test
    void rendersXhtmlToAPdfContainingTheText() throws Exception {
        byte[] pdf = engine.render(XHTML, Instant.parse("2026-07-28T10:15:30Z"));

        assertTrue(pdf.length > 0);
        // Every PDF starts with the %PDF- header; this proves we produced a real file.
        assertEquals("%PDF-", new String(pdf, 0, 5));

        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("QTN/2026-27/0001"), text);
            assertTrue(text.contains("Rs. 1,23,456.78"), text);
        }
    }

    @Test
    void sameInputRendersToIdenticalBytes() {
        Instant at = Instant.parse("2026-07-28T10:15:30Z");

        byte[] first = engine.render(XHTML, at);
        byte[] second = engine.render(XHTML, at);

        // The design spec requires shown/emailed/WhatsApped output to be byte-identical.
        // PDF writers stamp a creation date and a document ID by default, which would
        // make these differ; render() must pin both.
        assertArrayEquals(first, second);
    }
}
```

Note: PDFBox 2.x uses `PDDocument.load(byte[])`. If the resolved PDFBox is 3.x, that method is gone — use `org.apache.pdfbox.Loader.loadPDF(byte[])` instead, and apply the same change to every later test that opens a PDF.

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.platform.pdf.PdfEngineTest'`
Expected: FAIL — compilation error, `PdfEngine` does not exist.

- [ ] **Step 4: Write the minimal implementation**

Create `backend/src/main/java/com/easycrm/platform/pdf/PdfEngine.java`:

```java
package com.easycrm.platform.pdf;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.Instant;

/**
 * Renders well-formed XHTML to PDF bytes. Knows nothing about any domain object.
 *
 * Output is a pure function of (xhtml, timestamp): the caller supplies the timestamp
 * so that re-rendering the same frozen quotation version always produces identical
 * bytes, which is what makes "shown, emailed and WhatsApped output are the same
 * document" an assertable property rather than an aspiration.
 */
@Component
public class PdfEngine {

    public byte[] render(String xhtml, Instant timestamp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(xhtml, null);
            builder.toStream(out);
            builder.useFastMode();
            builder.run();
        } catch (Exception e) {
            throw new IllegalStateException("PDF rendering failed", e);
        }
        return out.toByteArray();
    }
}
```

- [ ] **Step 5: Run the test — expect the render to pass and determinism to fail**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.platform.pdf.PdfEngineTest'`
Expected: `rendersXhtmlToAPdfContainingTheText` PASSES; `sameInputRendersToIdenticalBytes` FAILS, because the PDF carries a wall-clock creation date and a random document ID.

If the *first* test fails instead, the library is the problem, not the metadata — see this task's opening note before continuing.

- [ ] **Step 6: Make the output deterministic**

The exact API for pinning metadata is what this spike is here to establish. Post-process the rendered bytes with PDFBox, which is already on the classpath as openhtmltopdf's backend:

```java
package com.easycrm.platform.pdf;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

@Component
public class PdfEngine {

    public byte[] render(String xhtml, Instant timestamp) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(xhtml, null);
            builder.toStream(raw);
            builder.useFastMode();
            builder.run();
        } catch (Exception e) {
            throw new IllegalStateException("PDF rendering failed", e);
        }
        return stampDeterministicMetadata(raw.toByteArray(), timestamp);
    }

    /**
     * Replaces the wall-clock creation date and the writer's random document ID with
     * values derived from the caller's timestamp, so two renders of the same input
     * are byte-identical.
     */
    private byte[] stampDeterministicMetadata(byte[] pdf, Instant timestamp) {
        Calendar at = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        at.setTimeInMillis(timestamp.toEpochMilli());
        try (PDDocument doc = PDDocument.load(pdf)) {
            PDDocumentInformation info = new PDDocumentInformation();
            info.setProducer("EasyCRM");
            info.setCreator("EasyCRM");
            info.setCreationDate(at);
            info.setModificationDate(at);
            doc.setDocumentInformation(info);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("PDF metadata stamping failed", e);
        }
    }
}
```

- [ ] **Step 7: Run the tests until both pass**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.platform.pdf.PdfEngineTest'`
Expected: PASS, both tests.

If `sameInputRendersToIdenticalBytes` still fails, the remaining difference is the trailer `/ID`. PDFBox derives it from document state; setting a fixed `PDDocumentInformation` normally makes it stable. If it does not, inspect the two byte arrays to find the first differing offset before changing approach — and report what you found rather than disabling the assertion. **Never weaken this test to make it pass.**

- [ ] **Step 8: Run the whole suite**

Run: `cd backend && ./gradlew test`
Expected: 189 tests, 0 failures (187 baseline + 2).

- [ ] **Step 9: Commit**

```bash
git add backend/build.gradle.kts backend/src/main/java/com/easycrm/platform/pdf/PdfEngine.java backend/src/test/java/com/easycrm/platform/pdf/PdfEngineTest.java
git commit -m "feat(platform): deterministic XHTML-to-PDF engine

openhtmltopdf with the PDFBox backend, no external binary. Creation date,
producer and modification date are stamped from a caller-supplied timestamp
so two renders of the same frozen quotation version are byte-identical."
```

---

## Task 2: Indian money, quantity, percent and date formatting

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/format/IndianFormats.java`
- Test: `backend/src/test/java/com/easycrm/platform/format/IndianFormatsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `IndianFormats` — a final class with a private constructor and four static methods:
  - `public static String rupees(BigDecimal amount)` → `"Rs. 1,23,456.78"` (null → `""`)
  - `public static String qty(BigDecimal quantity)` → `"10"`, `"2.5"` (trailing zeros stripped)
  - `public static String percent(BigDecimal pct)` → `"18"`, `"2.5"` (null → `""`)
  - `public static String date(LocalDate date)` → `"28-07-2026"` (null → `""`)
  - `public static String date(Instant instant)` → `"28-07-2026"` in Asia/Kolkata (null → `""`)

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/platform/format/IndianFormatsTest.java`:

```java
package com.easycrm.platform.format;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IndianFormatsTest {

    @Test
    void rupeesUsesIndianDigitGrouping() {
        // Lakh/crore grouping: the first group is 3 digits, every group after it is 2.
        assertEquals("Rs. 1,23,456.78", IndianFormats.rupees(new BigDecimal("123456.78")));
        assertEquals("Rs. 1,00,00,000.00", IndianFormats.rupees(new BigDecimal("10000000")));
        assertEquals("Rs. 999.00", IndianFormats.rupees(new BigDecimal("999")));
        assertEquals("Rs. 0.00", IndianFormats.rupees(BigDecimal.ZERO));
    }

    @Test
    void rupeesNeverUsesTheRupeeGlyph() {
        // U+20B9 is absent from the base-14 PDF fonts and no font is embedded.
        assertEquals(false, IndianFormats.rupees(new BigDecimal("1")).contains("₹"));
    }

    @Test
    void nullsRenderAsEmptyStringsRatherThanTheWordNull() {
        assertEquals("", IndianFormats.rupees(null));
        assertEquals("", IndianFormats.percent(null));
        assertEquals("", IndianFormats.date((LocalDate) null));
        assertEquals("", IndianFormats.date((Instant) null));
    }

    @Test
    void qtyAndPercentStripMeaninglessTrailingZeros() {
        assertEquals("10", IndianFormats.qty(new BigDecimal("10.000")));
        assertEquals("2.5", IndianFormats.qty(new BigDecimal("2.500")));
        assertEquals("18", IndianFormats.percent(new BigDecimal("18.0000")));
        assertEquals("2.5", IndianFormats.percent(new BigDecimal("2.5000")));
    }

    @Test
    void datesRenderDayFirstAndInstantsUseIndianStandardTime() {
        assertEquals("28-07-2026", IndianFormats.date(LocalDate.of(2026, 7, 28)));
        // 21:00 UTC on the 27th is 02:30 on the 28th in Asia/Kolkata — the date the
        // Indian user would expect to see on the document.
        assertEquals("28-07-2026", IndianFormats.date(Instant.parse("2026-07-27T21:00:00Z")));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.platform.format.IndianFormatsTest'`
Expected: FAIL — compilation error, `IndianFormats` does not exist.

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/easycrm/platform/format/IndianFormats.java`:

```java
package com.easycrm.platform.format;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Presentation-only formatting for Indian business documents. Computes nothing. */
public final class IndianFormats {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY_FIRST =
        DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ROOT);

    private IndianFormats() {}

    /**
     * "Rs. 1,23,456.78". "Rs." rather than the rupee sign: U+20B9 is not in the
     * base-14 PDF fonts and this slice embeds no font.
     */
    public static String rupees(BigDecimal amount) {
        if (amount == null) return "";
        return "Rs. " + indianGrouped(amount);
    }

    /**
     * Indian (lakh/crore) digit grouping: the first group from the right is three
     * digits, every group after it is two.
     *
     * Hand-rolled deliberately. java.text.DecimalFormat holds a single groupingSize
     * taken from the rightmost separator, so a "#,##,##0.00" pattern silently gives
     * Western grouping; an en-IN locale lookup would instead tie this output to
     * whichever CLDR version ships with the JDK. This is a pure function of its input.
     *
     * setScale(2) also guarantees a decimal point exists, which the substring logic
     * depends on — BigDecimal.ZERO.toPlainString() is "0", with no dot.
     */
    private static String indianGrouped(BigDecimal amount) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        boolean negative = scaled.signum() < 0;
        String plain = scaled.abs().toPlainString();
        int dot = plain.indexOf('.');
        String whole = plain.substring(0, dot);
        String fraction = plain.substring(dot);

        String grouped;
        if (whole.length() <= 3) {
            grouped = whole;
        } else {
            String lastThree = whole.substring(whole.length() - 3);
            String rest = whole.substring(0, whole.length() - 3);
            StringBuilder sb = new StringBuilder();
            int i = rest.length();
            while (i > 2) {
                sb.insert(0, "," + rest.substring(i - 2, i));
                i -= 2;
            }
            sb.insert(0, rest, 0, i);
            grouped = sb + "," + lastThree;
        }
        return (negative ? "-" : "") + grouped + fraction;
    }

    public static String qty(BigDecimal quantity) {
        return quantity == null ? "" : quantity.stripTrailingZeros().toPlainString();
    }

    public static String percent(BigDecimal pct) {
        return pct == null ? "" : pct.stripTrailingZeros().toPlainString();
    }

    public static String date(LocalDate date) {
        return date == null ? "" : DAY_FIRST.format(date);
    }

    public static String date(Instant instant) {
        return instant == null ? "" : DAY_FIRST.format(instant.atZone(IST));
    }

}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.platform.format.IndianFormatsTest'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/easycrm/platform/format/IndianFormats.java backend/src/test/java/com/easycrm/platform/format/IndianFormatsTest.java
git commit -m "feat(platform): Indian money, quantity and date formatting

Lakh/crore digit grouping hand-rolled rather than delegated: DecimalFormat
holds a single groupingSize and silently gives Western grouping, and a
locale lookup would vary with the bundled CLDR. Rs. rather than the rupee glyph, which the base-14 PDF fonts
lack. Instants render in Asia/Kolkata."
```

---

## Task 3: Seller profile — `tenant` columns and `GET`/`PATCH /api/v1/tenant`

Without this the letterhead is a business name and a GSTIN floating on white space.

**Files:**
- Create: `backend/src/main/resources/db/migration/V24__tenant_profile.sql`
- Create: `backend/src/main/java/com/easycrm/platform/error/ForbiddenException.java`
- Create: `backend/src/main/java/com/easycrm/tenant/TenantService.java`
- Create: `backend/src/main/java/com/easycrm/tenant/web/TenantController.java`
- Create: `backend/src/main/java/com/easycrm/tenant/web/dto/TenantProfileRequest.java`
- Create: `backend/src/main/java/com/easycrm/tenant/web/dto/TenantResponse.java`
- Modify: `backend/src/main/java/com/easycrm/tenant/Tenant.java`
- Modify: `backend/src/main/java/com/easycrm/platform/error/ApiExceptionHandler.java`
- Test: `backend/src/test/java/com/easycrm/tenant/web/TenantProfileTest.java`

**Interfaces:**
- Consumes: `TenantContext.get()` → `Optional<TenantPrincipal(tenantId, userId, role)>`; `TenantRepository extends JpaRepository<Tenant, UUID>`.
- Produces:
  - `Tenant#updateProfile(String address, String phone, String email)`, plus getters `getAddress()`, `getPhone()`, `getEmail()`.
  - `TenantResponse(String id, String businessName, String gstin, String stateCode, String address, String phone, String email)` with `static TenantResponse of(Tenant t)`.
  - `TenantService#get()` and `#updateProfile(TenantProfileRequest req)`, both returning `TenantResponse`.
  - `ForbiddenException extends RuntimeException` → HTTP 403.

- [ ] **Step 1: Write the migration**

Create `backend/src/main/resources/db/migration/V24__tenant_profile.sql`:

```sql
-- Seller letterhead fields. All nullable: existing tenants keep working and the
-- PDF simply omits whatever is absent.
ALTER TABLE tenant ADD COLUMN address VARCHAR(512);
ALTER TABLE tenant ADD COLUMN phone   VARCHAR(20);
ALTER TABLE tenant ADD COLUMN email   VARCHAR(255);
```

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/easycrm/tenant/web/TenantProfileTest.java`:

```java
package com.easycrm.tenant.web;

import com.easycrm.platform.security.JwtService;
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
class TenantProfileTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired JwtService jwt;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void ownerCanSetAndReadBackTheSellerProfile() throws Exception {
        var owner = tokens.provisionOwner("27");
        String auth = "Bearer " + owner.token();

        mvc.perform(patch("/api/v1/tenant").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"address":"12 MG Road, Pune 411001","phone":"+919876543210",
                     "email":"sales@acme.example"}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.address").value("12 MG Road, Pune 411001"))
            .andExpect(jsonPath("$.phone").value("+919876543210"));

        mvc.perform(get("/api/v1/tenant").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("sales@acme.example"))
            .andExpect(jsonPath("$.stateCode").value("27"));
    }

    @Test
    void nonOwnerGets403() throws Exception {
        var owner = tokens.provisionOwner("27");
        String salesExec = "Bearer " + jwt.mint(owner.tenantId(), UUID.randomUUID(), "SALES_EXEC");

        mvc.perform(patch("/api/v1/tenant").header("Authorization", salesExec)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"address":"nope","phone":null,"email":null}"""))
            .andExpect(status().isForbidden());
    }

    @Test
    void oneTenantCannotSeeAnothersProfile() throws Exception {
        var a = tokens.provisionOwner("27");
        mvc.perform(patch("/api/v1/tenant").header("Authorization", "Bearer " + a.token())
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"address":"A's address","phone":null,"email":null}"""))
            .andExpect(status().isOk());

        var b = tokens.provisionOwner("29");
        mvc.perform(get("/api/v1/tenant").header("Authorization", "Bearer " + b.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.address").doesNotExist());
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.tenant.web.TenantProfileTest'`
Expected: FAIL — no such endpoint (404), and compilation succeeds only after the DTOs exist.

- [ ] **Step 4: Add the entity fields**

In `backend/src/main/java/com/easycrm/tenant/Tenant.java`, add the three columns after the `gstin` field:

```java
    @Column(length = 512)
    private String address;

    @Column(length = 20)
    private String phone;

    @Column(length = 255)
    private String email;
```

and add, next to `setStatus`:

```java
    /** Full replace: an omitted field clears the stored value (house-wide PATCH semantics). */
    public void updateProfile(String address, String phone, String email) {
        this.address = address;
        this.phone = phone;
        this.email = email;
    }

    public String getAddress() { return address; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
```

- [ ] **Step 5: Add the 403 exception and its handler**

Create `backend/src/main/java/com/easycrm/platform/error/ForbiddenException.java`:

```java
package com.easycrm.platform.error;

/**
 * The caller is authenticated and the record is inside their tenant, but their role
 * does not permit this operation. Distinct from a cross-tenant read, which is a 404
 * so that the response cannot confirm the record exists.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) { super(message); }
}
```

In `ApiExceptionHandler`, add a handler next to `unauthorized`:

```java
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> forbidden(ForbiddenException ex) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage(), null);
    }
```

- [ ] **Step 6: Add the DTOs**

Create `backend/src/main/java/com/easycrm/tenant/web/dto/TenantProfileRequest.java`:

```java
package com.easycrm.tenant.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record TenantProfileRequest(
    @Size(max = 512) String address,
    @Size(max = 20) String phone,
    @Size(max = 255) @Email String email) {}
```

Create `backend/src/main/java/com/easycrm/tenant/web/dto/TenantResponse.java`:

```java
package com.easycrm.tenant.web.dto;

import com.easycrm.tenant.Tenant;

public record TenantResponse(String id, String businessName, String gstin, String stateCode,
                             String address, String phone, String email) {

    public static TenantResponse of(Tenant t) {
        return new TenantResponse(t.getId().toString(), t.getBusinessName(), t.getGstin(),
            t.getStateCode(), t.getAddress(), t.getPhone(), t.getEmail());
    }
}
```

- [ ] **Step 7: Add the service and controller**

Create `backend/src/main/java/com/easycrm/tenant/TenantService.java`:

```java
package com.easycrm.tenant;

import com.easycrm.platform.error.ForbiddenException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.tenant.web.dto.TenantProfileRequest;
import com.easycrm.tenant.web.dto.TenantResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TenantService {

    private final TenantRepository tenants;

    public TenantService(TenantRepository tenants) { this.tenants = tenants; }

    @Transactional(readOnly = true)
    public TenantResponse get() {
        return TenantResponse.of(current());
    }

    @Transactional
    public TenantResponse updateProfile(TenantProfileRequest req) {
        requireOwner();
        Tenant t = current();
        t.updateProfile(req.address(), req.phone(), req.email());
        return TenantResponse.of(t);
    }

    private Tenant current() {
        UUID id = TenantContext.tenantId();
        // `tenant` is a global table, so this is the one place the id must be passed
        // explicitly rather than left to @TenantId + RLS.
        return tenants.findById(id).orElseThrow(() -> new NotFoundException("tenant not found"));
    }

    private void requireOwner() {
        String role = TenantContext.get().map(TenantContext.TenantPrincipal::role).orElse(null);
        if (!"OWNER".equals(role)) {
            throw new ForbiddenException("only an owner may change the business profile");
        }
    }
}
```

Create `backend/src/main/java/com/easycrm/tenant/web/TenantController.java`:

```java
package com.easycrm.tenant.web;

import com.easycrm.tenant.TenantService;
import com.easycrm.tenant.web.dto.TenantProfileRequest;
import com.easycrm.tenant.web.dto.TenantResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenant")
public class TenantController {

    private final TenantService service;

    public TenantController(TenantService service) { this.service = service; }

    @GetMapping
    public TenantResponse get() { return service.get(); }

    @PatchMapping
    public TenantResponse patch(@Valid @RequestBody TenantProfileRequest req) {
        return service.updateProfile(req);
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.tenant.web.TenantProfileTest'`
Expected: PASS, 3 tests.

- [ ] **Step 9: Run the whole suite**

Run: `cd backend && ./gradlew test`
Expected: 197 tests, 0 failures.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/resources/db/migration/V24__tenant_profile.sql backend/src/main/java/com/easycrm/tenant backend/src/main/java/com/easycrm/platform/error backend/src/test/java/com/easycrm/tenant
git commit -m "feat(tenant): seller profile columns and GET/PATCH /api/v1/tenant

Address, phone and email for the quotation letterhead, all nullable so
existing tenants keep working. Adds ForbiddenException -> 403 for the
OWNER-only update; role comes from the JWT via TenantContext, which keeps
method security out of the codebase for now."
```

---

## Task 4: The quotation PDF renderer

Renders a self-contained view model to bytes. No repositories, no Spring Data, no database — which is what makes it unit-testable and keeps the template's inputs explicit.

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/pdf/QuotationPdfData.java`
- Create: `backend/src/main/java/com/easycrm/sales/pdf/QuotationPdfRenderer.java`
- Create: `backend/src/main/resources/templates/quotation.xhtml`
- Test: `backend/src/test/java/com/easycrm/sales/pdf/QuotationPdfRendererTest.java`

**Interfaces:**
- Consumes: `PdfEngine#render(String, Instant)` (Task 1); `IndianFormats` (Task 2).
- Produces:
  - `QuotationPdfData` — a record with nested records, all fields already formatted as strings except where noted:
    ```java
    QuotationPdfData(Seller seller, Buyer buyer, Doc doc, List<Line> lines,
                     Totals totals, boolean interState, Instant renderTimestamp)
    Seller(String businessName, String gstin, String address, String phone, String email)
    Buyer(String businessName, String gstin, String address)
    Doc(String quoteNo, int versionNo, String date, String validUntil,
        String placeOfSupply, String paymentTerms, String deliveryTerms, String notes)
    Line(int serial, String name, String hsn, String uom, String qty, String rate,
         String discountPct, String gstRate, String taxableValue, String lineTotal)
    Totals(String subTotal, String cgst, String sgst, String igst,
           String totalTax, String grandTotal)
    ```
  - `QuotationPdfRenderer#render(QuotationPdfData data)` → `byte[]`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/sales/pdf/QuotationPdfRendererTest.java`:

```java
package com.easycrm.sales.pdf;

import com.easycrm.support.IntegrationTest;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class QuotationPdfRendererTest extends IntegrationTest {

    @Autowired QuotationPdfRenderer renderer;

    private static QuotationPdfData data(boolean interState) {
        return new QuotationPdfData(
            new QuotationPdfData.Seller("Acme Traders", "27AAPFU0939F1ZV",
                "12 MG Road, Pune 411001", "+919876543210", "sales@acme.example"),
            new QuotationPdfData.Buyer("Bharat Industries", "29AAPFU0939F1ZV",
                "44 Brigade Road, Bengaluru 560001"),
            new QuotationPdfData.Doc("QTN/2026-27/0001", 1, "28-07-2026", "27-08-2026",
                "29", "30 days", "Ex-works", "Rates valid for this order only"),
            List.of(new QuotationPdfData.Line(1, "Ball Bearing 6203", "84821011", "PCS",
                "10", "Rs. 100.00", "5", "18", "Rs. 950.00", "Rs. 1,121.00")),
            new QuotationPdfData.Totals("Rs. 950.00",
                interState ? null : "Rs. 85.50", interState ? null : "Rs. 85.50",
                interState ? "Rs. 171.00" : null, "Rs. 171.00", "Rs. 1,121.00"),
            interState, Instant.parse("2026-07-28T10:15:30Z"));
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void showsBothPartiesTheQuoteNumberAndEveryLine() throws Exception {
        String text = textOf(renderer.render(data(true)));

        assertTrue(text.contains("Acme Traders"), text);
        assertTrue(text.contains("12 MG Road, Pune 411001"), text);
        assertTrue(text.contains("Bharat Industries"), text);
        assertTrue(text.contains("QTN/2026-27/0001"), text);
        assertTrue(text.contains("Ball Bearing 6203"), text);
        assertTrue(text.contains("84821011"), text);   // HSN is a GST requirement
        assertTrue(text.contains("Rs. 1,121.00"), text);
        assertTrue(text.contains("30 days"), text);
    }

    @Test
    void interStateShowsIgstAndNeverCgstOrSgst() throws Exception {
        String text = textOf(renderer.render(data(true)));

        assertTrue(text.contains("IGST"), text);
        assertFalse(text.contains("CGST"), text);
        assertFalse(text.contains("SGST"), text);
    }

    @Test
    void intraStateShowsCgstAndSgstAndNeverIgst() throws Exception {
        String text = textOf(renderer.render(data(false)));

        assertTrue(text.contains("CGST"), text);
        assertTrue(text.contains("SGST"), text);
        assertFalse(text.contains("IGST"), text);
    }

    @Test
    void aSellerWithNoAddressPhoneOrEmailStillRenders() throws Exception {
        QuotationPdfData full = data(false);
        QuotationPdfData sparse = new QuotationPdfData(
            new QuotationPdfData.Seller("Acme Traders", null, null, null, null),
            full.buyer(), full.doc(), full.lines(), full.totals(),
            full.interState(), full.renderTimestamp());

        String text = textOf(renderer.render(sparse));

        assertTrue(text.contains("Acme Traders"), text);
        assertFalse(text.contains("null"), text);   // no null leaking onto the letterhead
    }

    @Test
    void theSameVersionAlwaysRendersToTheSameBytes() {
        assertArrayEquals(renderer.render(data(false)), renderer.render(data(false)));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.pdf.QuotationPdfRendererTest'`
Expected: FAIL — compilation error, `QuotationPdfData` and `QuotationPdfRenderer` do not exist.

- [ ] **Step 3: Write the view model**

Create `backend/src/main/java/com/easycrm/sales/pdf/QuotationPdfData.java`:

```java
package com.easycrm.sales.pdf;

import java.time.Instant;
import java.util.List;

/**
 * Everything the template needs, already formatted. Deliberately free of entities and
 * of BigDecimal: the document presents the frozen snapshot and recomputes nothing, so
 * formatting decisions all happen before rendering, where they are easy to test.
 *
 * A tax field that does not apply is null, not zero — the template omits the whole
 * column rather than printing "Rs. 0.00" for a tax that was never charged.
 */
public record QuotationPdfData(Seller seller, Buyer buyer, Doc doc, List<Line> lines,
                               Totals totals, boolean interState, Instant renderTimestamp) {

    public record Seller(String businessName, String gstin, String address,
                         String phone, String email) {}

    public record Buyer(String businessName, String gstin, String address) {}

    public record Doc(String quoteNo, int versionNo, String date, String validUntil,
                      String placeOfSupply, String paymentTerms, String deliveryTerms,
                      String notes) {}

    public record Line(int serial, String name, String hsn, String uom, String qty,
                       String rate, String discountPct, String gstRate,
                       String taxableValue, String lineTotal) {}

    public record Totals(String subTotal, String cgst, String sgst, String igst,
                         String totalTax, String grandTotal) {}
}
```

- [ ] **Step 4: Write the template**

Create `backend/src/main/resources/templates/quotation.xhtml`. It must be **well-formed XML** — openhtmltopdf parses XHTML strictly, so every tag closes and every attribute is quoted.

```html
<html xmlns:th="http://www.thymeleaf.org">
<head>
  <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
  <style>
    @page { size: A4; margin: 18mm 14mm; }
    body { font-family: Helvetica, sans-serif; font-size: 9pt; color: #111; }
    h1 { font-size: 15pt; margin: 0 0 2mm 0; }
    .muted { color: #555; }
    .parties { width: 100%; margin-top: 6mm; }
    .parties td { vertical-align: top; width: 50%; padding-right: 6mm; }
    .label { font-size: 7.5pt; text-transform: uppercase; color: #777;
             letter-spacing: 0.4pt; margin-bottom: 1mm; }
    table.items { width: 100%; border-collapse: collapse; margin-top: 6mm; }
    table.items th { background: #f0f0f0; text-align: left; font-size: 8pt;
                     padding: 2mm; border-bottom: 0.4mm solid #999; }
    table.items td { padding: 2mm; border-bottom: 0.2mm solid #ddd; }
    .num { text-align: right; }
    table.totals { margin-top: 4mm; margin-left: auto; width: 62mm; }
    table.totals td { padding: 1.2mm 2mm; }
    table.totals tr.grand td { font-weight: bold; border-top: 0.4mm solid #999; }
    .terms { margin-top: 8mm; font-size: 8.5pt; }
    .terms div { margin-bottom: 1.5mm; }
  </style>
</head>
<body>

  <h1 th:text="${d.seller.businessName}">Seller</h1>
  <div class="muted">
    <span th:if="${d.seller.address}" th:text="${d.seller.address}">Address</span>
    <span th:if="${d.seller.gstin}" th:text="'| GSTIN: ' + ${d.seller.gstin}">GSTIN</span>
    <span th:if="${d.seller.phone}" th:text="'| ' + ${d.seller.phone}">Phone</span>
    <span th:if="${d.seller.email}" th:text="'| ' + ${d.seller.email}">Email</span>
  </div>

  <table class="parties">
    <tr>
      <td>
        <div class="label">Quotation to</div>
        <div th:text="${d.buyer.businessName}">Buyer</div>
        <div class="muted" th:if="${d.buyer.address}" th:text="${d.buyer.address}">Addr</div>
        <div class="muted" th:if="${d.buyer.gstin}"
             th:text="'GSTIN: ' + ${d.buyer.gstin}">GSTIN</div>
      </td>
      <td>
        <div class="label">Quotation</div>
        <div th:text="${d.doc.quoteNo} + '  (v' + ${d.doc.versionNo} + ')'">No</div>
        <div class="muted" th:text="'Date: ' + ${d.doc.date}">Date</div>
        <div class="muted" th:if="${d.doc.validUntil}"
             th:text="'Valid until: ' + ${d.doc.validUntil}">Valid</div>
        <div class="muted" th:text="'Place of supply: ' + ${d.doc.placeOfSupply}">POS</div>
      </td>
    </tr>
  </table>

  <table class="items">
    <thead>
      <tr>
        <th>#</th><th>Description</th><th>HSN</th><th>UOM</th>
        <th class="num">Qty</th><th class="num">Rate</th><th class="num">Disc %</th>
        <th class="num">GST %</th><th class="num">Taxable</th><th class="num">Amount</th>
      </tr>
    </thead>
    <tbody>
      <tr th:each="line : ${d.lines}">
        <td th:text="${line.serial}">1</td>
        <td th:text="${line.name}">Item</td>
        <td th:text="${line.hsn}">HSN</td>
        <td th:text="${line.uom}">PCS</td>
        <td class="num" th:text="${line.qty}">1</td>
        <td class="num" th:text="${line.rate}">Rs. 0.00</td>
        <td class="num" th:text="${line.discountPct}">0</td>
        <td class="num" th:text="${line.gstRate}">18</td>
        <td class="num" th:text="${line.taxableValue}">Rs. 0.00</td>
        <td class="num" th:text="${line.lineTotal}">Rs. 0.00</td>
      </tr>
    </tbody>
  </table>

  <table class="totals">
    <tr><td>Sub total</td><td class="num" th:text="${d.totals.subTotal}">Rs. 0.00</td></tr>
    <tr th:unless="${d.interState}">
      <td>CGST</td><td class="num" th:text="${d.totals.cgst}">Rs. 0.00</td>
    </tr>
    <tr th:unless="${d.interState}">
      <td>SGST</td><td class="num" th:text="${d.totals.sgst}">Rs. 0.00</td>
    </tr>
    <tr th:if="${d.interState}">
      <td>IGST</td><td class="num" th:text="${d.totals.igst}">Rs. 0.00</td>
    </tr>
    <tr class="grand">
      <td>Grand total</td><td class="num" th:text="${d.totals.grandTotal}">Rs. 0.00</td>
    </tr>
  </table>

  <div class="terms">
    <div th:if="${d.doc.paymentTerms}"
         th:text="'Payment terms: ' + ${d.doc.paymentTerms}">Payment</div>
    <div th:if="${d.doc.deliveryTerms}"
         th:text="'Delivery terms: ' + ${d.doc.deliveryTerms}">Delivery</div>
    <div th:if="${d.doc.notes}" th:text="${d.doc.notes}">Notes</div>
    <div class="muted">This is a quotation, not a tax invoice.</div>
  </div>

</body>
</html>
```

- [ ] **Step 5: Write the renderer**

Create `backend/src/main/java/com/easycrm/sales/pdf/QuotationPdfRenderer.java`:

```java
package com.easycrm.sales.pdf;

import com.easycrm.platform.pdf.PdfEngine;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Locale;

@Component
public class QuotationPdfRenderer {

    private final TemplateEngine templates;
    private final PdfEngine pdf;

    public QuotationPdfRenderer(TemplateEngine templates, PdfEngine pdf) {
        this.templates = templates;
        this.pdf = pdf;
    }

    public byte[] render(QuotationPdfData data) {
        // Locale.ROOT: nothing in the template is localised, and pinning it keeps the
        // output independent of the server's default locale.
        Context ctx = new Context(Locale.ROOT);
        ctx.setVariable("d", data);
        String xhtml = templates.process("quotation", ctx);
        return pdf.render(xhtml, data.renderTimestamp());
    }
}
```

- [ ] **Step 6: Point Thymeleaf at `.xhtml`**

Thymeleaf's Spring Boot defaults resolve `templates/<name>.html`. Add to `backend/src/main/resources/application.yml`, inside the existing `spring:` block:

```yaml
  thymeleaf:
    suffix: .xhtml
    mode: XML
```

`mode: XML` makes Thymeleaf emit strictly well-formed markup, which is what openhtmltopdf requires.

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.pdf.QuotationPdfRendererTest'`
Expected: PASS, 5 tests.

If a test fails on extracted text, print the extracted string first — `PDFTextStripper` inserts line breaks at layout boundaries, so a long value may be split across lines. Assert on the smallest distinctive fragment rather than reflowing the layout to satisfy the test.

- [ ] **Step 8: Run the whole suite**

Run: `cd backend && ./gradlew test`
Expected: 202 tests, 0 failures.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/pdf backend/src/main/resources/templates/quotation.xhtml backend/src/main/resources/application.yml backend/src/test/java/com/easycrm/sales/pdf
git commit -m "feat(sales): render a quotation version to PDF

QuotationPdfData is a fully-formatted view model, so the template holds no
BigDecimal and no entity and the renderer needs no database. A tax that does
not apply is null rather than zero, so an inter-state quote shows IGST only
and an intra-state one CGST/SGST only."
```

---

## Task 5: `GET /api/v1/quotations/{id}/pdf`

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/pdf/QuotationPdfService.java`
- Modify: `backend/src/main/java/com/easycrm/sales/web/QuotationController.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/QuotationPdfEndpointTest.java`

**Interfaces:**
- Consumes: `QuotationPdfRenderer#render` (Task 4); `IndianFormats` (Task 2); `QuotationRepository`, `QuotationVersionRepository#findByQuotationIdAndVersionNo(UUID, int)` and `#findById`, `QuotationItemRepository#findByVersionId(UUID)`, `CustomerRepository`, `TenantRepository`.
- Produces:
  - `QuotationPdfService#renderByQuotation(UUID quotationId, Integer versionNo)` → `byte[]`
  - `QuotationPdfService#renderByVersionId(UUID versionId)` → `byte[]` (used by Task 8's public endpoint)

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/sales/web/QuotationPdfEndpointTest.java`:

```java
package com.easycrm.sales.web;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class QuotationPdfEndpointTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    /** Creates a DRAFT quotation for a customer in `customerState` and returns its id. */
    private String draftQuotation(String auth, String customerState) throws Exception {
        String cId = JsonPath.read(mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"businessName":"Bharat Industries","stateCode":"%s","source":"MANUAL"}"""
                    .formatted(customerState)))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String pId = JsonPath.read(mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"sku":"SKU-%s","name":"Ball Bearing 6203","hsnCode":"84821011",
                     "uom":"PCS","gstRate":"18","baseRate":"100.00"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 8))))
            .andReturn().getResponse().getContentAsString(), "$.id");
        return JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(
                    "{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"10\"}]}"
                        .formatted(cId, pId)))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void rendersASentQuotationWithBothPartiesAndTheHsnCode() throws Exception {
        var owner = tokens.provisionOwner("27");
        String auth = "Bearer " + owner.token();
        mvc.perform(patch("/api/v1/tenant").header("Authorization", auth)
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"address":"12 MG Road, Pune 411001","phone":"+919876543210","email":null}"""));
        String qId = draftQuotation(auth, "27");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth));

        byte[] pdf = mvc.perform(get("/api/v1/quotations/" + qId + "/pdf").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andReturn().getResponse().getContentAsByteArray();

        String text = textOf(pdf);
        assertTrue(text.contains("Bharat Industries"), text);
        assertTrue(text.contains("12 MG Road, Pune 411001"), text);
        assertTrue(text.contains("84821011"), text);
    }

    @Test
    void intraStateShowsCgstSgstAndInterStateShowsIgst() throws Exception {
        String authIntra = "Bearer " + tokens.provisionOwner("27").token();
        String intra = draftQuotation(authIntra, "27");        // seller 27, buyer 27
        mvc.perform(post("/api/v1/quotations/" + intra + "/send").header("Authorization", authIntra));
        String intraText = textOf(mvc.perform(get("/api/v1/quotations/" + intra + "/pdf")
            .header("Authorization", authIntra)).andReturn().getResponse().getContentAsByteArray());
        assertTrue(intraText.contains("CGST") && !intraText.contains("IGST"), intraText);

        String authInter = "Bearer " + tokens.provisionOwner("27").token();
        String inter = draftQuotation(authInter, "29");        // seller 27, buyer 29
        mvc.perform(post("/api/v1/quotations/" + inter + "/send").header("Authorization", authInter));
        String interText = textOf(mvc.perform(get("/api/v1/quotations/" + inter + "/pdf")
            .header("Authorization", authInter)).andReturn().getResponse().getContentAsByteArray());
        assertTrue(interText.contains("IGST") && !interText.contains("CGST"), interText);
    }

    @Test
    void aDraftHasNoDocumentToRender() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String qId = draftQuotation(auth, "27");   // never sent

        mvc.perform(get("/api/v1/quotations/" + qId + "/pdf").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void anEarlierVersionStillRendersAfterARevision() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String qId = draftQuotation(auth, "27");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth));
        mvc.perform(post("/api/v1/quotations/" + qId + "/revise").header("Authorization", auth));
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth));

        // v2 is the default; v1 is still reachable and still says "v1".
        String v2 = textOf(mvc.perform(get("/api/v1/quotations/" + qId + "/pdf")
            .header("Authorization", auth)).andReturn().getResponse().getContentAsByteArray());
        assertTrue(v2.contains("(v2)"), v2);

        String v1 = textOf(mvc.perform(get("/api/v1/quotations/" + qId + "/pdf?version=1")
                .header("Authorization", auth))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertTrue(v1.contains("(v1)"), v1);
    }

    @Test
    void crossTenantReturns404() throws Exception {
        String authA = "Bearer " + tokens.provisionOwner("27").token();
        String qId = draftQuotation(authA, "27");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", authA));

        String authB = "Bearer " + tokens.provisionOwner("29").token();
        mvc.perform(get("/api/v1/quotations/" + qId + "/pdf").header("Authorization", authB))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationPdfEndpointTest'`
Expected: FAIL — 404 on `/pdf`, the endpoint does not exist.

- [ ] **Step 3: Write the service**

Create `backend/src/main/java/com/easycrm/sales/pdf/QuotationPdfService.java`:

```java
package com.easycrm.sales.pdf;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.format.IndianFormats;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.Quotation;
import com.easycrm.sales.QuotationItem;
import com.easycrm.sales.QuotationItemRepository;
import com.easycrm.sales.QuotationRepository;
import com.easycrm.sales.QuotationVersion;
import com.easycrm.sales.QuotationVersionRepository;
import com.easycrm.sales.VersionStatus;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class QuotationPdfService {

    private final QuotationRepository quotations;
    private final QuotationVersionRepository versions;
    private final QuotationItemRepository items;
    private final CustomerRepository customers;
    private final TenantRepository tenants;
    private final QuotationPdfRenderer renderer;

    public QuotationPdfService(QuotationRepository quotations, QuotationVersionRepository versions,
                               QuotationItemRepository items, CustomerRepository customers,
                               TenantRepository tenants, QuotationPdfRenderer renderer) {
        this.quotations = quotations;
        this.versions = versions;
        this.items = items;
        this.customers = customers;
        this.tenants = tenants;
        this.renderer = renderer;
    }

    /** Latest SENT version when versionNo is null, otherwise that specific frozen version. */
    @Transactional(readOnly = true)
    public byte[] renderByQuotation(UUID quotationId, Integer versionNo) {
        Quotation q = quotations.findById(quotationId)
            .orElseThrow(() -> new NotFoundException("quotation not found"));
        QuotationVersion v = versionNo == null
            ? versions.findById(requireCurrentVersion(q))
                .orElseThrow(() -> new NotFoundException("quotation version not found"))
            // 422, not 404: the quotation exists and is visible — it is the version
            // parameter that is wrong, which is invalid input rather than a missing record.
            : versions.findByQuotationIdAndVersionNo(quotationId, versionNo)
                .orElseThrow(() -> new ValidationException("version", "no such version"));
        return render(q, v);
    }

    /** Entry point for the public share link, which knows a version id and nothing else. */
    @Transactional(readOnly = true)
    public byte[] renderByVersionId(UUID versionId) {
        QuotationVersion v = versions.findById(versionId)
            .orElseThrow(() -> new NotFoundException("quotation version not found"));
        Quotation q = quotations.findById(v.getQuotationId())
            .orElseThrow(() -> new NotFoundException("quotation not found"));
        return render(q, v);
    }

    private UUID requireCurrentVersion(Quotation q) {
        if (q.getQuoteNo() == null || q.getCurrentVersionId() == null) {
            throw new ValidationException("status", "send the quotation before rendering it");
        }
        return q.getCurrentVersionId();
    }

    private byte[] render(Quotation q, QuotationVersion v) {
        if (v.getStatus() != VersionStatus.SENT) {
            throw new ValidationException("status", "send the quotation before rendering it");
        }
        Tenant tenant = tenants.findById(TenantContext.tenantId())
            .orElseThrow(() -> new NotFoundException("tenant not found"));
        Customer customer = customers.findById(q.getCustomerId())
            .orElseThrow(() -> new NotFoundException("customer not found"));
        List<QuotationItem> lines = items.findByVersionId(v.getId());

        // The version's place of supply is the buyer's state, frozen when it was created.
        // Comparing it to the seller's state is exact; inferring from whether any line
        // has non-zero IGST would misread a wholly zero-rated inter-state quote as
        // intra-state and print CGST/SGST rows for tax that was never charged.
        boolean interState = !v.getPlaceOfSupply().equals(tenant.getStateCode());

        return renderer.render(new QuotationPdfData(
            new QuotationPdfData.Seller(tenant.getBusinessName(), tenant.getGstin(),
                tenant.getAddress(), tenant.getPhone(), tenant.getEmail()),
            new QuotationPdfData.Buyer(customer.getBusinessName(), customer.getGstin(),
                customer.getBillingAddress()),
            new QuotationPdfData.Doc(q.getQuoteNo(), v.getVersionNo(),
                IndianFormats.date(v.getSentAt()), IndianFormats.date(v.getValidUntil()),
                v.getPlaceOfSupply(), v.getPaymentTerms(), v.getDeliveryTerms(), v.getNotes()),
            toLines(lines),
            totals(v, lines, interState),
            interState,
            v.getSentAt()));   // pins deterministic PDF metadata to when the version froze
    }

    private List<QuotationPdfData.Line> toLines(List<QuotationItem> source) {
        List<QuotationPdfData.Line> out = new ArrayList<>();
        int serial = 1;
        for (QuotationItem i : source) {
            out.add(new QuotationPdfData.Line(serial++, i.getNameSnapshot(), i.getHsnSnapshot(),
                i.getUomSnapshot(), IndianFormats.qty(i.getQty()), IndianFormats.rupees(i.getRate()),
                IndianFormats.percent(i.getDiscountPct()), IndianFormats.percent(i.getGstRate()),
                IndianFormats.rupees(i.getTaxableValue()), IndianFormats.rupees(i.getLineTotal())));
        }
        return out;
    }

    private QuotationPdfData.Totals totals(QuotationVersion v, List<QuotationItem> lines,
                                           boolean interState) {
        BigDecimal cgst = sum(lines, QuotationItem::getCgst);
        BigDecimal sgst = sum(lines, QuotationItem::getSgst);
        BigDecimal igst = sum(lines, QuotationItem::getIgst);
        return new QuotationPdfData.Totals(
            IndianFormats.rupees(v.getSubTotal()),
            interState ? null : IndianFormats.rupees(cgst),
            interState ? null : IndianFormats.rupees(sgst),
            interState ? IndianFormats.rupees(igst) : null,
            IndianFormats.rupees(v.getTotalTax()),
            IndianFormats.rupees(v.getGrandTotal()));
    }

    private BigDecimal sum(List<QuotationItem> lines,
                           java.util.function.Function<QuotationItem, BigDecimal> field) {
        // Summing already-rounded per-line tax, which is how the version's totals were
        // built (round per line, then sum — challenge #2). Nothing is re-rounded here.
        return lines.stream().map(field).filter(java.util.Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
```

- [ ] **Step 4: Add the endpoint**

In `QuotationController`, add the field and endpoint. Change the constructor to take both services:

```java
    private final QuotationService service;
    private final QuotationPdfService pdfService;

    public QuotationController(QuotationService service, QuotationPdfService pdfService) {
        this.service = service;
        this.pdfService = pdfService;
    }
```

and add, after `version(...)`:

```java
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id,
                                      @RequestParam(required = false) Integer version) {
        byte[] bytes = pdfService.renderByQuotation(id, version);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
            .body(bytes);
    }
```

Add the imports `com.easycrm.sales.pdf.QuotationPdfService`, `org.springframework.http.HttpHeaders`, `org.springframework.http.MediaType`.

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationPdfEndpointTest'`
Expected: PASS, 5 tests.

- [ ] **Step 6: Run the whole suite**

Run: `cd backend && ./gradlew test`
Expected: 207 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/pdf/QuotationPdfService.java backend/src/main/java/com/easycrm/sales/web/QuotationController.java backend/src/test/java/com/easycrm/sales/web/QuotationPdfEndpointTest.java
git commit -m "feat(sales): GET /api/v1/quotations/{id}/pdf

Defaults to the latest sent version; ?version=n renders an earlier frozen
one, since a trader who has revised three times still needs to see what was
actually sent. 422 for a quotation with nothing frozen yet, 404 cross-tenant.
The PDF timestamp is the version's sentAt, so a given version always renders
to the same bytes."
```

---

## Task 6: The `share_link` table

**Files:**
- Create: `backend/src/main/resources/db/migration/V25__share_link.sql`
- Create: `backend/src/main/java/com/easycrm/sales/ShareLink.java`
- Create: `backend/src/main/java/com/easycrm/sales/ShareLinkRepository.java`
- Modify: `backend/src/test/java/com/easycrm/arch/TenantScopingArchTest.java`
- Test: `backend/src/test/java/com/easycrm/sales/ShareLinkRepositoryTest.java`

**Interfaces:**
- Consumes: `BaseEntity` (id, createdAt, updatedAt, version).
- Produces:
  - `ShareLink(String token, UUID tenantId, UUID quotationVersionId)` with `getToken()`, `getTenantId()`, `getQuotationVersionId()`.
  - `ShareLinkRepository#findByToken(String)` → `Optional<ShareLink>` and `#findByQuotationVersionId(UUID)` → `Optional<ShareLink>`, both `@Transactional(readOnly = true)`.

- [ ] **Step 1: Write the migration**

Create `backend/src/main/resources/db/migration/V25__share_link.sql`:

```sql
-- GLOBAL table: deliberately NO row-level security and NO @TenantId.
-- The public share endpoint has no JWT, so it has no tenant; this row is what
-- resolves one. Everything it points at is then read through @TenantId + RLS as
-- normal. It holds no document data — only an opaque token, a tenant and a version.
CREATE TABLE share_link (
    id                   UUID PRIMARY KEY,
    token                VARCHAR(64) NOT NULL UNIQUE,
    tenant_id            UUID NOT NULL,
    quotation_version_id UUID NOT NULL,
    created_at           TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ,
    version              BIGINT NOT NULL DEFAULT 0
);

-- One link per version: sharing the same version twice returns the same URL, so a
-- link already sent to a customer keeps working.
CREATE UNIQUE INDEX uq_share_link_version ON share_link (quotation_version_id);
```

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/easycrm/sales/ShareLinkRepositoryTest.java`:

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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ShareLinkRepositoryTest extends IntegrationTest {
    @Autowired ShareLinkRepository links;
    @Autowired TransactionTemplate tx;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void isReadableWithNoTenantContextAtAll() {
        UUID tenantId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String token = "tok-" + UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "OWNER"));
        tx.executeWithoutResult(s -> links.save(new ShareLink(token, tenantId, versionId)));
        TenantContext.clear();

        // The whole point: a public request has no tenant, and this row must still be
        // found. A tenant-scoped table would return empty here (RLS fails safe).
        ShareLink found = links.findByToken(token).orElseThrow();

        assertEquals(tenantId, found.getTenantId());
        assertEquals(versionId, found.getQuotationVersionId());
    }

    @Test
    void anUnknownTokenResolvesToNothing() {
        assertTrue(links.findByToken("tok-" + UUID.randomUUID()).isEmpty());
    }

    @Test
    void aVersionCannotHaveTwoLinks() {
        UUID tenantId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "OWNER"));
        tx.executeWithoutResult(s ->
            links.save(new ShareLink("tok-" + UUID.randomUUID(), tenantId, versionId)));

        assertThrows(DataIntegrityViolationException.class, () ->
            tx.executeWithoutResult(s ->
                links.saveAndFlush(new ShareLink("tok-" + UUID.randomUUID(), tenantId, versionId))));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.ShareLinkRepositoryTest'`
Expected: FAIL — compilation error, `ShareLink` does not exist.

- [ ] **Step 4: Write the entity and repository**

Create `backend/src/main/java/com/easycrm/sales/ShareLink.java`:

```java
package com.easycrm.sales;

import com.easycrm.platform.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * GLOBAL table (intentionally NOT tenant-scoped), like {@code refresh_token}: the public
 * share endpoint is pre-auth and must resolve a tenant from the opaque token alone, so
 * this cannot be tenant-filtered. Everything it points at is then loaded through
 * {@code @TenantId} + RLS as normal.
 *
 * The token is stored in plaintext — unlike refresh_token, which is hashed. A refresh
 * token grants authenticated capability; this one only reads a frozen quotation that is
 * rendered from rows in this same database. Plaintext is what makes sharing idempotent:
 * one stable link per version, so a link already sent to a customer keeps working.
 * It is a bearer credential, so it must never be logged — hence no toString().
 */
@Entity
@Table(name = "share_link")
public class ShareLink extends BaseEntity {

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "quotation_version_id", nullable = false)
    private UUID quotationVersionId;

    protected ShareLink() {}

    public ShareLink(String token, UUID tenantId, UUID quotationVersionId) {
        this.token = token;
        this.tenantId = tenantId;
        this.quotationVersionId = quotationVersionId;
    }

    public String getToken() { return token; }
    public UUID getTenantId() { return tenantId; }
    public UUID getQuotationVersionId() { return quotationVersionId; }
}
```

Create `backend/src/main/java/com/easycrm/sales/ShareLinkRepository.java`:

```java
package com.easycrm.sales;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface ShareLinkRepository extends JpaRepository<ShareLink, UUID> {

    // Derived queries are not transactional by default, which would leave the RLS GUC
    // unset. share_link has no RLS, but the annotation keeps these consistent with the
    // rest of the codebase and correct if a policy is ever added.
    @Transactional(readOnly = true)
    Optional<ShareLink> findByToken(String token);

    @Transactional(readOnly = true)
    Optional<ShareLink> findByQuotationVersionId(UUID quotationVersionId);
}
```

- [ ] **Step 5: Allowlist the entity in ArchUnit**

In `backend/src/test/java/com/easycrm/arch/TenantScopingArchTest.java`, extend `GLOBAL_TABLES`:

```java
    private static final Set<String> GLOBAL_TABLES = Set.of(
        "com.easycrm.tenant.Tenant",
        "com.easycrm.iam.RefreshToken",   // pre-auth session table, looked up by hash
        "com.easycrm.sales.ShareLink"     // pre-auth share table: resolves the tenant itself
    );
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.ShareLinkRepositoryTest' --tests 'com.easycrm.arch.TenantScopingArchTest'`
Expected: PASS. Without the allowlist entry the ArchUnit test fails — confirm you saw it pass only after adding it.

- [ ] **Step 7: Run the whole suite**

Run: `cd backend && ./gradlew test`
Expected: 210 tests, 0 failures.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V25__share_link.sql backend/src/main/java/com/easycrm/sales/ShareLink.java backend/src/main/java/com/easycrm/sales/ShareLinkRepository.java backend/src/test/java/com/easycrm/sales/ShareLinkRepositoryTest.java backend/src/test/java/com/easycrm/arch/TenantScopingArchTest.java
git commit -m "feat(sales): global share_link table mapping a token to tenant and version

A public request carries no JWT and therefore no tenant, so a token column on
quotation_version would be unlookupable — RLS returns zero rows without a
tenant. The mapping has to live in a table that is readable without one.
Allowlisted in ArchUnit alongside refresh_token."
```

---

## Task 7: `POST /api/v1/quotations/{id}/share` and the `wa.me` link

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/ShareLinkService.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/dto/ShareResponse.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/easycrm/sales/web/QuotationController.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/QuotationShareTest.java`

**Interfaces:**
- Consumes: `QuotationRepository`, `QuotationVersionRepository`, `ShareLinkRepository` (Task 6), `CustomerRepository`, `ContactRepository#findByCustomerId(UUID)` → `List<Contact>`, `TenantRepository`, `IndianFormats` (Task 2).
- Produces:
  - `ShareResponse(String publicUrl, String waMeUrl)`
  - `ShareLinkService#share(UUID quotationId)` → `ShareResponse`
  - `ShareLinkService#resolve(String token)` → `ShareLinkService.Resolved(UUID tenantId, UUID quotationVersionId)`, throwing `NotFoundException` when the token is unknown (used by Task 8)

- [ ] **Step 1: Add the public base URL property**

In `backend/src/main/resources/application.yml`, under the existing `easycrm:` block:

```yaml
easycrm:
  public-base-url: ${PUBLIC_BASE_URL:http://localhost:8080}
```

(keep the existing `jwt:` sub-block as it is).

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/easycrm/sales/web/QuotationShareTest.java`:

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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class QuotationShareTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String customer(String auth, String state) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"businessName":"Bharat Industries","stateCode":"%s","source":"MANUAL"}"""
                    .formatted(state)))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String sentQuotation(String auth, String customerId) throws Exception {
        String pId = JsonPath.read(mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"sku":"SKU-%s","name":"Widget","hsnCode":"84821011","uom":"PCS",
                     "gstRate":"18","baseRate":"100.00"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 8))))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String qId = JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(
                    "{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"10\"}]}"
                        .formatted(customerId, pId)))
            .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth));
        return qId;
    }

    private void addContact(String auth, String customerId, String phone, String whatsapp)
            throws Exception {
        mvc.perform(post("/api/v1/customers/" + customerId + "/contacts")
            .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Ramesh","phone":%s,"whatsappNumber":%s,"isPrimary":true}"""
                .formatted(phone == null ? "null" : "\"" + phone + "\"",
                           whatsapp == null ? "null" : "\"" + whatsapp + "\"")));
    }

    @Test
    void sharingTwiceReturnsTheSameLink() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String qId = sentQuotation(auth, customer(auth, "27"));

        String first = JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/share")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(), "$.publicUrl");
        String second = JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/share")
                .header("Authorization", auth))
            .andReturn().getResponse().getContentAsString(), "$.publicUrl");

        // A link already WhatsApped to a customer must not stop working because
        // someone pressed share again.
        assertEquals(first, second);
    }

    @Test
    void theWaMeLinkPrefersTheWhatsappNumberOverThePhone() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String cId = customer(auth, "27");
        addContact(auth, cId, "+919000000001", "+919876543210");
        String qId = sentQuotation(auth, cId);

        String waMe = JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/share")
            .header("Authorization", auth)).andReturn().getResponse().getContentAsString(),
            "$.waMeUrl");

        assertTrue(waMe.startsWith("https://wa.me/919876543210?text="), waMe);
        assertFalse(waMe.contains("919000000001"), waMe);
        assertTrue(waMe.contains("%2F"), waMe);   // the public URL's slashes are encoded
    }

    @Test
    void fallsBackToThePlainPhoneWhenThereIsNoWhatsappNumber() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String cId = customer(auth, "27");
        addContact(auth, cId, "+919000000001", null);
        String qId = sentQuotation(auth, cId);

        String waMe = JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/share")
            .header("Authorization", auth)).andReturn().getResponse().getContentAsString(),
            "$.waMeUrl");

        assertTrue(waMe.startsWith("https://wa.me/919000000001?text="), waMe);
    }

    @Test
    void aCustomerWithNoContactStillGetsAShareableLink() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String qId = sentQuotation(auth, customer(auth, "27"));   // no contact at all

        String waMe = JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/share")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(), "$.waMeUrl");

        // No number: WhatsApp opens its own contact picker. Never block the share.
        assertTrue(waMe.startsWith("https://wa.me/?text="), waMe);
    }

    @Test
    void aDraftCannotBeShared() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String cId = customer(auth, "27");
        String pId = JsonPath.read(mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"sku":"SKU-%s","name":"Widget","hsnCode":"84821011","uom":"PCS",
                     "gstRate":"18","baseRate":"100.00"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 8))))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String qId = JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(
                    "{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"1\"}]}"
                        .formatted(cId, pId)))
            .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(post("/api/v1/quotations/" + qId + "/share").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void crossTenantShareReturns404() throws Exception {
        String authA = "Bearer " + tokens.provisionOwner("27").token();
        String qId = sentQuotation(authA, customer(authA, "27"));

        String authB = "Bearer " + tokens.provisionOwner("29").token();
        mvc.perform(post("/api/v1/quotations/" + qId + "/share").header("Authorization", authB))
            .andExpect(status().isNotFound());
    }
}
```

Before running, confirm the contact-create endpoint path and body field names against `backend/src/main/java/com/easycrm/crm/web/ContactController.java` and `ContactRequest`. If they differ from `POST /api/v1/customers/{id}/contacts` with `{name, phone, whatsappNumber, isPrimary}`, fix the helper — do not change the production API to match the test.

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationShareTest'`
Expected: FAIL — 404, the `/share` endpoint does not exist.

- [ ] **Step 4: Write the DTO**

Create `backend/src/main/java/com/easycrm/sales/web/dto/ShareResponse.java`:

```java
package com.easycrm.sales.web.dto;

public record ShareResponse(String publicUrl, String waMeUrl) {}
```

- [ ] **Step 5: Write the service**

Create `backend/src/main/java/com/easycrm/sales/ShareLinkService.java`:

```java
package com.easycrm.sales;

import com.easycrm.crm.Contact;
import com.easycrm.crm.ContactRepository;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.format.IndianFormats;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.web.dto.ShareResponse;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ShareLinkService {

    /** 16 bytes = 128 bits of entropy: not guessable, not enumerable. */
    private static final int TOKEN_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShareLinkRepository links;
    private final QuotationRepository quotations;
    private final QuotationVersionRepository versions;
    private final ContactRepository contacts;
    private final TenantRepository tenants;
    private final String publicBaseUrl;

    public ShareLinkService(ShareLinkRepository links, QuotationRepository quotations,
                            QuotationVersionRepository versions, ContactRepository contacts,
                            TenantRepository tenants,
                            @Value("${easycrm.public-base-url}") String publicBaseUrl) {
        this.links = links;
        this.quotations = quotations;
        this.versions = versions;
        this.contacts = contacts;
        this.tenants = tenants;
        this.publicBaseUrl = publicBaseUrl;
    }

    /** Token -> the tenant and version it points at. The only pre-auth read in the app. */
    public record Resolved(UUID tenantId, UUID quotationVersionId) {}

    @Transactional
    public ShareResponse share(UUID quotationId) {
        Quotation q = quotations.findById(quotationId)
            .orElseThrow(() -> new NotFoundException("quotation not found"));
        if (q.getCurrentVersionId() == null || q.getQuoteNo() == null) {
            throw new ValidationException("status", "send the quotation before sharing it");
        }
        QuotationVersion v = versions.findById(q.getCurrentVersionId())
            .orElseThrow(() -> new NotFoundException("quotation version not found"));
        if (v.getStatus() != VersionStatus.SENT) {
            throw new ValidationException("status", "send the quotation before sharing it");
        }

        // Idempotent: reuse the version's existing link so a URL already sent to a
        // customer keeps working. This is only possible because the token is stored
        // in plaintext — see ShareLink's class comment.
        ShareLink link = links.findByQuotationVersionId(v.getId())
            .orElseGet(() -> links.save(new ShareLink(newToken(), TenantContext.tenantId(), v.getId())));

        String publicUrl = publicBaseUrl + "/public/q/" + link.getToken();
        return new ShareResponse(publicUrl, waMeUrl(q, v, publicUrl));
    }

    @Transactional(readOnly = true)
    public Resolved resolve(String token) {
        // Deliberately the same 404 for unknown and malformed: a distinguishable
        // response would confirm which tokens exist.
        ShareLink link = links.findByToken(token)
            .orElseThrow(() -> new NotFoundException("not found"));
        return new Resolved(link.getTenantId(), link.getQuotationVersionId());
    }

    private static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String waMeUrl(Quotation q, QuotationVersion v, String publicUrl) {
        Tenant tenant = tenants.findById(TenantContext.tenantId())
            .orElseThrow(() -> new NotFoundException("tenant not found"));
        Optional<Contact> primary = primaryContact(q.getCustomerId());
        String number = primary.map(c -> c.getWhatsappNumber() != null
                ? c.getWhatsappNumber() : c.getPhone())
            .filter(s -> s != null && !s.isBlank())
            .map(ShareLinkService::digitsOnly)
            .orElse("");

        String greeting = primary.map(Contact::getName)
            .filter(n -> n != null && !n.isBlank())
            .map(n -> "Namaste " + n + ",").orElse("Namaste,");
        String message = greeting
            + " please find our quotation " + q.getQuoteNo()
            + " for " + IndianFormats.rupees(v.getGrandTotal())
            + (v.getValidUntil() == null ? ""
                : ", valid until " + IndianFormats.date(v.getValidUntil()))
            + ".\n" + publicUrl
            + "\n- " + tenant.getBusinessName();

        // No number is not an error: wa.me with only text opens WhatsApp's contact
        // picker, which costs the salesperson one tap instead of blocking the share.
        return "https://wa.me/" + number + "?text="
             + URLEncoder.encode(message, StandardCharsets.UTF_8);
    }

    private Optional<Contact> primaryContact(UUID customerId) {
        List<Contact> all = contacts.findByCustomerId(customerId);
        return all.stream()
            .max(Comparator.comparing(Contact::isPrimary));   // a primary one, else any
    }

    /** wa.me wants digits only: no +, spaces or dashes. */
    private static String digitsOnly(String phone) {
        return phone.replaceAll("\\D", "");
    }
}
```

- [ ] **Step 6: Add the endpoint**

In `QuotationController`, add the `ShareLinkService` to the constructor alongside the two existing services, and add:

```java
    @PostMapping("/{id}/share")
    public ShareResponse share(@PathVariable UUID id) { return shareLinks.share(id); }
```

with imports `com.easycrm.sales.ShareLinkService` and `com.easycrm.sales.web.dto.ShareResponse`.

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationShareTest'`
Expected: PASS, 6 tests.

- [ ] **Step 8: Run the whole suite**

Run: `cd backend && ./gradlew test`
Expected: 216 tests, 0 failures.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/ShareLinkService.java backend/src/main/java/com/easycrm/sales/web/dto/ShareResponse.java backend/src/main/java/com/easycrm/sales/web/QuotationController.java backend/src/main/resources/application.yml backend/src/test/java/com/easycrm/sales/web/QuotationShareTest.java
git commit -m "feat(sales): POST /api/v1/quotations/{id}/share with a wa.me deep link

Idempotent per version, so pressing share twice cannot break a link already
sent to a customer. The recipient number is the primary contact's WhatsApp
number, falling back to their phone; with neither, the link carries text only
and WhatsApp opens its contact picker rather than the share failing."
```

---

## Task 8: `GET /public/q/{token}` — the unauthenticated read path

The isolation-critical task. Read §5 of the design spec before starting.

**Files:**
- Modify: `backend/src/main/java/com/easycrm/platform/tenancy/TenantContext.java`
- Modify: `backend/src/main/java/com/easycrm/platform/security/SecurityConfig.java`
- Create: `backend/src/main/java/com/easycrm/sales/web/PublicShareController.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/PublicShareTest.java`

**Interfaces:**
- Consumes: `ShareLinkService#resolve(String)` → `Resolved(tenantId, quotationVersionId)` (Task 7); `QuotationPdfService#renderByVersionId(UUID)` (Task 5).
- Produces: `TenantContext#runAs(TenantPrincipal, Supplier<T>)` → `T` — a value-returning sibling of the existing `Runnable` overload.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/sales/web/PublicShareTest.java`:

```java
package com.easycrm.sales.web;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PublicShareTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String sentQuotationId(String auth, String buyerName) throws Exception {
        String cId = JsonPath.read(mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"businessName":"%s","stateCode":"27","source":"MANUAL"}""".formatted(buyerName)))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String pId = JsonPath.read(mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"sku":"SKU-%s","name":"Widget","hsnCode":"84821011","uom":"PCS",
                     "gstRate":"18","baseRate":"100.00"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 8))))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String qId = JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(
                    "{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"10\"}]}"
                        .formatted(cId, pId)))
            .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth));
        return qId;
    }

    private String shareToken(String auth, String quotationId) throws Exception {
        String url = JsonPath.read(mvc.perform(post("/api/v1/quotations/" + quotationId + "/share")
            .header("Authorization", auth)).andReturn().getResponse().getContentAsString(),
            "$.publicUrl");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void rendersTheQuotationWithNoAuthorizationHeaderAtAll() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String token = shareToken(auth, sentQuotationId(auth, "Bharat Industries"));
        TenantContext.clear();

        byte[] pdf = mvc.perform(get("/public/q/" + token))   // deliberately no header
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andReturn().getResponse().getContentAsByteArray();

        // The tenant came from the share_link row, not from a JWT.
        assertTrue(textOf(pdf).contains("Bharat Industries"), textOf(pdf));
    }

    @Test
    void anUnknownTokenReturns404AndNotA401() throws Exception {
        // 401 would prove the route is auth-gated and leak that the token space exists;
        // 404 matches the codebase's cross-tenant rule.
        mvc.perform(get("/public/q/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/public/q/not-a-real-token")).andExpect(status().isNotFound());
    }

    @Test
    void oneTenantsTokenNeverRendersAnothersQuotation() throws Exception {
        String authA = "Bearer " + tokens.provisionOwner("27").token();
        String tokenA = shareToken(authA, sentQuotationId(authA, "Tenant A Buyer"));
        TenantContext.clear();

        String authB = "Bearer " + tokens.provisionOwner("29").token();
        sentQuotationId(authB, "Tenant B Buyer");
        TenantContext.clear();

        String text = textOf(mvc.perform(get("/public/q/" + tokenA))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());

        assertTrue(text.contains("Tenant A Buyer"), text);
        assertFalse(text.contains("Tenant B Buyer"), text);
    }

    @Test
    void anAlreadySharedVersionKeepsRenderingAfterTheQuotationIsRevised() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String qId = sentQuotationId(auth, "Bharat Industries");
        String v1Token = shareToken(auth, qId);

        mvc.perform(post("/api/v1/quotations/" + qId + "/revise").header("Authorization", auth));
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth));
        String v2Token = shareToken(auth, qId);
        TenantContext.clear();

        assertNotEquals(v1Token, v2Token);
        // The customer who received the v1 link still sees exactly what they were sent.
        assertTrue(textOf(mvc.perform(get("/public/q/" + v1Token))
            .andReturn().getResponse().getContentAsByteArray()).contains("(v1)"));
        assertTrue(textOf(mvc.perform(get("/public/q/" + v2Token))
            .andReturn().getResponse().getContentAsByteArray()).contains("(v2)"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.PublicShareTest'`
Expected: FAIL — the route does not exist, and `SecurityConfig`'s `anyRequest().denyAll()` rejects `/public/**` anyway.

- [ ] **Step 3: Add a value-returning `runAs`**

In `backend/src/main/java/com/easycrm/platform/tenancy/TenantContext.java`, add next to the existing `runAs`:

```java
    /**
     * Value-returning sibling of {@link #runAs(TenantPrincipal, Runnable)}. The tenant
     * must be established BEFORE the transaction opens: TenantAwareTransactionManager
     * reads it in doBegin to set the RLS GUC, and Hibernate resolves a session's tenant
     * once at session-open and never re-reads it (see challenge #9).
     */
    public static <T> T runAs(TenantPrincipal principal, java.util.function.Supplier<T> body) {
        TenantPrincipal previous = HOLDER.get();
        HOLDER.set(principal);
        try {
            return body.get();
        } finally {
            if (previous == null) HOLDER.remove(); else HOLDER.set(previous);
        }
    }
```

- [ ] **Step 4: Open the public route**

In `SecurityConfig`, add the `permitAll` rule before the `/api/**` rule:

```java
                .requestMatchers("/actuator/health").permitAll()
                // Public share links: no JWT. The tenant is resolved from the share_link
                // row itself, and every read behind it still goes through @TenantId + RLS.
                .requestMatchers(HttpMethod.GET, "/public/q/*").permitAll()
                .requestMatchers(HttpMethod.POST,
```

`JwtAuthenticationFilter` needs no change: it already passes a request through untouched when there is no `Authorization` header.

- [ ] **Step 5: Write the controller**

Create `backend/src/main/java/com/easycrm/sales/web/PublicShareController.java`:

```java
package com.easycrm.sales.web;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.ShareLinkService;
import com.easycrm.sales.pdf.QuotationPdfService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The application's only unauthenticated read path.
 *
 * A public request has no JWT, so it has no tenant, so every tenant-scoped query would
 * return zero rows. The share_link table is global precisely so this one lookup can
 * happen without a tenant; the tenant it yields is then installed via runAs BEFORE the
 * rendering transaction opens, and everything after that is ordinary @TenantId + RLS
 * loading. The token is never echoed into a response or a log.
 */
@RestController
@RequestMapping("/public/q")
public class PublicShareController {

    private final ShareLinkService shareLinks;
    private final QuotationPdfService pdfService;

    public PublicShareController(ShareLinkService shareLinks, QuotationPdfService pdfService) {
        this.shareLinks = shareLinks;
        this.pdfService = pdfService;
    }

    @GetMapping("/{token}")
    public ResponseEntity<byte[]> quotation(@PathVariable String token) {
        ShareLinkService.Resolved resolved = shareLinks.resolve(token);   // 404 if unknown
        byte[] pdf = TenantContext.runAs(
            new TenantContext.TenantPrincipal(resolved.tenantId(), null, "PUBLIC"),
            () -> pdfService.renderByVersionId(resolved.quotationVersionId()));
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
            .body(pdf);
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.PublicShareTest'`
Expected: PASS, 4 tests.

If the render inside `runAs` returns "not found", the tenant is not reaching the transaction — check that `renderByVersionId` is called through the Spring proxy (it is, being a different bean) and that `runAs` wraps the call rather than being wrapped by it.

- [ ] **Step 7: Run the whole suite**

Run: `cd backend && ./gradlew test`
Expected: 220 tests, 0 failures.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/easycrm/platform/tenancy/TenantContext.java backend/src/main/java/com/easycrm/platform/security/SecurityConfig.java backend/src/main/java/com/easycrm/sales/web/PublicShareController.java backend/src/test/java/com/easycrm/sales/web/PublicShareTest.java
git commit -m "feat(sales): GET /public/q/{token} serves a shared quotation without auth

The tenant comes from the share_link row rather than a JWT, installed via a
value-returning TenantContext.runAs before the rendering transaction opens so
the RLS GUC is set and Hibernate resolves the right tenant at session-open.
Unknown and malformed tokens both 404, so the response cannot confirm which
tokens exist."
```

---

## Task 9: Fix `QuotationService.list`'s dropped filter

Carried over from the order-lifecycle whole-branch review, which asked that this lead the next slice. `?status=` and `?customerId=` together currently ignores the customer.

**Files:**
- Create: `backend/src/main/java/com/easycrm/sales/QuotationSpecifications.java`
- Modify: `backend/src/main/java/com/easycrm/sales/QuotationRepository.java`
- Modify: `backend/src/main/java/com/easycrm/sales/QuotationService.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/QuotationListTest.java` (existing file — add to it)

**Interfaces:**
- Consumes: `QuotationStatus`, `Quotation`.
- Produces: `QuotationSpecifications#filter(QuotationStatus status, UUID customerId)` → `Specification<Quotation>`.

- [ ] **Step 1: Write the failing test**

Add to the existing `backend/src/test/java/com/easycrm/sales/web/QuotationListTest.java`. Match the file's existing helpers for creating customers and quotations rather than introducing new ones:

```java
    @Test
    void statusAndCustomerFiltersApplyTogether() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String wanted = customer(auth, "Wanted Buyer");
        String other = customer(auth, "Other Buyer");

        String sentForWanted = quotation(auth, wanted);
        mvc.perform(post("/api/v1/quotations/" + sentForWanted + "/send").header("Authorization", auth));
        String sentForOther = quotation(auth, other);
        mvc.perform(post("/api/v1/quotations/" + sentForOther + "/send").header("Authorization", auth));
        quotation(auth, wanted);   // stays DRAFT

        // Both filters must AND. Before the fix this returned both SENT quotations,
        // because customerId was silently dropped when status was present.
        mvc.perform(get("/api/v1/quotations?status=SENT&customerId=" + wanted)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(sentForWanted));
    }
```

If `QuotationListTest` has no `customer(...)` / `quotation(...)` helpers, copy the pattern from `QuotationPdfEndpointTest` (Task 5) into this file.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationListTest'`
Expected: FAIL — `totalElements` is 2, not 1.

- [ ] **Step 3: Write the specification**

Create `backend/src/main/java/com/easycrm/sales/QuotationSpecifications.java`:

```java
package com.easycrm.sales;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class QuotationSpecifications {

    private QuotationSpecifications() {}

    /** AND-composes whichever filters are non-null. Tenant scoping comes from RLS, not here. */
    public static Specification<Quotation> filter(QuotationStatus status, UUID customerId) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (status != null)     ps.add(cb.equal(root.get("status"), status));
            if (customerId != null) ps.add(cb.equal(root.get("customerId"), customerId));
            return cb.and(ps.toArray(new Predicate[0])); // empty -> always-true conjunction
        };
    }
}
```

- [ ] **Step 4: Wire it in**

In `QuotationRepository`, extend `JpaSpecificationExecutor` and drop the two now-unused derived finders:

```java
public interface QuotationRepository
        extends JpaRepository<Quotation, UUID>, JpaSpecificationExecutor<Quotation> {
}
```

Add the import `org.springframework.data.jpa.repository.JpaSpecificationExecutor`. If any other class still calls `findByStatus` or `findByCustomerId`, keep those methods; otherwise remove them.

In `QuotationService.list`, replace the `if / else if` chain:

```java
    @Transactional(readOnly = true)
    public PageResponse<QuotationResponse> list(QuotationStatus status, UUID customerId,
                                                Pageable pageable) {
        Page<Quotation> page = quotations.findAll(
            QuotationSpecifications.filter(status, customerId), pageable);
        return PageResponse.of(page.map(this::toResponse));
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.sales.web.QuotationListTest'`
Expected: PASS — including the file's pre-existing single-filter and cross-tenant tests, which must keep passing unchanged.

- [ ] **Step 6: Run the whole suite**

Run: `cd backend && ./gradlew test`
Expected: 221 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/QuotationSpecifications.java backend/src/main/java/com/easycrm/sales/QuotationRepository.java backend/src/main/java/com/easycrm/sales/QuotationService.java backend/src/test/java/com/easycrm/sales/web/QuotationListTest.java
git commit -m "fix(sales): quotation list honours status and customerId together

The if/else-if chain silently dropped customerId whenever status was also
supplied. Replaced with an AND-composed Specification mirroring
OrderSpecifications, closing challenge #24 for the last list endpoint that
still had the bug."
```

---

## Task 10: Documentation wrap-up

Required by CLAUDE.md and not optional. Do this before proposing the merge.

**Files:**
- Modify: `docs/superpowers/engineering-challenges.md`
- Modify: `docs/superpowers/annotations-reference.md`
- Modify: `docs/superpowers/HANDOFF.md`
- Modify: `docs/superpowers/specs/2026-07-28-quotation-pdf-share-design.md` (status line only)

- [ ] **Step 1: Log challenge 28 — serving a tenant-scoped document to a request with no tenant**

Append to `engineering-challenges.md`, using the template at the bottom of that file. Cover: the public endpoint has no JWT → `TenantContext` empty → `TenantIdentifierResolver` yields `NO_TENANT` and the RLS GUC stays unset → every scoped query returns zero rows, so a token column on `quotation_version` is unlookupable by construction; the fix is one global RLS-exempt table holding nothing but token → tenant + version, then `runAs` **before** the transaction opens (tying back to challenge #9's session-open ordering); why the exception is narrow (no document data reachable through the global table, every content read still under `@TenantId` + RLS); and the lesson — when a pre-auth endpoint must reach tenant-scoped data, the resolution table has to sit outside the isolation boundary, and keeping it free of everything but identifiers is what keeps the exception small.

- [ ] **Step 2: Log challenge 29 — a PDF is not a pure function of its input by default**

Cover: the spec requires byte-identical output, but PDF writers stamp a wall-clock creation date and a document ID, so two renders of the same frozen version differ; a naive "just render it twice, it's deterministic because the data is immutable" assumption is wrong; the fix is stamping creation/modification date and producer from the version's `sentAt` so the render is a pure function of stored data, with `assertArrayEquals` on two renders as the regression guard; the lesson — "the inputs are immutable" does not make the output reproducible when the format itself embeds ambient state, and the assertion is what turns the claim into a property.

- [ ] **Step 3: Update the annotations reference**

Add rows for anything new this slice introduced that is not already present. Check before adding: `@Value` (constructor-injected config property), and confirm whether `@RequestParam(required = false)` and `@PathVariable` already have rows. Do not invent entries for annotations the slice did not use.

- [ ] **Step 4: Update the handoff**

In `docs/superpowers/HANDOFF.md`: add the spec and plan to the §2 reading list; update §3's "Current state" and the merged-work list with this slice and the new test total; move **PDF generation and the `wa.me` share link** out of §4's deferred list, marking it done; strike backlog item #1 in §8 (the `QuotationService.list` filter fix) since Task 9 closes it; and rewrite §8's "next chunk" so PDF/WhatsApp is no longer the suggested default — the remaining candidates are activity/follow-up, scheduled auto-expiry, the auth follow-up, and cursor pagination.

Add to §8's smaller-backlog list, since this slice deliberately deferred them:
- no rate limiting on `/public/q/{token}` — the only unauthenticated route in the app;
- no expiry or revoke on a share link;
- `QuotationSpecifications` joins `OrderSpecifications` and `EnquirySpecifications` in using string-keyed `root.get(...)` rather than a JPA static metamodel (existing backlog item — update its wording to name all three).

- [ ] **Step 5: Flip the spec's status line**

In `docs/superpowers/specs/2026-07-28-quotation-pdf-share-design.md`, change `**Status:** designed, not yet implemented` to `**Status:** implemented, merged to \`main\` as \`<merge-sha>\`` once the merge commit exists. If the merge has not happened yet, leave a note and update it at merge time.

- [ ] **Step 6: Run the whole suite one final time**

Run: `cd backend && ./gradlew clean test`
Expected: 221 tests, 0 failures, from a clean build.

- [ ] **Step 7: Commit**

```bash
git add docs/superpowers
git commit -m "docs(sales): challenges, annotations and handoff for the PDF/share slice"
```

---

## Verification Checklist

Before proposing the merge, confirm each of these by running it — not by reading the code:

- [ ] `cd backend && ./gradlew clean test` → 221 tests, 0 failures
- [ ] `GET /public/q/{token}` returns a PDF with **no** `Authorization` header
- [ ] An unknown public token returns **404**, never 401 or 500
- [ ] A tenant-B token never renders tenant-A content, and vice versa
- [ ] Sharing the same version twice returns the identical `publicUrl`
- [ ] Rendering the same version twice yields identical bytes
- [ ] An intra-state quote shows CGST/SGST only; an inter-state quote shows IGST only
- [ ] `?status=` + `?customerId=` on the quotation list AND together
- [ ] The share token appears in **no** log line and **no** error response body
- [ ] The ArchUnit test passes with `ShareLink` allowlisted
- [ ] No commit message mentions Claude or AI, and every commit is authored `divyam <divyam.0444@gmail.com>`
