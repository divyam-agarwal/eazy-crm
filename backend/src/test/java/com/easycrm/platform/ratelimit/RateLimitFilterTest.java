package com.easycrm.platform.ratelimit;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateLimitFilterTest {

    /** Records the keys it is asked about, and denies once told to. */
    static final class RecordingStore implements RateLimitStore {
        final List<String> keys = new ArrayList<>();
        boolean deny = false;
        @Override public Decision tryConsume(String key, RateLimitPolicy policy) {
            keys.add(key);
            return deny ? new Decision(false, 36_500_000_000L) : new Decision(true, 0);
        }
    }

    private static final RateLimitProperties PROPS = new RateLimitProperties(true, List.of(
        new RateLimitPolicy("public-share", "/public/q/*", 2, Duration.ofHours(1))));

    private static MockHttpServletRequest request(String path, String remoteAddr) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    @Test
    void allowedRequestPassesDownTheChain() throws Exception {
        RecordingStore store = new RecordingStore();
        RateLimitFilter filter = new RateLimitFilter(PROPS, store, new ObjectMapper());
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/public/q/tok", "1.2.3.4"), res, chain);

        verify(chain).doFilter(any(), any());
        assertEquals(200, res.getStatus());
    }

    @Test
    void deniedRequestIs429WithRetryAfterAndTheHouseEnvelope() throws Exception {
        RecordingStore store = new RecordingStore();
        store.deny = true;
        RateLimitFilter filter = new RateLimitFilter(PROPS, store, new ObjectMapper());
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/public/q/tok", "1.2.3.4"), res, chain);

        // The chain must NOT run: the entire point is that the expensive work never starts.
        verify(chain, never()).doFilter(any(), any());
        assertEquals(429, res.getStatus());
        assertEquals("37", res.getHeader("Retry-After"));
        assertEquals("application/json", res.getContentType());
        assertTrue(res.getContentAsString().contains("\"code\":\"RATE_LIMITED\""),
            "must match the ApiExceptionHandler envelope shape, got: " + res.getContentAsString());
        assertTrue(res.getContentAsString().contains("\"error\""));
    }

    @Test
    void unmatchedPathIsNeverConsulted() throws Exception {
        RecordingStore store = new RecordingStore();
        store.deny = true;   // would deny if it were ever asked
        RateLimitFilter filter = new RateLimitFilter(PROPS, store, new ObjectMapper());
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/api/v1/customers", "1.2.3.4"), res, chain);

        verify(chain).doFilter(any(), any());
        assertEquals(200, res.getStatus());
        assertTrue(store.keys.isEmpty(), "an unmatched path must not even touch the store");
    }

    @Test
    void bucketKeyIsTheRemoteAddrAndIgnoresForwardedHeaders() throws Exception {
        RecordingStore store = new RecordingStore();
        RateLimitFilter filter = new RateLimitFilter(PROPS, store, new ObjectMapper());

        MockHttpServletRequest first = request("/public/q/tok", "1.2.3.4");
        first.addHeader("X-Forwarded-For", "9.9.9.9");
        MockHttpServletRequest second = request("/public/q/tok", "1.2.3.4");
        second.addHeader("X-Forwarded-For", "8.8.8.8");

        filter.doFilter(first, new MockHttpServletResponse(), mock(FilterChain.class));
        filter.doFilter(second, new MockHttpServletResponse(), mock(FilterChain.class));

        // Same socket, different spoofed headers, ONE bucket. Reading X-Forwarded-For here
        // would let any client mint a fresh bucket per request and the limiter would be
        // decorative while every other test still passed. The store owns policy
        // namespacing internally, so the filter passes the client identifier alone.
        assertEquals(List.of("1.2.3.4", "1.2.3.4"), store.keys);
    }

    @Test
    void disabledPropertiesSkipTheStoreEntirely() throws Exception {
        RecordingStore store = new RecordingStore();
        store.deny = true;
        RateLimitProperties off = new RateLimitProperties(false, PROPS.policies());
        RateLimitFilter filter = new RateLimitFilter(off, store, new ObjectMapper());
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/public/q/tok", "1.2.3.4"), res, chain);

        verify(chain).doFilter(any(), any());
        assertTrue(store.keys.isEmpty());
    }
}
