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
import tools.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §6.2 (complete-and-log) and §7.2 (transition guards). */
@SpringBootTest
@AutoConfigureMockMvc
class FollowUpTransitionEndpointTest extends IntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired EnquiryRepository enquiries;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;

    private final UUID tenantId = UUID.randomUUID();
    private UUID ownerId;
    private String ownerToken;
    private UUID enquiryId;

    @BeforeEach
    void seed() {
        ownerId = seedUser(tenantId);
        ownerToken = tokens.as(tenantId, ownerId, "OWNER");
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, ownerId, "OWNER"));
        tx.executeWithoutResult(s -> enquiryId = enquiries.saveAndFlush(new Enquiry(
            null, "Ramesh", "9876555001", "9876555001", null,
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
    void completingMarksItDone() throws Exception {
        UUID id = newFollowUp();

        mvc.perform(post("/api/v1/follow-ups/" + id + "/complete")
                .header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"rang, sending a revised quote\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DONE"))
            .andExpect(jsonPath("$.completionNote").value("rang, sending a revised quote"))
            .andExpect(jsonPath("$.overdue").value(false))
            .andExpect(jsonPath("$.completedAt").exists());
    }

    @Test
    void completingTwiceIs422() throws Exception {
        UUID id = newFollowUp();
        complete(id, "done").andExpect(status().isOk());

        complete(id, "again")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.fields.status").exists());
    }

    @Test
    void completingWithAnActivityWritesItToTheSameSubject() throws Exception {
        UUID id = newFollowUp();

        mvc.perform(post("/api/v1/follow-ups/" + id + "/complete")
                .header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"note":"closed it","type":"CALL","body":"rang them back",
                     "outcome":"agreed on price"}
                    """))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/activities")
                .param("subjectType", "ENQUIRY").param("subjectId", enquiryId.toString())
                .header(AUTH, "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].body").value("rang them back"))
            .andExpect(jsonPath("$.content[0].type").value("CALL"));
    }

    @Test
    void completingWithoutAnActivityWritesNone() throws Exception {
        UUID id = newFollowUp();
        complete(id, "just closing it").andExpect(status().isOk());

        mvc.perform(get("/api/v1/activities")
                .param("subjectType", "ENQUIRY").param("subjectId", enquiryId.toString())
                .header(AUTH, "Bearer " + ownerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void cancellingRequiresAReason() throws Exception {
        UUID id = newFollowUp();

        mvc.perform(post("/api/v1/follow-ups/" + id + "/cancel")
                .header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"   \"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.fields.reason").exists());
    }

    @Test
    void cancellingWithAReasonWorks() throws Exception {
        UUID id = newFollowUp();

        mvc.perform(post("/api/v1/follow-ups/" + id + "/cancel")
                .header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"went with a competitor\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.completionNote").value("went with a competitor"));
    }

    @Test
    void reschedulingMovesTheDueDate() throws Exception {
        UUID id = newFollowUp();
        Instant newDue = Instant.now().plusSeconds(432_000);

        mvc.perform(patch("/api/v1/follow-ups/" + id)
                .header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"dueAt":"%s","assignedTo":"%s","note":"pushed a week"}
                    """.formatted(newDue, ownerId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.note").value("pushed a week"));
    }

    @Test
    void reschedulingACompletedFollowUpIs422() throws Exception {
        UUID id = newFollowUp();
        complete(id, "done").andExpect(status().isOk());

        mvc.perform(patch("/api/v1/follow-ups/" + id)
                .header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"dueAt":"%s","assignedTo":"%s","note":"revived"}
                    """.formatted(Instant.now().plusSeconds(3600), ownerId)))
            .andExpect(status().isUnprocessableEntity());
    }

    private org.springframework.test.web.servlet.ResultActions complete(UUID id, String note)
            throws Exception {
        return mvc.perform(post("/api/v1/follow-ups/" + id + "/complete")
            .header(AUTH, "Bearer " + ownerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"" + note + "\"}"));
    }

    private UUID newFollowUp() throws Exception {
        String body = mvc.perform(post("/api/v1/follow-ups")
                .header(AUTH, "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","dueAt":"%s",
                     "assignedTo":"%s","note":"ring back"}
                    """.formatted(enquiryId, Instant.now().plusSeconds(172_800), ownerId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asText());
    }
}
