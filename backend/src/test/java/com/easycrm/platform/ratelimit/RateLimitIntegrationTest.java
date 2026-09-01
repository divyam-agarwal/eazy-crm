package com.easycrm.platform.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * End-to-end proof that the rate limiter is wired in correctly, including the one
 * assertion that fails if the mechanism is installed in the wrong place: a drained
 * bucket on a PROTECTED route must answer 429, never 401.
 *
 * <p><b>Why {@code @DynamicPropertySource} here, not {@code @TestPropertySource}:</b>
 * {@link IntegrationTest} defaults {@code easycrm.rate-limit.enabled=false} through
 * {@code @TestPropertySource} so the limiter stays off for the rest of the suite (see
 * that class's javadoc). A {@code @TestPropertySource} override in THIS class would
 * NOT reliably beat that default: Spring merges {@code @TestPropertySource} inline
 * properties across a class hierarchy with subclass entries taking precedence only
 * over same-key entries at the SAME merge step, and in practice the safe, documented
 * way to unconditionally outrank any {@code @TestPropertySource} value — from any
 * class in the hierarchy — is {@code @DynamicPropertySource}, whose registrations
 * always win over {@code @TestPropertySource} regardless of which class declares
 * which (see "Context Configuration with Dynamic Property Sources" in the Spring
 * reference docs). That is why the default itself lives in
 * {@code @TestPropertySource} on {@link IntegrationTest} rather than in that class's
 * {@code @DynamicPropertySource} method — a second {@code @DynamicPropertySource}
 * registration of the same key on the superclass would always run after (and so beat)
 * a subclass's, since {@code @DynamicPropertySource} methods across a hierarchy are
 * invoked leaf-class-first, then superclass. Registering the override here as
 * {@code @DynamicPropertySource} is therefore the only race-free way to guarantee
 * this class's tiny limits win.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RateLimitIntegrationTest extends IntegrationTest {

    @DynamicPropertySource
    static void rateLimitProps(DynamicPropertyRegistry registry) {
        registry.add("easycrm.rate-limit.enabled", () -> "true");
        // Tiny limits: the point is the mechanism, not looping sixty times. A distinct
        // property set means a distinct cached Spring context, so these buckets never
        // touch another test class's.
        registry.add("easycrm.rate-limit.policies[0].name", () -> "public-share");
        registry.add("easycrm.rate-limit.policies[0].path", () -> "/public/q/*");
        registry.add("easycrm.rate-limit.policies[0].capacity", () -> "2");
        registry.add("easycrm.rate-limit.policies[0].refill-period", () -> "1h");
        // Not a shipped policy. It exists so the ordering proof below has a PROTECTED
        // route to aim at — that assertion is impossible without one.
        registry.add("easycrm.rate-limit.policies[1].name", () -> "api-protected");
        registry.add("easycrm.rate-limit.policies[1].path", () -> "/api/v1/customers/**");
        registry.add("easycrm.rate-limit.policies[1].capacity", () -> "2");
        registry.add("easycrm.rate-limit.policies[1].refill-period", () -> "1h");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    RateLimitProperties properties;

    /** Distinct source addresses, so each test gets a fresh bucket in the shared store. */
    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static String someToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Diagnostic guard, not part of the brief's five: if property-source precedence
     * ever resolves the other way (see class javadoc), every other test in this class
     * would fail with confusing 404s/401s that look like the feature is broken. This
     * test fails first, and its message says the actual problem: the limiter is
     * disabled in this context.
     *
     * <p>Matched pair with {@code com.easycrm.support.HarnessRateLimitDisabledTest},
     * which guards the opposite direction: that the limiter stays OFF in the ordinary
     * shared harness context every other integration test class uses. Together they
     * pin both edges of the {@code easycrm.rate-limit.enabled} default.
     */
    @Test
    void theLimiterIsEnabledInThisTestContext() {
        assertTrue(
                properties.enabled(),
                "the limiter is disabled in this context — "
                        + "check @DynamicPropertySource precedence against IntegrationTest");
        assertEquals(
                2,
                properties.policyFor("/public/q/anything").orElseThrow().capacity(),
                "expected this class's tiny test capacity (2), not the shipped default (60) — "
                        + "the limiter is reading the wrong property source");
    }

    @Test
    void thirdRequestToThePublicRouteIs429WithRetryAfterAndTheHouseEnvelope() throws Exception {
        String token = someToken();
        // An unknown token 404s, which is fine: the limiter runs before the handler, so
        // the response status below proves the bucket drained regardless of the outcome.
        mvc.perform(get("/public/q/" + token).with(from("203.0.113.10"))).andExpect(status().isNotFound());
        mvc.perform(get("/public/q/" + token).with(from("203.0.113.10"))).andExpect(status().isNotFound());

        var third = mvc.perform(get("/public/q/" + token).with(from("203.0.113.10")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.error.message").exists())
                .andReturn();

        assertTrue(
                Integer.parseInt(third.getResponse().getHeader("Retry-After")) >= 1,
                "Retry-After must never be 0 — that invites an immediate retry");
    }

    @Test
    void spoofedForwardedHeadersShareOneBucket() throws Exception {
        String token = someToken();
        // Priming calls assert 404 (unknown token, bucket not yet drained) so a bucket
        // leaked from an earlier test fails right here, not silently at the third call.
        mvc.perform(get("/public/q/" + token).with(from("203.0.113.20")).header("X-Forwarded-For", "1.1.1.1"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/public/q/" + token).with(from("203.0.113.20")).header("X-Forwarded-For", "2.2.2.2"))
                .andExpect(status().isNotFound());

        // If the filter trusted the header, each request would have minted its own bucket
        // and this third one would sail through — a bypass any client could perform.
        mvc.perform(get("/public/q/" + token).with(from("203.0.113.20")).header("X-Forwarded-For", "3.3.3.3"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void limiterRunsBeforeSpringSecurity() throws Exception {
        // No Authorization header: this route answers 401 normally.
        mvc.perform(get("/api/v1/customers/" + UUID.randomUUID()).with(from("203.0.113.30")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/customers/" + UUID.randomUUID()).with(from("203.0.113.30")))
                .andExpect(status().isUnauthorized());

        // Once the bucket is drained the answer must become 429, NOT 401. A 401 here means
        // the filter is running behind the security chain — in which case the auth policy
        // would never see a failed login, and every other test in this class would still
        // pass. This is the assertion that catches a misordered filter.
        mvc.perform(get("/api/v1/customers/" + UUID.randomUUID()).with(from("203.0.113.30")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void unmatchedPathsAreNeverLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/actuator/health").with(from("203.0.113.40"))).andExpect(status().isOk());
        }
    }

    @Test
    void distinctClientsDoNotShareAnAllowance() throws Exception {
        String token = someToken();
        // Priming calls assert 404 (unknown token, bucket not yet drained) so a bucket
        // leaked from an earlier test fails right here, not silently at the third call.
        mvc.perform(get("/public/q/" + token).with(from("203.0.113.50"))).andExpect(status().isNotFound());
        mvc.perform(get("/public/q/" + token).with(from("203.0.113.50"))).andExpect(status().isNotFound());
        mvc.perform(get("/public/q/" + token).with(from("203.0.113.50"))).andExpect(status().isTooManyRequests());

        // Same token, different recipient: a forwarded share link must still open. This is
        // the behaviour a per-token bucket would have broken (spec section 3).
        mvc.perform(get("/public/q/" + token).with(from("203.0.113.51"))).andExpect(status().isNotFound());
    }
}
