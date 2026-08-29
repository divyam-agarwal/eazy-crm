package com.easycrm.sales;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Endpoint-level: proves the HTTP contract 404s for an invisible enquiry, on both reads
 * and writes, and that OWNER/unassigned remain fully visible. Also proves the
 * one-active-enquiry-per-phone dedupe pre-check stays unfiltered by design. See spec
 * 2026-08-29-record-visibility-design.md §4, §5.2, §6.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EnquiryVisibilityTest extends IntegrationTest {

    private static final String AUTH = "Authorization";
    private static final String EXEC_B_PHONE = "9876500002";

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired EnquiryRepository enquiries;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID execAId = UUID.randomUUID();
    private final UUID execBId = UUID.randomUUID();

    private String execAToken;
    private String ownerToken;
    private UUID mine, execBEnquiry, pool;

    @BeforeEach
    void seed() {
        execAToken = tokens.as(tenantId, execAId, "SALES_EXEC");
        ownerToken = tokens.as(tenantId, UUID.randomUUID(), "OWNER");

        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, execAId, "OWNER"));
        mine = enquiries.saveAndFlush(newEnquiry("9876500001", execAId)).getId();
        execBEnquiry = enquiries.saveAndFlush(newEnquiry(EXEC_B_PHONE, execBId)).getId();
        pool = enquiries.saveAndFlush(newEnquiry("9876500003", null)).getId();
        TenantContext.clear();
    }

    @AfterEach void clear() { TenantContext.clear(); }

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

    /**
     * The dedupe pre-check MUST stay unfiltered (spec §6). If it only saw exec A's own
     * enquiries, exec A would successfully create a second active enquiry for a phone
     * exec B already holds -- breaking one-active-per-phone, the invariant the check
     * exists to protect. The 409 does disclose that SOMEONE holds the number. That
     * disclosure is the accepted trade; a broken invariant is not.
     *
     * <p>The discriminator is the error MESSAGE, not the row count. The partial unique
     * index ({@code uq_enquiry_tenant_active_phone}) forbids a second active row
     * unconditionally, so if the pre-check were filtered and let the insert through, the
     * index would reject it at commit and Postgres would roll back the whole transaction
     * -- the row count would still land on 1, identical to the correct behaviour. Only the
     * message distinguishes "the pre-check caught it"
     * ({@code EnquiryService.requireNoActiveDuplicateExcept}'s "an active enquiry already
     * exists...") from "the backstop caught it" ({@code ApiExceptionHandler}'s generic
     * "the request conflicts with existing data").
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
            // The discriminator: this message only comes from the app-level pre-check.
            // The unique-index backstop's message is the generic "the request conflicts
            // with existing data" (ApiExceptionHandler.dataIntegrity) and would NOT match.
            .andExpect(jsonPath("$.error.message")
                .value(containsString("active enquiry already exists for this phone")));

        // Secondary sanity check only -- NOT a discriminator. A constraint violation rolls
        // back the whole transaction, so this count would also read 1 if the pre-check
        // were broken and the unique index caught the duplicate instead.
        assertThat(countActiveEnquiriesFor(EXEC_B_PHONE)).isEqualTo(1);
    }

    // --- helpers -------------------------------------------------------------

    private Enquiry newEnquiry(String phone, UUID assignedTo) {
        return new Enquiry(null, "Test Contact", phone, phone, null,
            EnquirySource.MANUAL, null, assignedTo, null);
    }

    private long countActiveEnquiriesFor(String phone) {
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, execAId, "OWNER"));
        try {
            return enquiries.findByNormalizedPhone(phone).stream()
                .filter(e -> e.getStage().isActive())
                .count();
        } finally {
            TenantContext.clear();
        }
    }

    private String bearer(String token) { return "Bearer " + token; }

    private String validEnquiryJson() {
        return """
            {"contactName":"Theirs Updated","contactPhone":"9876509999","source":"MANUAL"}""";
    }

    private String enquiryJsonFor(String phone) {
        return """
            {"contactName":"Dup Test","contactPhone":"%s","source":"MANUAL"}""".formatted(phone);
    }
}
