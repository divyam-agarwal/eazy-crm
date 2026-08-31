package com.easycrm.sales;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A follow-up assigned to someone else must be invisible to a SALES_EXEC. Spec §4.1. */
@SpringBootTest
@AutoConfigureMockMvc
class FollowUpVisibilityTest extends IntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired FollowUpRepository followUps;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID execAId = UUID.randomUUID();
    private final UUID execBId = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();

    private String execAToken, ownerToken;
    private UUID mine, theirs;

    @BeforeEach
    void seed() {
        execAToken = tokens.as(tenantId, execAId, "SALES_EXEC");
        ownerToken = tokens.as(tenantId, UUID.randomUUID(), "OWNER");
        // +2 days, not +1hr: unambiguously past endOfTodayIST (UPCOMING) no matter what
        // time of day the suite runs, mirroring the offset used in FollowUpEndpointTest.
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, execAId, "OWNER"),
            () -> tx.executeWithoutResult(s -> {
                mine = followUps.saveAndFlush(new FollowUp(SubjectType.ENQUIRY, subject,
                    Instant.now().plusSeconds(172_800), execAId, "mine", execAId)).getId();
                theirs = followUps.saveAndFlush(new FollowUp(SubjectType.ENQUIRY, subject,
                    Instant.now().plusSeconds(172_800), execBId, "theirs", execBId)).getId();
            }));
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void execSeesTheirOwnFollowUp() throws Exception {
        mvc.perform(get("/api/v1/follow-ups/" + mine).header(AUTH, "Bearer " + execAToken))
            .andExpect(status().isOk());
    }

    @Test
    void execCannotSeeAnotherExecsFollowUp() throws Exception {
        mvc.perform(get("/api/v1/follow-ups/" + theirs).header(AUTH, "Bearer " + execAToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void execsListOmitsAnotherExecsFollowUp() throws Exception {
        mvc.perform(get("/api/v1/follow-ups").header(AUTH, "Bearer " + execAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].note").value("mine"));
    }

    @Test
    void ownerSeesBoth() throws Exception {
        mvc.perform(get("/api/v1/follow-ups").header(AUTH, "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void execsSummaryCountsOnlyTheirOwn() throws Exception {
        mvc.perform(get("/api/v1/follow-ups/summary").header(AUTH, "Bearer " + execAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.upcoming").value(1));
    }
}
