package com.easycrm.sales.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** HTTP contract for creating and reading follow-ups. Spec §9. */
@SpringBootTest
@AutoConfigureMockMvc
class FollowUpEndpointTest extends IntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    EnquiryRepository enquiries;

    @Autowired
    UserRepository users;

    @Autowired
    TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private UUID ownerId;
    private String ownerToken;
    private UUID enquiryId;

    @BeforeEach
    void seed() {
        // A real ACTIVE User row, not a bare random id: FollowUpService now validates
        // assignedTo through AssignableUsers (task 8) -- a bare random UUID would 422.
        ownerId = seedUser(tenantId);
        ownerToken = tokens.as(tenantId, ownerId, "OWNER");
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, ownerId, "OWNER"));
        tx.executeWithoutResult(s -> enquiryId = enquiries
                .saveAndFlush(new Enquiry(
                        null,
                        "Ramesh",
                        "9876544001",
                        "9876544001",
                        null,
                        EnquirySource.MANUAL,
                        "needs bags",
                        ownerId,
                        null))
                .getId());
        TenantContext.clear();
    }

    /** Seeds a real User row in the given tenant so assignedTo can resolve against it. */
    private UUID seedUser(UUID tenantId) {
        return TenantContext.runAs(
                new TenantContext.TenantPrincipal(tenantId, UUID.randomUUID(), "OWNER"),
                () -> tx.execute(s -> users.save(new User(
                                "owner-" + UUID.randomUUID() + "@example.com",
                                null,
                                "hash",
                                Role.OWNER,
                                UserStatus.ACTIVE))
                        .getId()));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void createsAPendingFollowUp() throws Exception {
        mvc.perform(create(Instant.now().plusSeconds(172_800), "ring back Tuesday"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.note").value("ring back Tuesday"))
                .andExpect(jsonPath("$.assignedTo").value(ownerId.toString()))
                .andExpect(jsonPath("$.overdue").value(false));
    }

    @Test
    void aPastDueDateIsAcceptedAndReportsAsOverdue() throws Exception {
        mvc.perform(create(Instant.now().minusSeconds(172_800), "should have rung"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.overdue").value(true));
    }

    @Test
    void anInvisibleSubjectIs404() throws Exception {
        mvc.perform(post("/api/v1/follow-ups")
                        .header(AUTH, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","dueAt":"%s",
                     "assignedTo":"%s","note":"x"}
                    """.formatted(UUID.randomUUID(), Instant.now().plusSeconds(3600), ownerId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownAssigneeIs422() throws Exception {
        mvc.perform(post("/api/v1/follow-ups")
                        .header(AUTH, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","dueAt":"%s",
                     "assignedTo":"%s","note":"x"}
                    """.formatted(enquiryId, Instant.now().plusSeconds(3600), UUID.randomUUID())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.assignedTo").exists());
    }

    @Test
    void listsOverdueSeparatelyFromUpcoming() throws Exception {
        mvc.perform(create(Instant.now().minusSeconds(172_800), "past")).andExpect(status().isCreated());
        mvc.perform(create(Instant.now().plusSeconds(172_800), "future")).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/follow-ups").param("scope", "OVERDUE").header(AUTH, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].note").value("past"))
                .andExpect(jsonPath("$.content.length()").value(1));

        mvc.perform(get("/api/v1/follow-ups").param("scope", "UPCOMING").header(AUTH, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].note").value("future"))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void theSummaryCountsSumToThePendingTotal() throws Exception {
        mvc.perform(create(Instant.now().minusSeconds(172_800), "past")).andExpect(status().isCreated());
        mvc.perform(create(Instant.now().plusSeconds(172_800), "future")).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/follow-ups/summary").header(AUTH, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(1))
                .andExpect(jsonPath("$.upcoming").value(1))
                .andExpect(jsonPath("$.dueToday").value(0));
    }

    @Test
    void filtersBySubject() throws Exception {
        mvc.perform(create(Instant.now().plusSeconds(3600), "on this enquiry")).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/follow-ups")
                        .param("subjectType", "ENQUIRY")
                        .param("subjectId", enquiryId.toString())
                        .header(AUTH, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    private org.springframework.test.web.servlet.RequestBuilder create(Instant dueAt, String note) {
        return post("/api/v1/follow-ups")
                .header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                {"subjectType":"ENQUIRY","subjectId":"%s","dueAt":"%s",
                 "assignedTo":"%s","note":"%s"}
                """.formatted(enquiryId, dueAt, ownerId, note));
    }
}
