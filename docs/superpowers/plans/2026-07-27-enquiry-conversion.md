# Enquiry → Quotation Conversion Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a quotation is raised from a lead (`enquiryId` present on create), flip that enquiry to `CONVERTED` and stamp it onto the quotation, atomically.

**Architecture:** One behavioural change in `QuotationService.create()`: inject `EnquiryRepository`, and when `req.enquiryId() != null`, load the enquiry (404 if not visible) and call `enquiry.markConverted()` (422 if terminal). The existing `new Quotation(customerId, enquiryId)` line already stamps `quotation.enquiry_id`; the whole thing already runs inside `create()`'s `@Transactional`, so conversion + quote-build are atomic. No new endpoint, DTO, migration, or annotation.

**Tech Stack:** Spring Boot 4.1, Java 25, Hibernate 7, PostgreSQL (RLS), JUnit 5 + Testcontainers + MockMvc + jayway JsonPath.

## Global Constraints

- **Tenant isolation is structural** — load the enquiry via `EnquiryRepository.findById` on the tenant-scoped session (`@TenantId` + RLS). Never hand-write `WHERE tenant_id`.
- **Cross-tenant / missing reads → 404** (`NotFoundException`). Cross-tenant list → empty.
- **Illegal state transition → 422** via `ValidationException` (NOT `IllegalStateException`). `ConflictException` → 409, `NotFoundException` → 404.
- **Money is never a `double`** — BigDecimal / NUMERIC / JSON string (unchanged here).
- **Commits:** author as `divyam <divyam.0444@gmail.com>`, plain `git commit`. Never mention Claude/AI, never add a `Co-Authored-By` trailer.
- **TDD:** failing test → run-to-confirm-fail → minimal code → run-to-pass → commit. One task per commit.
- **Tests read `Tenant.state_code`** (quotation GST split) → use `TestTokens.provisionOwner("27")`, not a phantom owner.
- **Build/test:** `cd backend && ./gradlew test` (Docker must be running for Testcontainers).

---

## File Structure

- **Modify:** `backend/src/main/java/com/easycrm/sales/QuotationService.java` — inject `EnquiryRepository`; add the conversion block to `create()`. (Only production change in this slice.)
- **Create:** `backend/src/test/java/com/easycrm/sales/web/QuotationConversionTest.java` — all conversion integration tests (`@SpringBootTest` + `@AutoConfigureMockMvc`, extends `IntegrationTest`).

No changes to entities, DTOs, migrations, or the annotations reference (none are new).

---

### Task 1: Wire conversion into quotation-create (load + validate + flip)

**Files:**
- Modify: `backend/src/main/java/com/easycrm/sales/QuotationService.java`
- Test: `backend/src/test/java/com/easycrm/sales/web/QuotationConversionTest.java`

**Interfaces:**
- Consumes: `EnquiryRepository.findById(UUID) : Optional<Enquiry>` (inherited from `JpaRepository`); `Enquiry.markConverted() : void` (throws `ValidationException` from a terminal stage); `QuotationCreateRequest.enquiryId() : UUID` (nullable); existing `NotFoundException`.
- Produces: no new public signature — `QuotationService.create(QuotationCreateRequest)` keeps its shape; behaviour gains the conversion side-effect.

Reference existing test helpers to mirror: `QuotationControllerTest.seed(auth, state)` (creates a customer + product, returns `{customerId, productId}` via the real APIs) and `EnquiryCreateTest` (enquiry create body shape). Copy the `seed` helper into the new test class.

- [ ] **Step 1: Write the failing happy-path test**

Create `backend/src/test/java/com/easycrm/sales/web/QuotationConversionTest.java`:

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
class QuotationConversionTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    // Mirrors QuotationControllerTest.seed: creates a customer + product via the real APIs.
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

    // Creates an active enquiry, returns its id.
    private String seedEnquiry(String auth, String phone) throws Exception {
        String body = """
            {"contactName":"Ravi","contactPhone":"%s","source":"INDIAMART"}""".formatted(phone);
        String eBody = mvc.perform(post("/api/v1/enquiries").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(eBody, "$.id");
    }

    @Test
    void raisingQuoteFromEnquiryConvertsItAndStampsTheQuotation() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seed(auth, "27");
        String enquiryId = seedEnquiry(auth, "9876543210");

        String body = """
            {"customerId":"%s","enquiryId":"%s","items":[{"productId":"%s","qty":"2"}]}"""
            .formatted(ids[0], enquiryId, ids[1]);

        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.enquiryId").value(enquiryId));

        // The lead is now CONVERTED (terminal).
        mvc.perform(get("/api/v1/enquiries/" + enquiryId).header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stage").value("CONVERTED"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.easycrm.sales.web.QuotationConversionTest.raisingQuoteFromEnquiryConvertsItAndStampsTheQuotation"`
Expected: FAIL — the quotation is created (201) and `enquiryId` is stamped, but the enquiry GET returns `"stage":"NEW"`, so the `CONVERTED` assertion fails (nothing flips the enquiry yet).

- [ ] **Step 3: Inject `EnquiryRepository` into `QuotationService`**

In `backend/src/main/java/com/easycrm/sales/QuotationService.java`, add the field and constructor param. Change the field block (after `private final OrderRepository orders;`) and constructor to include `enquiries`:

```java
    private final OrderRepository orders;
    private final EnquiryRepository enquiries;
    private final ApplicationEventPublisher events;

    public QuotationService(QuotationRepository quotations, QuotationVersionRepository versions,
                            QuotationItemRepository items, CustomerRepository customers,
                            TenantRepository tenants, PriceResolver priceResolver,
                            DocumentNumberService documentNumbers, OrderRepository orders,
                            EnquiryRepository enquiries, ApplicationEventPublisher events) {
        this.quotations = quotations;
        this.versions = versions;
        this.items = items;
        this.customers = customers;
        this.tenants = tenants;
        this.priceResolver = priceResolver;
        this.documentNumbers = documentNumbers;
        this.orders = orders;
        this.enquiries = enquiries;
        this.events = events;
    }
```

(`Enquiry` and `EnquiryRepository` are in the same package `com.easycrm.sales`, so no imports needed.)

- [ ] **Step 4: Add the conversion block to `create()`**

In the same file, insert the conversion block into `create()`, right after the customer load / `interState` line and before `Quotation quotation = quotations.save(...)`:

```java
    @Transactional
    public QuotationResponse create(QuotationCreateRequest req) {
        Customer customer = customers.findById(req.customerId())
            .orElseThrow(() -> new NotFoundException("customer not found"));
        boolean interState = isInterState(customer.getStateCode());

        if (req.enquiryId() != null) {
            Enquiry enquiry = enquiries.findById(req.enquiryId())
                .orElseThrow(() -> new NotFoundException("enquiry not found"));
            enquiry.markConverted(); // 422 if the enquiry is already terminal
        }

        Quotation quotation = quotations.save(new Quotation(req.customerId(), req.enquiryId()));
        QuotationVersion version = versions.save(
            new QuotationVersion(quotation.getId(), 1, customer.getStateCode()));
        version.setHeader(req.validUntil(), req.paymentTerms(), req.deliveryTerms(), req.notes());
        buildItems(version, req.customerId(), req.items(), interState);
        quotation.setCurrentVersionId(version.getId());
        return toResponse(quotation);
    }
```

- [ ] **Step 5: Run the happy-path test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.easycrm.sales.web.QuotationConversionTest.raisingQuoteFromEnquiryConvertsItAndStampsTheQuotation"`
Expected: PASS.

- [ ] **Step 6: Add the validation tests (404 + terminal-guard)**

Append to `QuotationConversionTest`:

```java
    @Test
    void unknownEnquiryIdReturns404() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seed(auth, "27");
        String body = """
            {"customerId":"%s","enquiryId":"%s","items":[{"productId":"%s","qty":"1"}]}"""
            .formatted(ids[0], UUID.randomUUID(), ids[1]);

        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNotFound());
    }

    @Test
    void crossTenantEnquiryIdReturns404AndLeavesItUntouched() throws Exception {
        String authA = "Bearer " + tokens.provisionOwner("27").token();
        String enquiryId = seedEnquiry(authA, "9876543210");

        String authB = "Bearer " + tokens.provisionOwner("27").token();
        String[] idsB = seed(authB, "27");
        String body = """
            {"customerId":"%s","enquiryId":"%s","items":[{"productId":"%s","qty":"1"}]}"""
            .formatted(idsB[0], enquiryId, idsB[1]);

        // Tenant B cannot see tenant A's enquiry -> 404.
        mvc.perform(post("/api/v1/quotations").header("Authorization", authB)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNotFound());

        // Tenant A's enquiry is untouched.
        mvc.perform(get("/api/v1/enquiries/" + enquiryId).header("Authorization", authA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stage").value("NEW"));
    }

    @Test
    void quotingAnAlreadyTerminalEnquiryReturns422() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seed(auth, "27");
        String enquiryId = seedEnquiry(auth, "9876543210");

        // Take the enquiry terminal via /lose.
        mvc.perform(post("/api/v1/enquiries/" + enquiryId + "/lose").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"lostReason\":\"bought elsewhere\"}"))
            .andExpect(status().isOk());

        String body = """
            {"customerId":"%s","enquiryId":"%s","items":[{"productId":"%s","qty":"1"}]}"""
            .formatted(ids[0], enquiryId, ids[1]);
        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnprocessableEntity());
    }
```

- [ ] **Step 7: Run the full test class to verify all pass**

Run: `cd backend && ./gradlew test --tests "com.easycrm.sales.web.QuotationConversionTest"`
Expected: PASS (4 tests). The 404 and 422 tests already pass against the Step 4 implementation — `findById().orElseThrow` gives 404; `markConverted()`'s terminal guard gives 422.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/easycrm/sales/QuotationService.java \
        backend/src/test/java/com/easycrm/sales/web/QuotationConversionTest.java
git commit -m "feat(sales): convert enquiry to CONVERTED when a quote is raised from it"
```

---

### Task 2: Lock in the emergent guarantees (one-quote-per-enquiry, re-enquiry, atomicity, regression)

**Files:**
- Test: `backend/src/test/java/com/easycrm/sales/web/QuotationConversionTest.java` (append)

**Interfaces:**
- Consumes: everything from Task 1. No production change — these tests characterise guarantees that fall out of the terminal guard, the partial unique index, and the transaction boundary. If any fails, the fix belongs in Task 1's implementation, not here.

- [ ] **Step 1: Write the emergent-guarantee tests**

Append to `QuotationConversionTest`:

```java
    @Test
    void aSecondQuoteFromTheSameEnquiryReturns422() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seed(auth, "27");
        String enquiryId = seedEnquiry(auth, "9876543210");
        String body = """
            {"customerId":"%s","enquiryId":"%s","items":[{"productId":"%s","qty":"1"}]}"""
            .formatted(ids[0], enquiryId, ids[1]);

        // First quote converts the enquiry.
        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());

        // Second quote from the now-CONVERTED (terminal) enquiry -> 422.
        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void reEnquiringOnTheSamePhoneAfterConversionSucceeds() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seed(auth, "27");
        String enquiryId = seedEnquiry(auth, "9876543210");
        String body = """
            {"customerId":"%s","enquiryId":"%s","items":[{"productId":"%s","qty":"1"}]}"""
            .formatted(ids[0], enquiryId, ids[1]);

        // Convert the lead.
        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());

        // While the first was active a duplicate would 409; now it is CONVERTED it leaves the
        // partial unique index, so a fresh enquiry on the same phone is allowed.
        mvc.perform(post("/api/v1/enquiries").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"contactName":"Ravi","contactPhone":"9876543210","source":"PHONE"}"""))
            .andExpect(status().isCreated());
    }

    @Test
    void failedQuoteBuildRollsBackTheConversion() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seed(auth, "27");
        String enquiryId = seedEnquiry(auth, "9876543210");

        // qty 0 passes bean validation (@NotNull only) but fails in buildItems -> 422,
        // AFTER markConverted() flipped the managed enquiry in the same transaction.
        String body = """
            {"customerId":"%s","enquiryId":"%s","items":[{"productId":"%s","qty":"0"}]}"""
            .formatted(ids[0], enquiryId, ids[1]);
        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnprocessableEntity());

        // The transaction rolled back, so the enquiry is still active (NEW), not CONVERTED.
        mvc.perform(get("/api/v1/enquiries/" + enquiryId).header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stage").value("NEW"));
    }

    @Test
    void creatingWithoutAnEnquiryIdStillWorks() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String[] ids = seed(auth, "27");
        String body = """
            {"customerId":"%s","items":[{"productId":"%s","qty":"1"}]}"""
            .formatted(ids[0], ids[1]);

        mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.enquiryId").doesNotExist());
    }
```

- [ ] **Step 2: Run the full test class to verify all pass**

Run: `cd backend && ./gradlew test --tests "com.easycrm.sales.web.QuotationConversionTest"`
Expected: PASS (8 tests total). All pass against Task 1's implementation — no production change. If `failedQuoteBuildRollsBackTheConversion` fails (enquiry shows CONVERTED), the conversion block is outside the transaction boundary — revisit Task 1 Step 4.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/easycrm/sales/web/QuotationConversionTest.java
git commit -m "test(sales): lock in enquiry-conversion guarantees (dedupe, atomicity, regression)"
```

---

### Task 3: Full-suite verification + documentation wrap-up

**Files:**
- Modify (maybe): `docs/superpowers/engineering-challenges.md`
- Modify: `docs/superpowers/HANDOFF.md`

- [ ] **Step 1: Run the whole suite from clean**

Run: `cd backend && ./gradlew clean test`
Expected: PASS — 154 prior tests + 8 new = **162 tests**, all green. (Confirm the exact count from the run output; the new class adds 8.)

- [ ] **Step 2: Engineering-challenges evaluation (per CLAUDE.md)**

Decide whether the create-time-flip design clears the CLAUDE.md bar (non-obvious; you'd explain it in an interview). It plausibly does — the interesting content is the **transaction-boundary coupling** (conversion + quote-build share one tx, so a failed build un-converts the lead — `failedQuoteBuildRollsBackTheConversion` proves it) and the **invariant tie-in** (conversion is the second path, besides `LOST`, that frees the dedupe phone). If you judge it worth logging, append **one** entry using the template at the bottom of `docs/superpowers/engineering-challenges.md` (Problem → why hard → Solution → Lesson), numbered as the next entry (#25). Do not pad — if it reads as routine wiring, skip it and note in the commit that you evaluated and skipped. No new annotation is introduced, so `annotations-reference.md` needs no change.

- [ ] **Step 3: Update the handoff**

In `docs/superpowers/HANDOFF.md`: move "Enquiry → quotation conversion wiring" out of the §4 deferred list, note it merged; update the §3 test count (154 → 162) and the "current state" line; reference the new spec/plan in the §2 read-order list. Keep edits factual and consistent with the rest of the file.

- [ ] **Step 4: Commit the docs**

```bash
git add docs/superpowers/
git commit -m "docs(enquiry): log conversion wiring + update handoff"
```

---

## Self-Review

**Spec coverage:**
- §5 behaviour (load + `markConverted` + stamp, `enquiryId == null` unchanged) → Task 1 Steps 3–4; regression test Task 2 Step 1 (`creatingWithoutAnEnquiryIdStillWorks`).
- §5 atomic conversion + quote build → Task 2 (`failedQuoteBuildRollsBackTheConversion`).
- §5 one enquiry → one quotation → Task 2 (`aSecondQuoteFromTheSameEnquiryReturns422`).
- §5 frees the phone → Task 2 (`reEnquiringOnTheSamePhoneAfterConversionSucceeds`).
- §5 no customer match forced → covered structurally (code never reads `enquiry.customerId`); no dedicated test needed.
- §7 test list (happy, 404 unknown + cross-tenant, terminal 422, one-quote, re-enquiry, atomicity, null regression) → Task 1 + Task 2, one test each.
- §8 docs obligations → Task 3.
- Concurrent double-convert (§5) — guarded by the enquiry `@Version`; not integration-tested (racing two tx deterministically is out of proportion for this slice), noted here as a deliberate omission.

**Placeholder scan:** none — every step has exact code, paths, commands, and expected output.

**Type consistency:** `EnquiryRepository.findById`, `Enquiry.markConverted()`, `QuotationCreateRequest.enquiryId()`, `NotFoundException`, `TestTokens.provisionOwner(String)` all match the current codebase (verified against source). Constructor param list matches the existing `QuotationService` constructor with `enquiries` inserted before `events`.
