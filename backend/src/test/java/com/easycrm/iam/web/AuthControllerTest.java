package com.easycrm.iam.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void signupThenLoginThenRefresh() throws Exception {
        String signup = """
            {"slug":"ctrl-a","businessName":"Ctrl A","stateCode":"27",
             "email":"o@ctrl-a.test","password":"correct-horse"}""";
        MvcResult signupResult = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signup))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").exists())
                .andReturn();

        String login = """
            {"slug":"ctrl-a","email":"o@ctrl-a.test","password":"correct-horse"}""";
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));

        String raw = AuthCookieTest.cookieValue(signupResult);
        mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("easycrm_rt", raw))
                        .header("X-EasyCRM-Client", "web"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());
    }

    @Test
    void signupValidationFailsWithoutPassword() throws Exception {
        String bad = """
            {"slug":"ctrl-b","businessName":"Ctrl B","stateCode":"27","email":"o@ctrl-b.test"}""";
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    void beanValidationFailuresCarryConstraintCodes() throws Exception {
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slug":"codes-a","businessName":"Codes","stateCode":"27","email":"o@codes-a.test"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldCodes.password").value("NOT_BLANK"));
    }

    @Test
    void aTakenSlugLandsOnTheSlugFieldWithACode() throws Exception {
        String body = """
            {"slug":"codes-dupe","businessName":"Codes","stateCode":"27",
             "email":"%s","password":"correct-horse"}""";
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("a@codes.test")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("b@codes.test")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.fields.slug").exists())
                .andExpect(jsonPath("$.error.fieldCodes.slug").value("SLUG_TAKEN"));
    }

    @Test
    void aSellerGstinDisagreeingWithTheStateCarriesAMismatchCode() throws Exception {
        // 27AAPFU0939F1ZV is a valid Maharashtra (27) GSTIN; stateCode 29 is Karnataka.
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slug":"codes-mismatch","businessName":"Codes","stateCode":"29","gstin":"27AAPFU0939F1ZV",
                             "email":"o@codes-m.test","password":"correct-horse"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.stateCode").value("STATE_CODE_GSTIN_MISMATCH"));
    }
}
