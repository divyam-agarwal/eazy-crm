package com.easycrm.sales.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** HTTP contract for logging and reading activities. Spec §9. */
@SpringBootTest
@AutoConfigureMockMvc
class ActivityEndpointTest extends IntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    EnquiryRepository enquiries;

    @Autowired
    TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private String ownerToken;
    private UUID enquiryId;

    @BeforeEach
    void seed() {
        ownerToken = tokens.as(tenantId, ownerId, "OWNER");
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, ownerId, "OWNER"));
        tx.executeWithoutResult(s -> enquiryId = enquiries
                .saveAndFlush(new Enquiry(
                        null,
                        "Ramesh",
                        "9876511001",
                        "9876511001",
                        null,
                        EnquirySource.MANUAL,
                        "needs 10 bags",
                        ownerId,
                        null))
                .getId());
        TenantContext.clear();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void logsACallAgainstAnEnquiry() throws Exception {
        mvc.perform(post("/api/v1/activities")
                        .header(AUTH, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","type":"CALL",
                     "body":"rang them","outcome":"no answer"}
                    """.formatted(enquiryId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("CALL"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.body").value("rang them"))
                .andExpect(jsonPath("$.loggedBy").value(ownerId.toString()))
                .andExpect(jsonPath("$.occurredAt").exists());
    }

    @Test
    void occurredAtDefaultsToNowWhenOmitted() throws Exception {
        mvc.perform(post("/api/v1/activities")
                        .header(AUTH, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","type":"NOTE","body":"walked in"}
                    """.formatted(enquiryId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.occurredAt").exists());
    }

    @Test
    void aFutureOccurredAtIs422() throws Exception {
        mvc.perform(post("/api/v1/activities")
                        .header(AUTH, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","type":"CALL",
                     "body":"time travel","occurredAt":"%s"}
                    """.formatted(enquiryId, Instant.now().plusSeconds(86_400))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.occurredAt").exists());
    }

    @Test
    void aSubjectThatDoesNotExistIs404() throws Exception {
        mvc.perform(post("/api/v1/activities")
                        .header(AUTH, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","type":"CALL","body":"x"}
                    """.formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsTheSubjectTimelineNewestFirst() throws Exception {
        log("older", Instant.now().minusSeconds(7200));
        log("newer", Instant.now().minusSeconds(60));

        mvc.perform(get("/api/v1/activities")
                        .param("subjectType", "ENQUIRY")
                        .param("subjectId", enquiryId.toString())
                        .header(AUTH, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].body").value("newer"))
                .andExpect(jsonPath("$.content[1].body").value("older"));
    }

    @Test
    void listingWithoutASubjectIs400() throws Exception {
        mvc.perform(get("/api/v1/activities").header(AUTH, "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listingAnInvisibleSubjectIs404() throws Exception {
        mvc.perform(get("/api/v1/activities")
                        .param("subjectType", "ENQUIRY")
                        .param("subjectId", UUID.randomUUID().toString())
                        .header(AUTH, "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    private void log(String body, Instant occurredAt) throws Exception {
        mvc.perform(post("/api/v1/activities")
                        .header(AUTH, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","type":"CALL",
                     "body":"%s","occurredAt":"%s"}
                    """.formatted(enquiryId, body, occurredAt)))
                .andExpect(status().isCreated());
    }
}
