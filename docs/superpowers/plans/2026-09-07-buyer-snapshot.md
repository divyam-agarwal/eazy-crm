# Buyer Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Freeze the buyer's business name, GSTIN and billing address into `QuotationVersion` at
`send()`, so a `SENT` quotation stops re-rendering differently after someone edits the customer.

**Architecture:** Three new nullable columns on `quotation_version`, mapped through a
`BuyerSnapshot` `@Embeddable`. `QuotationService.send()` reads the customer once, guards that its
`stateCode` still matches the version's frozen `placeOfSupply`, then freezes the three fields.
`QuotationPdfService` reads the frozen snapshot instead of the live `Customer`, and drops its
`com.easycrm.crm.Customer` import entirely. A per-tenant backfill loop inside the migration
populates existing `SENT` rows.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Hibernate/JPA, Flyway, PostgreSQL (RLS FORCEd),
JUnit 5 + MockMvc + Testcontainers, PDFBox (in tests), Spotless + SpotBugs, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-07-buyer-snapshot-design.md` — read it before Task 1. The
plan argues from the spec; where this plan says "why", the spec says it at length.

## Global Constraints

- **Working directory is `backend/`.** Every path below is relative to it unless it starts with
  `docs/`. The Gradle wrapper is `backend/gradlew`.
- **Money is never a `double`.** Not directly at issue here, but `BigDecimal` in Java / `NUMERIC`
  in Postgres / JSON string on the wire is absolute. `CLAUDE.md`.
- **Never hand-write `WHERE tenant_id = ?`.** Tenant scoping is `@TenantId` + RLS. `CLAUDE.md`.
  The one deliberate exception is the migration in Task 2, which sets the RLS GUC per tenant rather
  than filtering by hand — read §3.3 of the spec before writing it.
- **Never reformat or edit an applied Flyway migration.** Flyway checksums them. `V34` is new; do
  not touch `V1`–`V33`.
- **Commit as `divyam`.** Plain `git commit`, no `-c user.name=` override, no `Co-Authored-By:
  Claude` trailer, no mention of Claude or AI anywhere in the message. `CLAUDE.md`.
- **The full gate is `./gradlew clean check`** — test + Spotless + SpotBugs + JaCoCo across both
  projects. Baseline on `main` at `264bc3b` is **586 tests, 0 failures, 0 errors**. Every task that
  adds tests raises that number; the final task states the new total.
- **Run Spotless before every commit.** `./gradlew spotlessApply`. Challenge 67 records what
  happens when a slice runs only targeted tests between full-gate runs: violations accumulate
  invisibly and land as a separate fix-up commit.
- **New annotations get a row in `docs/superpowers/annotations-reference.md`** in the same change.
  Task 3 introduces `@Embeddable` and `@Embedded`; neither is in the codebase today.
- **Do not change `QuotationVersionResponse` or any DTO.** Spec decision B7. If
  `OpenApiSnapshotTest` fails, something touched the contract that should not have.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `src/test/java/com/easycrm/sales/web/QuotationBuyerSnapshotTest.java` (new) | The whole slice's behaviour: determinism across a customer edit, freeze-on-send, the state guard, revision re-freeze, and the public-share path | 1, 4, 5, 6 |
| `src/main/resources/db/migration/V34__quotation_version_buyer_snapshot.sql` (new) | Three columns + the per-tenant backfill | 2 |
| `src/main/java/com/easycrm/sales/BuyerSnapshot.java` (new) | The frozen buyer as one value object | 3 |
| `src/main/java/com/easycrm/sales/QuotationVersion.java` | Holds the snapshot; `freezeBuyer(...)` | 3 |
| `src/main/java/com/easycrm/sales/QuotationService.java` | Freezes at `send()`; the state-code guard | 4, 5 |
| `src/main/java/com/easycrm/sales/pdf/QuotationPdfService.java` | Reads the snapshot, not the customer | 6 |
| `docs/superpowers/engineering-challenges.md` | Challenge 68 | 7 |
| `docs/superpowers/annotations-reference.md` | `@Embeddable`, `@Embedded` | 3 |
| `docs/ROADMAP.md`, `docs/architecture/2026-08-24-service-scope-and-shared-modules.md`, three handoffs | H1 closed, S2 fold declined | 7 |

**Task order is dependency order.** Task 1's test must fail for the *right reason* (a changed PDF),
which requires no production code at all. Task 2 (schema) precedes Task 3 (mapping) because
`ddl-auto: validate` fails the whole context at startup if an `@Embedded` field has no columns —
every test in the suite would error, not just the new ones.

---

### Task 1: The failing regression test

This is F11 itself. It must fail on `main` before any production code exists, and it must fail by
showing two *different* PDFs — not by erroring.

**Files:**
- Create: `src/test/java/com/easycrm/sales/web/QuotationBuyerSnapshotTest.java`

**Interfaces:**
- Consumes: `IntegrationTest`, `TestTokens` (from `com.easycrm.support`), the existing
  `/api/v1/customers`, `/api/v1/products`, `/api/v1/quotations` routes.
- Produces: the private helpers `createCustomer`, `draftFor`, `pdfOf`, `updateCustomer` — Tasks 4,
  5 and 6 add tests to this same file and reuse them verbatim.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/easycrm/sales/web/QuotationBuyerSnapshotTest.java`:

```java
package com.easycrm.sales.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * F11: a SENT version must render identically no matter what happens to the customer
 * afterwards. Challenge 28 pinned the renderer deterministic; this pins its inputs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class QuotationBuyerSnapshotTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private String createCustomer(String auth, String state) throws Exception {
        String body =
                """
            {"businessName":"Bharat Industries","gstin":"27AAAAA0000A1Z5","stateCode":"%s",
             "billingAddress":"12 MG Road, Pune","source":"MANUAL"}"""
                        .formatted(state);
        return JsonPath.read(
                mvc.perform(post("/api/v1/customers")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    /** A full-body PUT: CustomerRequest has no PATCH form, so unsent fields would be nulled. */
    private void updateCustomer(String auth, String customerId, String name, String gstin, String address, String state)
            throws Exception {
        String body =
                """
            {"businessName":"%s","gstin":"%s","stateCode":"%s",
             "billingAddress":"%s","source":"MANUAL"}"""
                        .formatted(name, gstin, state, address);
        mvc.perform(put("/api/v1/customers/" + customerId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private String draftFor(String auth, String customerId) throws Exception {
        String prod =
                """
            {"sku":"SKU-%s","name":"Ball Bearing 6203","hsnCode":"84821011",
             "uom":"PCS","gstRate":"18","baseRate":"100.00"}"""
                        .formatted(UUID.randomUUID().toString().substring(0, 8));
        String pId = JsonPath.read(
                mvc.perform(post("/api/v1/products")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(prod))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        String body = """
            {"customerId":"%s","items":[{"productId":"%s","qty":"10"}]}"""
                .formatted(customerId, pId);
        return JsonPath.read(
                mvc.perform(post("/api/v1/quotations")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    private byte[] pdfOf(String auth, String quotationId) throws Exception {
        return mvc.perform(get("/api/v1/quotations/" + quotationId + "/pdf").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
    }

    @Test
    void sentQuotationRendersIdenticallyAfterTheCustomerIsEdited() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String cId = createCustomer(auth, "27");
        String qId = draftFor(auth, cId);
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth))
                .andExpect(status().isOk());

        byte[] before = pdfOf(auth, qId);

        // An ordinary, correct edit. The state code is deliberately unchanged: this test is
        // about buyer identity, and moving the state is Task 5's separate concern.
        updateCustomer(auth, cId, "Bharat Industries Pvt Ltd", "27BBBBB1111B1Z5", "99 FC Road, Pune", "27");

        assertArrayEquals(before, pdfOf(auth, qId), "a SENT version must not re-render after a customer edit");
    }
}
```

- [ ] **Step 2: Run it and confirm it fails for the right reason**

```bash
cd backend && ./gradlew test --tests '*QuotationBuyerSnapshotTest*'
```

Expected: **FAIL** on `assertArrayEquals` — "array contents differ". That is F11 reproduced.

**If it fails any other way, stop and report before continuing.** An error, an exception, or a
failure inside a helper means the fixture is wrong, not that the bug is proven. In particular a
`gstin` uniqueness clash (`uq_customer_tenant_gstin`) means the GSTIN literals need to be unique
per run — make them so rather than removing the assertion.

- [ ] **Step 3: Commit the failing test**

```bash
cd backend && ./gradlew spotlessApply
git add src/test/java/com/easycrm/sales/web/QuotationBuyerSnapshotTest.java
git commit -m "test: reproduce F11 — a SENT quotation re-renders after a customer edit

QuotationVersion freezes items, totals and placeOfSupply but not the
buyer, so QuotationPdfService reads businessName, gstin and
billingAddress live from customer at render time. Editing a customer
changes a document that was already sent, including through a public
share link the buyer holds.

Red on purpose: the next commits make it pass."
```

---

### Task 2: The migration and its backfill

**Files:**
- Create: `src/main/resources/db/migration/V34__quotation_version_buyer_snapshot.sql`

**Interfaces:**
- Produces: columns `buyer_business_name VARCHAR(255)`, `buyer_gstin VARCHAR(15)`,
  `buyer_billing_address VARCHAR(512)` on `quotation_version`. Task 3 maps exactly these names.

**Read spec §3.3 before writing this.** The obvious one-line `UPDATE` matches zero rows, commits,
and reports success — Flyway connects as `easycrm_owner`, `V26` FORCEs RLS on both tables, and the
policy predicate is `NULL` when no GUC is set. So is the obvious post-check, if written outside the
loop.

- [ ] **Step 1: Write the migration**

Create `src/main/resources/db/migration/V34__quotation_version_buyer_snapshot.sql`:

```sql
-- F11: QuotationVersion froze the items, the totals, the terms and the place of supply,
-- but not the buyer. QuotationPdfService read businessName, gstin and billingAddress live
-- from `customer` at render time, so editing a customer silently changed a document that
-- had already been sent — including through a public share link the buyer already held.
--
-- Nullable on purpose: a DRAFT has no buyer frozen yet. The invariant is not-null when
-- SENT, and QuotationService.send() is what establishes it. A NOT NULL column with a
-- placeholder default would make "never frozen" indistinguishable from a real value.
--
-- Widths mirror `customer` exactly. A snapshot narrower than its source truncates on freeze.
ALTER TABLE quotation_version
  ADD COLUMN buyer_business_name   VARCHAR(255),
  ADD COLUMN buyer_gstin           VARCHAR(15),
  ADD COLUMN buyer_billing_address VARCHAR(512);

-- The backfill has to cross tenants, and it CANNOT do so by ignoring RLS.
--
-- Flyway connects as easycrm_owner (spring.flyway.user), which owns these tables, and
-- V26 FORCEd row-level security precisely so the owner is bound by it too. Every policy
-- in this schema reads:
--
--     USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
--
-- The `true` is missing_ok. In a Flyway session nothing sets that GUC, so current_setting
-- returns NULL, the comparison is NULL, and it is never true. A plain cross-tenant UPDATE
-- here would match ZERO ROWS, commit, and report success — no error, no warning, no log
-- line. V26's own header predicted this by name: "a migration tool reused for a backfill".
-- This is the repo's first DML migration and so the first to meet it.
--
-- Rejected: NO FORCE around the backfill (a mid-migration failure leaves the table
-- unforced — a silent isolation hole introduced by the fix for a silent bug), and
-- BYPASSRLS on easycrm_owner (a permanent role attribute weakening layer 3 forever to
-- serve one migration). Instead: drive it per tenant from INSIDE the policy. `tenant` has
-- no tenant_id column and no RLS, so the registry itself is readable here.
DO $$
DECLARE
  t     uuid;
  stale integer;
BEGIN
  FOR t IN SELECT id FROM tenant LOOP
    PERFORM set_config('app.current_tenant', t::text, true);

    UPDATE quotation_version v
       SET buyer_business_name   = c.business_name,
           buyer_gstin           = c.gstin,
           buyer_billing_address = c.billing_address
      FROM quotation q
      JOIN customer c ON c.id = q.customer_id
     WHERE q.id = v.quotation_id
       AND v.status = 'SENT';

    -- This check MUST live inside the loop. Written after it, with the GUC reset, it
    -- would see zero rows through RLS and pass vacuously — as silently broken as the bug
    -- it exists to catch, and in exactly the same way.
    SELECT count(*) INTO stale
      FROM quotation_version
     WHERE status = 'SENT'
       AND buyer_business_name IS NULL;

    IF stale > 0 THEN
      RAISE EXCEPTION 'tenant %: % SENT quotation versions left unfrozen', t, stale;
    END IF;
  END LOOP;

  -- set_config(..., true) is transaction-local and Flyway wraps each migration in one,
  -- so this cannot leak; reset anyway, so nothing later in this file inherits a tenant.
  PERFORM set_config('app.current_tenant', '', true);
END $$;
```

- [ ] **Step 2: Verify it applies**

```bash
cd backend && ./gradlew test --tests '*RlsCoverageIntegrationTest*'
```

Expected: **PASS.** This boots the context against a Testcontainers Postgres, so it proves `V34`
applies cleanly and that the three new columns did not disturb layer-3 RLS coverage.

**Known and expected:** the backfill loop iterates zero tenants against a fresh container and
updates nothing. Spec §5 states this plainly — no test in this repo can prove the backfill works.
The in-migration `RAISE` is its only real check. Do not add a test that pretends otherwise.

- [ ] **Step 3: Commit**

```bash
cd backend && ./gradlew spotlessApply
git add src/main/resources/db/migration/V34__quotation_version_buyer_snapshot.sql
git commit -m "feat: add buyer snapshot columns to quotation_version

Three nullable columns mirroring customer's widths, plus a backfill for
existing SENT versions.

The backfill runs per tenant with app.current_tenant set, rather than
around RLS. Flyway connects as the table owner and V26 FORCEs RLS, so a
plain cross-tenant UPDATE would match zero rows and report success. The
post-check sits inside the loop for the same reason — outside it, with
no tenant set, it would pass vacuously."
```

---

### Task 3: `BuyerSnapshot` and the mapping

**Files:**
- Create: `src/main/java/com/easycrm/sales/BuyerSnapshot.java`
- Modify: `src/main/java/com/easycrm/sales/QuotationVersion.java`
- Modify: `docs/superpowers/annotations-reference.md`

**Interfaces:**
- Consumes: the three columns from Task 2.
- Produces: `BuyerSnapshot(String businessName, String gstin, String billingAddress)` with getters
  `getBusinessName()`, `getGstin()`, `getBillingAddress()`; and on `QuotationVersion`:
  `void freezeBuyer(String businessName, String gstin, String billingAddress)` and
  `BuyerSnapshot getBuyer()`. Tasks 4 and 6 use these exact names.

- [ ] **Step 1: Create the embeddable**

Create `src/main/java/com/easycrm/sales/BuyerSnapshot.java`:

```java
package com.easycrm.sales;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * The buyer as they were when the quotation was sent — frozen, like the line items and
 * the totals beside it, so a SENT version renders the same document forever (F11).
 *
 * Flat columns rather than the JSONB the AWS design's D10 named: QuotationItem already
 * freezes its product as name/hsn/uom snapshot columns in this same table family, and
 * ddl-auto: validate checks columns but cannot check inside a blob. See the design spec
 * §3.1 for the full reversal.
 *
 * Null for a DRAFT and non-null for a SENT version; QuotationService.send() is what
 * establishes that, and the database deliberately does not (a NOT NULL default would make
 * "never frozen" look like a real value).
 */
@Embeddable
public class BuyerSnapshot {

    @Column(name = "buyer_business_name")
    private String businessName;

    @Column(name = "buyer_gstin", length = 15)
    private String gstin;

    @Column(name = "buyer_billing_address", length = 512)
    private String billingAddress;

    protected BuyerSnapshot() {}

    public BuyerSnapshot(String businessName, String gstin, String billingAddress) {
        this.businessName = businessName;
        this.gstin = gstin;
        this.billingAddress = billingAddress;
    }

    public String getBusinessName() {
        return businessName;
    }

    public String getGstin() {
        return gstin;
    }

    public String getBillingAddress() {
        return billingAddress;
    }
}
```

- [ ] **Step 2: Map it onto `QuotationVersion`**

In `src/main/java/com/easycrm/sales/QuotationVersion.java`, add to the imports:

```java
import jakarta.persistence.Embedded;
```

Add the field immediately after the existing `sentAt` field:

```java
    /** Null until send() freezes it — see BuyerSnapshot. */
    @Embedded
    private BuyerSnapshot buyer;
```

Add `freezeBuyer` immediately after the existing `markSent` method:

```java
    public void freezeBuyer(String businessName, String gstin, String billingAddress) {
        this.buyer = new BuyerSnapshot(businessName, gstin, billingAddress);
    }
```

Add the getter at the end of the class, after `getSentAt()`:

```java
    public BuyerSnapshot getBuyer() {
        return buyer;
    }
```

- [ ] **Step 3: Verify the mapping validates against the schema**

```bash
cd backend && ./gradlew test --tests '*QuotationBuyerSnapshotTest*'
```

Expected: still **FAIL on `assertArrayEquals`**, exactly as in Task 1 — nothing writes or reads the
snapshot yet.

**What matters is that it still fails that way and not with a startup error.** `ddl-auto: validate`
runs at context startup: if a column name here disagrees with `V34`, every test in the class errors
before reaching the assertion. A green-looking run at this point would mean the class did not boot.

- [ ] **Step 4: Document the two new annotations**

Add rows to `docs/superpowers/annotations-reference.md` following the file's existing column
layout, for:

- **`@Embeddable`** — origin `jakarta.persistence`. Marks a class whose fields map into the
  *owning* entity's table rather than a table of its own. No identity, no lifecycle, no row: it is
  a value object, so it is loaded and saved with its owner and has no `@Id`. First used here for
  `BuyerSnapshot`.
- **`@Embedded`** — origin `jakarta.persistence`. Marks the field in the owning entity that holds
  an `@Embeddable`. Column names come from the `@Column` annotations inside the embeddable unless
  overridden with `@AttributeOverride`. First used here on `QuotationVersion.buyer`.

- [ ] **Step 5: Commit**

```bash
cd backend && ./gradlew spotlessApply
git add src/main/java/com/easycrm/sales/BuyerSnapshot.java \
        src/main/java/com/easycrm/sales/QuotationVersion.java \
        ../docs/superpowers/annotations-reference.md
git commit -m "feat: map a BuyerSnapshot embeddable onto QuotationVersion

Flat columns rather than D10's JSONB: QuotationItem already freezes its
product as flat snapshot columns in the same table family, and
ddl-auto: validate checks columns but not blob contents.

Nothing writes or reads it yet, so the F11 regression test still fails.
Adds the reference rows for @Embeddable and @Embedded, neither of which
the codebase used before."
```

---

### Task 4: Freeze on send

**Files:**
- Modify: `src/main/java/com/easycrm/sales/QuotationService.java` (the `send` method)
- Modify: `src/test/java/com/easycrm/sales/web/QuotationBuyerSnapshotTest.java`

**Interfaces:**
- Consumes: `QuotationVersion.freezeBuyer(String, String, String)` and `getBuyer()` from Task 3;
  the already-injected `VisibleFinder finder` field, whose signature is
  `Optional<Customer> findCustomer(UUID id)`.
- Produces: a `SENT` version whose `buyer` is non-null. Task 6 reads it.

- [ ] **Step 1: Write the failing test**

Append to `QuotationBuyerSnapshotTest`:

```java
    @Test
    void sendFreezesTheBuyerAndDraftHasNone() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String cId = createCustomer(auth, "27");
        String qId = draftFor(auth, cId);

        // A DRAFT has nothing frozen: the PDF route is the only reader, and it refuses a
        // draft outright, so the observable proof is that rendering is still rejected.
        mvc.perform(get("/api/v1/quotations/" + qId + "/pdf").header("Authorization", auth))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth))
                .andExpect(status().isOk());

        // The frozen values survive the customer being renamed out from under them.
        updateCustomer(auth, cId, "Renamed Entirely", "27CCCCC2222C1Z5", "Somewhere Else", "27");
        String text = textOf(pdfOf(auth, qId));
        org.junit.jupiter.api.Assertions.assertTrue(
                text.contains("Bharat Industries"), "the PDF must show the buyer frozen at send, not the live one");
        org.junit.jupiter.api.Assertions.assertFalse(
                text.contains("Renamed Entirely"), "the PDF must not show the edited customer");
    }
```

Add this helper beside `pdfOf`, and the two imports it needs
(`org.apache.pdfbox.pdmodel.PDDocument`, `org.apache.pdfbox.text.PDFTextStripper`):

```java
    /** Same approach as QuotationPdfEndpointTest: assert on text, not on raw PDF bytes. */
    private String textOf(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }
```

**If `PDDocument.load` does not compile,** match whatever `QuotationPdfEndpointTest.textOf` does —
PDFBox 3.x replaced it with `Loader.loadPDF(byte[])`. Copy that file's working form rather than
guessing.

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd backend && ./gradlew test --tests '*QuotationBuyerSnapshotTest*'
```

Expected: **FAIL** — the PDF still shows "Renamed Entirely", because nothing freezes yet.

- [ ] **Step 3: Freeze in `send()`**

In `QuotationService.send()`, after the `v.markSent(Instant.now());` line and before
`return toResponse(q);`:

```java
        // Freeze the buyer as they are NOW, not as they were when the draft was created:
        // a draft that sat for two weeks while someone corrected a typo'd GSTIN must send
        // the corrected one. From here the version renders the same document forever (F11).
        Customer customer = finder.findCustomer(q.getCustomerId())
                .orElseThrow(() -> new NotFoundException("customer not found"));
        v.freezeBuyer(customer.getBusinessName(), customer.getGstin(), customer.getBillingAddress());
```

`Customer` is already imported in this file (`create` and `replaceItems` both use it). Do not add
an import; if Spotless removes one, something else is wrong.

- [ ] **Step 4: Run, and expect both render tests to still fail**

```bash
cd backend && ./gradlew test --tests '*QuotationBuyerSnapshotTest*'
```

Expected: **both `sendFreezesTheBuyerAndDraftHasNone` and
`sentQuotationRendersIdenticallyAfterTheCustomerIsEdited` still FAIL.**

That is correct and not a mistake. `send()` now *writes* the snapshot, but
`QuotationPdfService` still *reads* the live customer — nothing observable changes until Task 6
switches the reader. The write path is complete and correct on its own, so commit it.

**Confirm the snapshot is really being written** before moving on, since no test can see it yet:

```bash
cd backend && ./gradlew test --tests '*QuotationSendTest*' --tests '*QuotationReviseTest*'
```

Expected: **PASS** — the new `findCustomer` lookup runs on every send and must not have broken the
existing send and revise flows.

- [ ] **Step 5: Commit**

```bash
cd backend && ./gradlew spotlessApply
git add src/main/java/com/easycrm/sales/QuotationService.java \
        src/test/java/com/easycrm/sales/web/QuotationBuyerSnapshotTest.java
git commit -m "feat: freeze the buyer when a quotation is sent

Freezes at send() rather than at version creation, so a draft that sits
while someone corrects a typo'd GSTIN sends the corrected one. The
render path still reads the customer live; Task 6 switches it."
```

---

### Task 5: The state-change guard

**Files:**
- Modify: `src/main/java/com/easycrm/sales/QuotationService.java` (the `send` method)
- Modify: `src/test/java/com/easycrm/sales/web/QuotationBuyerSnapshotTest.java`

**Interfaces:**
- Consumes: the `Customer customer` local introduced in Task 4.
- Produces: a 422 on `POST /api/v1/quotations/{id}/send` when the customer's state moved.

**Why (spec §4.1):** `placeOfSupply` freezes at *creation* because the per-line CGST/SGST/IGST was
computed against it then. Freezing a new address over an old tax split would print a Maharashtra
address beside a Karnataka intra-state breakup. The escape is a **new quotation**, not a revision —
`revise()` copies both the stale `placeOfSupply` and the frozen items forward, so the guard
correctly fires again there.

- [ ] **Step 1: Write the failing test**

Append to `QuotationBuyerSnapshotTest`:

```java
    @Test
    void sendRejectsAQuotationWhoseCustomerChangedState() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String cId = createCustomer(auth, "27");
        String qId = draftFor(auth, cId);

        // The customer moves to Karnataka. The draft's per-line tax was computed as
        // intra-state Maharashtra and is now wrong — sending it would print a Karnataka
        // address beside a CGST/SGST breakup that no longer applies.
        updateCustomer(auth, cId, "Bharat Industries", "29DDDDD3333D1Z5", "5 Residency Road, Bengaluru", "29");

        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("placeOfSupply"));

        // Nothing was frozen and nothing was sent: the quotation is still a usable DRAFT.
        mvc.perform(get("/api/v1/quotations/" + qId).header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }
```

**Confirm the error-body shape first.** `$.errors[0].field` is this codebase's `ValidationException`
contract — check one existing 422 assertion (`QuotationTransitionTest` or `QuotationEditTest`) and
match it exactly rather than trusting this line. Add the `jsonPath` static import if the file does
not have it yet.

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd backend && ./gradlew test --tests '*QuotationBuyerSnapshotTest.sendRejectsAQuotationWhoseCustomerChangedState*'
```

Expected: **FAIL** — the send returns 200, because there is no guard.

- [ ] **Step 3: Add the guard**

In `QuotationService.send()`, place this **between** the `findCustomer` lookup and the
`freezeBuyer` call added in Task 4:

```java
        // placeOfSupply froze at creation because the per-line CGST/SGST/IGST was computed
        // against it then. Buyer identity has no such coupling — which is what makes
        // freezing it late safe — but a customer who MOVED STATE breaks the coupling that
        // does exist, and a new address on an old tax split is a contradictory GST
        // document. revise() copies both the stale place of supply and the frozen items
        // forward, so it cannot fix this; only a new quotation recomputes the split.
        if (!customer.getStateCode().equals(v.getPlaceOfSupply())) {
            throw new ValidationException("placeOfSupply", "the customer's state has changed; raise a new quotation");
        }
```

`ValidationException` is already imported in this file.

- [ ] **Step 4: Run and confirm it passes**

```bash
cd backend && ./gradlew test --tests '*QuotationBuyerSnapshotTest*'
```

Expected: `sendRejectsAQuotationWhoseCustomerChangedState` **PASSES**. The two render-dependent
tests still fail until Task 6.

- [ ] **Step 5: Commit**

```bash
cd backend && ./gradlew spotlessApply
git add src/main/java/com/easycrm/sales/QuotationService.java \
        src/test/java/com/easycrm/sales/web/QuotationBuyerSnapshotTest.java
git commit -m "feat: reject send when the customer's state no longer matches

placeOfSupply freezes at creation because the per-line tax split was
computed against it. Freezing a new address over an old split would
print an address from one state beside another state's tax breakup, so
send() now refuses and points at raising a new quotation — revise()
copies the stale split forward and cannot fix it."
```

---

### Task 6: Render from the snapshot

The task that actually closes F11, and the one that deletes `crm` from the render path.

**Files:**
- Modify: `src/main/java/com/easycrm/sales/pdf/QuotationPdfService.java`
- Modify: `src/test/java/com/easycrm/sales/web/QuotationBuyerSnapshotTest.java`

**Interfaces:**
- Consumes: `QuotationVersion.getBuyer()` → `BuyerSnapshot` (Task 3), populated by Task 4.

- [ ] **Step 1: Add the public-share test**

The authenticated determinism test already exists (Task 1). Add its public-route twin, which is the
path that matters most — a link already sitting in someone's WhatsApp history:

```java
    @Test
    void publicShareLinkRendersIdenticallyAfterTheCustomerIsEdited() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String cId = createCustomer(auth, "27");
        String qId = draftFor(auth, cId);
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth))
                .andExpect(status().isOk());

        String publicUrl = JsonPath.read(
                mvc.perform(post("/api/v1/quotations/" + qId + "/share").header("Authorization", auth))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.publicUrl");
        String token = publicUrl.substring(publicUrl.lastIndexOf('/') + 1);

        byte[] before = mvc.perform(get("/public/q/" + token)) // deliberately no auth header
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        updateCustomer(auth, cId, "Bharat Industries LLP", "27EEEEE4444E1Z5", "77 JM Road, Pune", "27");

        byte[] after = mvc.perform(get("/public/q/" + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertArrayEquals(before, after, "a link already in the buyer's hands must not change");
    }
```

Confirm the share response field is `publicUrl` by checking `QuotationShareTest` or
`ShareResponse`; match whatever it actually is.

- [ ] **Step 2: Run and confirm all three render tests fail**

```bash
cd backend && ./gradlew test --tests '*QuotationBuyerSnapshotTest*'
```

Expected: `sentQuotationRendersIdenticallyAfterTheCustomerIsEdited`,
`sendFreezesTheBuyerAndDraftHasNone` and `publicShareLinkRendersIdenticallyAfterTheCustomerIsEdited`
all **FAIL**; `sendRejectsAQuotationWhoseCustomerChangedState` passes.

- [ ] **Step 3: Read the snapshot instead of the customer**

In `QuotationPdfService.render()`, delete these three lines:

```java
        Customer customer =
                finder.findCustomer(q.getCustomerId()).orElseThrow(() -> new NotFoundException("customer not found"));
```

and replace the `Buyer` construction:

```java
                new QuotationPdfData.Buyer(
                        customer.getBusinessName(), customer.getGstin(), customer.getBillingAddress()),
```

with:

```java
                new QuotationPdfData.Buyer(
                        buyer.getBusinessName(), buyer.getGstin(), buyer.getBillingAddress()),
```

Add this immediately after the existing `VersionStatus.SENT` check at the top of `render`:

```java
        // Frozen at send(), never read live: this is what stops an edited customer from
        // changing a document that was already issued (F11). Reading `crm` here is also
        // what would become a synchronous cross-service call to master-data on the most
        // exposed and most cached route in the system once document-svc is extracted (D10).
        BuyerSnapshot buyer = v.getBuyer();
        if (buyer == null) {
            // Unreachable: V34 backfilled every SENT row and send() freezes every new one.
            // Loud rather than a PDF with a blank buyer block, if that ever stops holding.
            throw new IllegalStateException("SENT version " + v.getId() + " has no frozen buyer");
        }
```

Add `import com.easycrm.sales.BuyerSnapshot;` and **delete `import com.easycrm.crm.Customer;`** —
that deletion is the architectural point of the whole slice. Leave the `VisibleFinder finder` field
alone: `render` still uses `finder.findQuotation(...)`, which is what enforces record-level
visibility on this route.

- [ ] **Step 3b: Add the revision re-freeze test**

This is the mechanism by which a corrected GSTIN ever reaches a customer, and it is what makes
freezing at `send()` (rather than at creation) worth the extra column-null state. Append to
`QuotationBuyerSnapshotTest`:

```java
    @Test
    void aRevisionFreezesItsOwnBuyerAndTheOldVersionKeepsTheOld() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String cId = createCustomer(auth, "27");
        String qId = draftFor(auth, cId);
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth))
                .andExpect(status().isOk());

        // The GSTIN was wrong on the original. Correcting it must reach the buyer somehow,
        // and a revision is that route — the state code stays put, so the Task 5 guard
        // does not fire.
        updateCustomer(auth, cId, "Bharat Industries", "27FFFFF5555F1Z5", "12 MG Road, Pune", "27");

        mvc.perform(post("/api/v1/quotations/" + qId + "/revise").header("Authorization", auth))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth))
                .andExpect(status().isOk());

        String v1 = textOf(mvc.perform(get("/api/v1/quotations/" + qId + "/pdf?version=1")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray());
        String v2 = textOf(pdfOf(auth, qId));

        org.junit.jupiter.api.Assertions.assertTrue(
                v1.contains("27AAAAA0000A1Z5"), "version 1 must keep the GSTIN it was sent with");
        org.junit.jupiter.api.Assertions.assertTrue(
                v2.contains("27FFFFF5555F1Z5"), "version 2 must carry the corrected GSTIN");
    }
```

Confirm the revise route and the `?version=` parameter against `QuotationReviseTest` and
`QuotationPdfEndpointTest` — both already exercise them — and match their exact forms.

- [ ] **Step 4: Run the full class, then the whole suite**

```bash
cd backend && ./gradlew test --tests '*QuotationBuyerSnapshotTest*'
cd backend && ./gradlew clean check
```

Expected: all five new tests **PASS**, and `clean check` is **green with 591 tests** (586 + 5),
0 failures, 0 errors, across both projects.

**If `QuotationPdfEndpointTest` or `PublicShareTest` fail,** they are asserting on buyer text that
now comes from the snapshot — read the failure before changing anything. A fixture that sends a
quotation and then edits the customer was silently depending on the bug.

- [ ] **Step 5: Commit**

```bash
cd backend && ./gradlew spotlessApply
git add src/main/java/com/easycrm/sales/pdf/QuotationPdfService.java \
        src/test/java/com/easycrm/sales/web/QuotationBuyerSnapshotTest.java
git commit -m "fix: render the quotation PDF from the frozen buyer (F11)

Closes the live correctness bug: a SENT version now renders identically
however the customer changes afterwards, through both the authenticated
route and the public share link.

Also deletes the crm.Customer import from the render path, which is
D10's architectural half — under the split that read becomes a
synchronous call to master-data on the most exposed, most cached route
in the system."
```

---

### Task 7: The documentation the working agreements owe

Not optional and not "later" — `CLAUDE.md` requires the challenge log to be written as part of the
same change.

**Files:**
- Modify: `docs/superpowers/engineering-challenges.md`
- Modify: `docs/ROADMAP.md`
- Modify: `docs/architecture/2026-08-24-service-scope-and-shared-modules.md`
- Modify: `docs/superpowers/HANDOFF.md`
- Modify: `docs/architecture/2026-08-20-aws-redesign-handoff.md`,
  `docs/architecture/2026-08-26-platform-modules-handoff.md`,
  `docs/architecture/2026-08-27-platform-llds-handoff.md`

- [ ] **Step 1: Write Challenge 68**

Append to `docs/superpowers/engineering-challenges.md`, matching the existing
`## Challenge N — <title>` / `### The problem` / `### The solution` / `### Lesson` structure. The
last entry is Challenge 67.

Title: **`## Challenge 68 — A backfill migration under FORCEd RLS silently updates nothing, and the obvious check passes vacuously`**

Cover, in the file's own voice:

- **The problem.** Flyway connects as `easycrm_owner`; `V26` FORCEd RLS specifically so the owner is
  bound. Every policy is `tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid`
  and `missing_ok = true` makes it `NULL` — never true — in a session with no GUC. A cross-tenant
  `UPDATE` matches zero rows, commits, and reports success.
- **Why it is hard.** Two layers of silence. The failure has no error, no warning and no log line —
  and the natural post-check (`count(*)` of unfrozen rows after the loop) *also* runs with no GUC,
  sees zero rows, and passes. The guard fails the same way as the thing it guards. Add that
  Testcontainers starts empty, so no test in the repo can catch it either.
- **The solution.** Loop over `tenant` (no `tenant_id`, no RLS, readable), `set_config` per tenant,
  update inside the policy, and put the `RAISE EXCEPTION` check **inside** the loop. Record why
  `NO FORCE`-and-re-`FORCE` was rejected (a mid-migration failure leaves a silent isolation hole)
  and why `BYPASSRLS` was rejected (permanently weakens layer 3 for one migration).
- **Lesson.** Under FORCEd RLS, a migration is not a privileged context — it is just another
  session with no tenant. Any future DML migration follows this shape. And a verification query
  written under the same broken assumption as the thing it verifies is not verification.

- [ ] **Step 2: Update the roadmap**

In `docs/ROADMAP.md`:
- §1.5 — strike the **H1** row, or mark it fixed and dated 2026-09-07 with the commit.
- Part 3 Phase 0 item 1 and Part 6 item 1 — mark done; **remove the "also folds in S2's
  primary-contact freeze" clause** and point at the design spec §7.
- Part 5 — move "Buyer snapshot (H1)" from the Application row's *Left* column to *Have*.
- Part 6 — renumber nothing; just mark item 1 complete so the next item reads as next.

- [ ] **Step 3: Record the declined S2 fold**

In `docs/architecture/2026-08-24-service-scope-and-shared-modules.md`, Appendix A item 2 ("S2 folds
into sub-project 1"): amend it in place. S2 is **not** folded in, because `ALTER TABLE ... ADD
COLUMN` with no default is O(1) here so the stated migration cost is near zero, while freezing the
contact makes a wrong phone number uncorrectable without burning a version number — and the payoff
lands only at SP8, which is conditional on a §4.5 trigger. Link the design spec §7. Leave the S2
*finding* standing; only its scheduling changed.

- [ ] **Step 4: Close F11 everywhere it is open**

F11 is listed as open in three architecture handoffs
(`2026-08-20-aws-redesign-handoff.md` §"a live bug" and its table row,
`2026-08-26-platform-modules-handoff.md` line ~135,
`2026-08-27-platform-llds-handoff.md` line ~186). Mark each closed with the date and the commit.
Leave S2's own row in `2026-08-26-platform-modules-handoff.md` open — Step 3 rescheduled it, it is
not done.

- [ ] **Step 5: Update the handoff**

In `docs/superpowers/HANDOFF.md`: rewrite §0 for this slice (what landed, the new test count, the
new `main` tip), add a §3 inventory entry, and in §0's numbered findings mark finding 1 (the buyer
snapshot) resolved. Note Challenge 68 as the challenge this slice owed and paid.

- [ ] **Step 6: Final verification, then commit**

```bash
cd backend && ./gradlew clean check
```

Expected: **green, 591 tests, 0 failures, 0 errors** (563 root, 28 primitives). State the real
number from the output — if it is not 591, say what it is and why rather than editing the plan's
claim.

```bash
git add ../docs
git commit -m "docs: record the buyer snapshot slice

Challenge 68 on the RLS backfill trap, the two annotation rows, H1
struck from the roadmap, F11 closed in the three architecture handoffs,
and Appendix A amended to say S2 is rescheduled rather than folded in."
```

---

## Verification

The slice is done when all of these hold:

- `./gradlew clean check` is green at **591 tests**, 0 failures, 0 errors.
- `grep -rn "com.easycrm.crm" src/main/java/com/easycrm/sales/pdf/` returns **nothing**.
- `docs/api/openapi.yaml` is **unchanged** in `git diff main` — B7 means the contract does not move.
- The seven commits above are on the branch, authored `divyam`, with no Claude/AI mention.
- Challenge 68 exists; `@Embeddable` and `@Embedded` have reference rows.

Then use **superpowers:finishing-a-development-branch** to integrate.
