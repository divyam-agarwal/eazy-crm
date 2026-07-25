package com.easycrm.iam.web;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AuthMeIntegrationTest extends IntegrationTest {
    @Autowired MockMvc mvc;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void signupTokenCanCallMe() throws Exception {
        String signup = """
            {"slug":"me-a","businessName":"Me A","stateCode":"27",
             "email":"o@me-a.test","password":"correct-horse"}""";
        String body = mvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON).content(signup))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String access = JsonPath.read(body, "$.accessToken");

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("o@me-a.test"))
            .andExpect(jsonPath("$.role").value("OWNER"))
            .andExpect(jsonPath("$.tenantSlug").value("me-a"));
    }
}
