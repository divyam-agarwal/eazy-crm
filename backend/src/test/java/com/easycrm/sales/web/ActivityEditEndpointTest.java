package com.easycrm.sales.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.Enquiry;
import com.easycrm.sales.EnquiryRepository;
import com.easycrm.sales.EnquirySource;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
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
import tools.jackson.databind.ObjectMapper;

/** Spec §7.1: own MANUAL rows only, and the deliberate 422-not-404 choice. */
@SpringBootTest
@AutoConfigureMockMvc
class ActivityEditEndpointTest extends IntegrationTest {

    private static final String AUTH = "Authorization";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    EnquiryRepository enquiries;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ObjectMapper json;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();

    private String ownerToken, otherToken;
    private UUID enquiryId, activityId;

    @BeforeEach
    void seed() throws Exception {
        ownerToken = tokens.as(tenantId, ownerId, "OWNER");
        otherToken = tokens.as(tenantId, otherId, "OWNER");

        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, ownerId, "OWNER"));
        tx.executeWithoutResult(s -> enquiryId = enquiries
                .saveAndFlush(new Enquiry(
                        null,
                        "Ramesh",
                        "9876533001",
                        "9876533001",
                        null,
                        EnquirySource.MANUAL,
                        "needs bags",
                        null,
                        null))
                .getId());
        TenantContext.clear();

        String created = mvc.perform(post("/api/v1/activities")
                        .header(AUTH, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","type":"CALL",
                     "body":"rang them","outcome":"no answer"}
                    """.formatted(enquiryId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        activityId = UUID.fromString(json.readTree(created).get("id").asText());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void theLoggerCanCorrectTheirOwnEntry() throws Exception {
        mvc.perform(edit(ownerToken, "rang them twice", "spoke to the owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("rang them twice"))
                .andExpect(jsonPath("$.outcome").value("spoke to the owner"));
    }

    @Test
    void anotherUserGets422NotNotFound() throws Exception {
        mvc.perform(edit(otherToken, "hijacked", null)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aMismatchedSubjectIs404() throws Exception {
        mvc.perform(patch("/api/v1/activities/" + activityId)
                        .header(AUTH, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","body":"x","outcome":null}
                    """.formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownActivityIs404() throws Exception {
        mvc.perform(patch("/api/v1/activities/" + UUID.randomUUID())
                        .header(AUTH, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"subjectType":"ENQUIRY","subjectId":"%s","body":"x","outcome":null}
                    """.formatted(enquiryId)))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.RequestBuilder edit(String token, String body, String outcome) {
        return patch("/api/v1/activities/" + activityId)
                .header(AUTH, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                {"subjectType":"ENQUIRY","subjectId":"%s","body":"%s","outcome":%s}
                """.formatted(enquiryId, body, outcome == null ? "null" : "\"" + outcome + "\""));
    }
}
