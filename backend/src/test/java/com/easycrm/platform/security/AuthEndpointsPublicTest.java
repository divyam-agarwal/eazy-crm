package com.easycrm.platform.security;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthEndpointsPublicTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @Test
    void loginEndpointIsReachableWithoutToken() throws Exception {
        // No Authorization header. Assert it is NOT 401 — i.e. security permitted it through.
        // The controller may not exist yet at this task (404) and later validates the body
        // (400); both are fine. The one status that must NOT occur is 401 (blocked by auth).
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(result -> assertNotEquals(
                        401, result.getResponse().getStatus(), "login must be permitted without a token, not 401"));
    }

    @Test
    void meEndpointStillRequiresAuth() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }
}
