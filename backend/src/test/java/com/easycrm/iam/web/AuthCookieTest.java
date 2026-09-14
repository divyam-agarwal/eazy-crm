package com.easycrm.iam.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Spec 2026-09-14-f0 §3.1: the refresh token lives only in an httpOnly cookie. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthCookieTest extends IntegrationTest {

    static final String CLIENT = "X-EasyCRM-Client";

    @Autowired
    MockMvc mvc;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    static String setCookie(MvcResult r) {
        List<String> all = r.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        return all.stream()
                .filter(h -> h.startsWith("easycrm_rt="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no easycrm_rt Set-Cookie in " + all));
    }

    static String cookieValue(MvcResult r) {
        String h = setCookie(r);
        return h.substring("easycrm_rt=".length(), h.indexOf(';'));
    }

    static void assertSessionCookie(MvcResult r) {
        String h = setCookie(r);
        assertTrue(h.contains("Path=/api/v1/auth"), h);
        assertTrue(h.contains("Max-Age=2592000"), h);
        assertTrue(h.contains("Secure"), h);
        assertTrue(h.contains("HttpOnly"), h);
        assertTrue(h.contains("SameSite=Strict"), h);
        assertFalse(cookieValue(r).isBlank(), h);
    }

    MvcResult signup(String slug) throws Exception {
        return mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slug":"%s","businessName":"Cookie Biz","stateCode":"27",
                             "email":"Owner@%s.test","password":"correct-horse"}""".formatted(slug, slug)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    @Test
    void signupSetsTheCookieAndReturnsIdentityWithoutARefreshToken() throws Exception {
        String slug = "ck-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult r = signup(slug);

        assertSessionCookie(r);
        String body = r.getResponse().getContentAsString();
        assertFalse(body.contains("refreshToken"), body);
        mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("easycrm_rt", cookieValue(r)))
                        .header(CLIENT, "web"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.tenantSlug").value(slug))
                .andExpect(jsonPath("$.email").value("Owner@" + slug + ".test"))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    void loginSetsTheCookie() throws Exception {
        String slug = "ck-" + UUID.randomUUID().toString().substring(0, 8);
        signup(slug);
        MvcResult r = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slug":"%s","email":"owner@%s.test","password":"correct-horse"}""".formatted(slug, slug)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantSlug").value(slug))
                .andReturn();
        assertSessionCookie(r);
    }

    @Test
    void refreshRotatesTheCookie() throws Exception {
        MvcResult s = signup("ck-" + UUID.randomUUID().toString().substring(0, 8));
        String first = cookieValue(s);

        MvcResult r = mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("easycrm_rt", first))
                        .header(CLIENT, "web"))
                .andExpect(status().isOk())
                .andReturn();

        assertSessionCookie(r);
        assertNotEquals(first, cookieValue(r));
    }

    @Test
    void refreshWithoutACookieIs401() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").header(CLIENT, "web")).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesAndClearsTheCookie() throws Exception {
        String raw = cookieValue(signup("ck-" + UUID.randomUUID().toString().substring(0, 8)));

        MvcResult r = mvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie("easycrm_rt", raw))
                        .header(CLIENT, "web"))
                .andExpect(status().isNoContent())
                .andReturn();

        String cleared = setCookie(r);
        assertTrue(cleared.contains("Max-Age=0"), cleared);
        assertTrue(cleared.contains("Path=/api/v1/auth"), cleared);
        mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("easycrm_rt", raw))
                        .header(CLIENT, "web"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutACookieIsStill204() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").header(CLIENT, "web")).andExpect(status().isNoContent());
    }

    @Test
    void refreshWithoutTheClientHeaderIs403WithTheEnvelope() throws Exception {
        String raw = cookieValue(signup("ck-" + UUID.randomUUID().toString().substring(0, 8)));
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("easycrm_rt", raw)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        // The header check must run BEFORE rotation: the token is still usable afterwards.
        mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("easycrm_rt", raw))
                        .header(CLIENT, "web"))
                .andExpect(status().isOk());
    }

    @Test
    void logoutWithoutTheClientHeaderIs403AndRevokesNothing() throws Exception {
        String raw = cookieValue(signup("ck-" + UUID.randomUUID().toString().substring(0, 8)));
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("easycrm_rt", raw)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("easycrm_rt", raw))
                        .header(CLIENT, "web"))
                .andExpect(status().isOk());
    }

    @Test
    void aCrossOriginPreflightToRefreshIsRefused() throws Exception {
        // No CORS configuration exists, so a cross-origin fetch carrying the custom header can never
        // pass its preflight. If CORS is ever added, this test forces a deliberate decision.
        MvcResult r = mvc.perform(options("/api/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, CLIENT))
                .andReturn();
        assertNull(r.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertTrue(
                r.getResponse().getStatus() >= 400,
                "preflight status " + r.getResponse().getStatus());
    }
}
