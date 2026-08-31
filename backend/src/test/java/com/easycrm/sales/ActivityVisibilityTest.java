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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The load-bearing tests: an activity hanging off an enquiry I cannot see must be
 * unreachable, on both the write and the read path. Spec §4.2, §10.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActivityVisibilityTest extends IntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired EnquiryRepository enquiries;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID execAId = UUID.randomUUID();
    private final UUID execBId = UUID.randomUUID();

    private String execAToken, ownerToken;
    private UUID mine, execBEnquiry, pool;

    @BeforeEach
    void seed() {
        execAToken = tokens.as(tenantId, execAId, "SALES_EXEC");
        ownerToken = tokens.as(tenantId, UUID.randomUUID(), "OWNER");
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, execAId, "OWNER"));
        tx.executeWithoutResult(s -> {
            mine = enquiries.saveAndFlush(newEnquiry("9876522001", execAId)).getId();
            execBEnquiry = enquiries.saveAndFlush(newEnquiry("9876522002", execBId)).getId();
            pool = enquiries.saveAndFlush(newEnquiry("9876522003", null)).getId();
        });
        TenantContext.clear();
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void execCanLogAgainstTheirOwnEnquiry() throws Exception {
        logAs(execAToken, mine).andExpect(status().isCreated());
    }

    @Test
    void execCanLogAgainstAnUnassignedEnquiry() throws Exception {
        logAs(execAToken, pool).andExpect(status().isCreated());
    }

    @Test
    void execCannotLogAgainstAnotherExecsEnquiry() throws Exception {
        logAs(execAToken, execBEnquiry).andExpect(status().isNotFound());
    }

    @Test
    void execCannotReadAnotherExecsEnquiryTimeline() throws Exception {
        logAs(ownerToken, execBEnquiry).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/activities")
                .param("subjectType", "ENQUIRY").param("subjectId", execBEnquiry.toString())
                .header(AUTH, "Bearer " + execAToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void ownerCanReadAnyTimelineInTheTenant() throws Exception {
        logAs(ownerToken, execBEnquiry).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/activities")
                .param("subjectType", "ENQUIRY").param("subjectId", execBEnquiry.toString())
                .header(AUTH, "Bearer " + ownerToken))
            .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions logAs(String token, UUID subject)
            throws Exception {
        return mvc.perform(post("/api/v1/activities").header(AUTH, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"subjectType":"ENQUIRY","subjectId":"%s","type":"CALL","body":"rang"}
                """.formatted(subject)));
    }

    private Enquiry newEnquiry(String phone, UUID assignedTo) {
        return new Enquiry(null, "Contact", phone, phone, null,
            EnquirySource.MANUAL, "need goods", assignedTo, null);
    }
}
