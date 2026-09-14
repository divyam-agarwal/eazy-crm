package com.easycrm.iam.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.easycrm.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Signup closed (spec §3.6). Its own context: the property is read per request from a bound record. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "easycrm.signup.enabled=false")
class SignupClosedTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    TenantRepository tenants;

    private static final String BODY = """
        {"slug":"%s","businessName":"Closed","stateCode":"27","email":"o@closed.test","password":"correct-horse"}""";

    @Test
    void statusReportsClosed() throws Exception {
        mvc.perform(get("/api/v1/auth/signup/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(false));
    }

    @Test
    void aClosedSignupIs404AndRevealsNothingAboutSlugs() throws Exception {
        var existing = tokens.provisionOwner("27");
        String takenSlug = tenants.findById(existing.tenantId()).orElseThrow().getSlug();

        String taken = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.formatted(takenSlug)))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String free = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.formatted("closed-free-slug")))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertEquals(free, taken, "a taken and a free slug must be indistinguishable while signup is closed");
    }
}
