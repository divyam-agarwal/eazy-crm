package com.easycrm.sales.web;

import com.easycrm.iam.Role;
import com.easycrm.iam.User;
import com.easycrm.iam.UserRepository;
import com.easycrm.iam.UserStatus;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.Enquiry;
import com.easycrm.sales.EnquiryRepository;
import com.easycrm.sales.EnquirySource;
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

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec §6.1 — the product's actual moment: a trader ends a call and needs "logged it,
 * ringing them Tuesday" to be one tap on patchy 4G, not two round-trips of which the
 * second can fail.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LogAndScheduleEndpointTest extends IntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired EnquiryRepository enquiries;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private UUID ownerId;
    private String ownerToken;
    private UUID enquiryId;

    @BeforeEach
    void seed() {
        // A real ACTIVE User row, not a bare random id: nextFollowUp.assignedTo now
        // validates through AssignableUsers (task 8) -- a bare random UUID would 422.
        ownerId = seedUser(tenantId);
        ownerToken = tokens.as(tenantId, ownerId, "OWNER");
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, ownerId, "OWNER"));
        tx.executeWithoutResult(s -> enquiryId = enquiries.saveAndFlush(new Enquiry(
            null, "Ramesh", "9876566001", "9876566001", null,
            EnquirySource.MANUAL, "needs bags", ownerId, null)).getId());
        TenantContext.clear();
    }

    /** Seeds a real User row in the given tenant so assignedTo can resolve against it. */
    private UUID seedUser(UUID tenantId) {
        return TenantContext.runAs(
            new TenantContext.TenantPrincipal(tenantId, UUID.randomUUID(), "OWNER"),
            () -> tx.execute(s -> users.save(new User(
                "owner-" + UUID.randomUUID() + "@example.com", null, "hash",
                Role.OWNER, UserStatus.ACTIVE)).getId()));
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void oneRequestWritesBothTheActivityAndTheFollowUp() throws Exception {
        mvc.perform(post("/api/v1/activities").header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","type":"CALL",
                     "body":"rang them","outcome":"wants a revised rate",
                     "nextFollowUp":{"dueAt":"%s","assignedTo":"%s","note":"ring Tuesday"}}
                    """.formatted(enquiryId, Instant.now().plusSeconds(172_800), ownerId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.followUpId").exists());

        mvc.perform(get("/api/v1/activities")
                .param("subjectType", "ENQUIRY").param("subjectId", enquiryId.toString())
                .header(AUTH, "Bearer " + ownerToken))
            .andExpect(jsonPath("$.content.length()").value(1));

        mvc.perform(get("/api/v1/follow-ups")
                .param("subjectType", "ENQUIRY").param("subjectId", enquiryId.toString())
                .header(AUTH, "Bearer " + ownerToken))
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].note").value("ring Tuesday"));
    }

    @Test
    void omittingNextFollowUpWritesOnlyTheActivity() throws Exception {
        mvc.perform(post("/api/v1/activities").header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","type":"CALL","body":"rang"}
                    """.formatted(enquiryId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.followUpId").doesNotExist());

        mvc.perform(get("/api/v1/follow-ups")
                .param("subjectType", "ENQUIRY").param("subjectId", enquiryId.toString())
                .header(AUTH, "Bearer " + ownerToken))
            .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void aBadAssigneeRollsBackTheActivityToo() throws Exception {
        mvc.perform(post("/api/v1/activities").header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","type":"CALL","body":"rang",
                     "nextFollowUp":{"dueAt":"%s","assignedTo":"%s","note":"x"}}
                    """.formatted(enquiryId, Instant.now().plusSeconds(3600), UUID.randomUUID())))
            .andExpect(status().isUnprocessableEntity());

        // Atomicity: the activity must NOT have been written. This is the whole point of
        // doing both in one transaction (spec §6.1).
        mvc.perform(get("/api/v1/activities")
                .param("subjectType", "ENQUIRY").param("subjectId", enquiryId.toString())
                .header(AUTH, "Bearer " + ownerToken))
            .andExpect(jsonPath("$.content.length()").value(0));
    }
}
