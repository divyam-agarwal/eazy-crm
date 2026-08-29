package com.easycrm.platform.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitPolicyTest {

    private static RateLimitPolicy policy(String path) {
        return new RateLimitPolicy("test", path, 10, Duration.ofMinutes(1));
    }

    @Test
    void singleSegmentWildcardMatchesExactlyOneSegment() {
        RateLimitPolicy p = policy("/public/q/*");
        assertTrue(p.matches("/public/q/abc123"));
        // Deliberately NOT limited: an extra segment never resolves to the share route,
        // is rejected at the security chain, and renders no PDF (spec section 5).
        assertFalse(p.matches("/public/q/abc/def"));
        assertFalse(p.matches("/public/q"));
        assertFalse(p.matches("/api/v1/customers"));
    }

    @Test
    void doubleWildcardMatchesEverythingBeneath() {
        RateLimitPolicy p = policy("/api/v1/auth/**");
        assertTrue(p.matches("/api/v1/auth/login"));
        assertTrue(p.matches("/api/v1/auth/refresh"));
        assertFalse(p.matches("/api/v1/customers"));
    }

    @Test
    void retryAfterRoundsUpAndIsNeverZero() {
        // A floor would round sub-second waits to 0 and invite an immediate retry.
        assertEquals(1, RateLimitPolicy.retryAfterSeconds(1));
        assertEquals(1, RateLimitPolicy.retryAfterSeconds(999_999_999L));
        assertEquals(2, RateLimitPolicy.retryAfterSeconds(1_000_000_001L));
        assertEquals(37, RateLimitPolicy.retryAfterSeconds(36_500_000_000L));
        assertEquals(1, RateLimitPolicy.retryAfterSeconds(0));
        assertEquals(1, RateLimitPolicy.retryAfterSeconds(-5));
    }

    @Test
    void firstMatchingPolicyWinsAndUnmatchedPathsAreUnlimited() {
        RateLimitProperties props = new RateLimitProperties(true, List.of(
            new RateLimitPolicy("public-share", "/public/q/*", 60, Duration.ofHours(1)),
            new RateLimitPolicy("auth", "/api/v1/auth/**", 30, Duration.ofMinutes(1))));

        assertEquals("public-share", props.policyFor("/public/q/tok").orElseThrow().name());
        assertEquals("auth", props.policyFor("/api/v1/auth/login").orElseThrow().name());
        // Unmatched: health checks and the future frontend must never be throttled
        // by a limit nobody sized for them.
        assertTrue(props.policyFor("/api/v1/customers").isEmpty());
        assertTrue(props.policyFor("/actuator/health").isEmpty());
    }
}
