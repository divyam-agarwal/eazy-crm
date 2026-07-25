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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void signupThenLoginThenRefresh() throws Exception {
        String signup = """
            {"slug":"ctrl-a","businessName":"Ctrl A","stateCode":"27",
             "email":"o@ctrl-a.test","password":"correct-horse"}""";
        String body = mvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON).content(signup))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").exists())
            .andReturn().getResponse().getContentAsString();

        String login = """
            {"slug":"ctrl-a","email":"o@ctrl-a.test","password":"correct-horse"}""";
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(login))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("OWNER"));

        String refreshToken = JsonPath.read(body, "$.refreshToken");
        String refresh = "{\"refreshToken\":\"" + refreshToken + "\"}";
        mvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON).content(refresh))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").exists());
    }

    @Test
    void signupValidationFailsWithoutPassword() throws Exception {
        String bad = """
            {"slug":"ctrl-b","businessName":"Ctrl B","stateCode":"27","email":"o@ctrl-b.test"}""";
        mvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON).content(bad))
            .andExpect(status().isBadRequest());
    }
}
