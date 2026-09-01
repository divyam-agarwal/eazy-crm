package com.easycrm.platform.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class RateLimitFilterTest {

    /** Records the keys it is asked about, and denies once told to. */
    static final class RecordingStore implements RateLimitStore {
        final List<String> keys = new ArrayList<>();
        boolean deny = false;

        @Override
        public Decision tryConsume(String key, RateLimitPolicy policy) {
            keys.add(key);
            return deny ? new Decision(false, 36_500_000_000L) : new Decision(true, 0);
        }
    }

    private static final RateLimitProperties PROPS = new RateLimitProperties(
            true, List.of(new RateLimitPolicy("public-share", "/public/q/*", 2, Duration.ofHours(1))));

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
        assertTrue(
                res.getContentAsString().contains("\"code\":\"RATE_LIMITED\""),
                "must match the ApiExceptionHandler envelope shape, got: " + res.getContentAsString());
        assertTrue(res.getContentAsString().contains("\"error\""));
    }

    @Test
    void unmatchedPathIsNeverConsulted() throws Exception {
        RecordingStore store = new RecordingStore();
        store.deny = true; // would deny if it were ever asked
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
    void matchesAPolicyEvenUnderANonEmptyContextPath() throws Exception {
        // getRequestURI() INCLUDES the servlet context path. If the filter matched on
        // that raw URI, setting server.servlet.context-path=/crm would turn every
        // "/public/q/*"-style policy path into a permanent non-match — the limiter would
        // become a total no-op with every existing test still green, since no other test
        // sets a context path. Matching must be done on the path WITHIN the application.
        RecordingStore store = new RecordingStore();
        RateLimitFilter filter = new RateLimitFilter(PROPS, store, new ObjectMapper());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/crm/public/q/tok");
        req.setContextPath("/crm");
        req.setRequestURI("/crm/public/q/tok");
        req.setRemoteAddr("1.2.3.4");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(any(), any());
        assertEquals(
                List.of("1.2.3.4"), store.keys, "a request under a non-empty context path must still match its policy");
    }

    @Test
    void rejectionLogsAWarningNamingThePolicyAndClientButNeverTheUriOrToken() throws Exception {
        // Before this, a rejection was completely silent: no log line, no counter,
        // nothing. An operator could not tell "limiter working" from "limiter matching
        // nothing." The fix must name the policy and client (so it's actionable) while
        // never logging the request URI/path — on /public/q/{token} that URI IS the share
        // token, and PublicShareController's javadoc is explicit that the token must
        // never reach a log.
        ch.qos.logback.classic.Logger logbackLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RateLimitFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            RecordingStore store = new RecordingStore();
            store.deny = true;
            RateLimitFilter filter = new RateLimitFilter(PROPS, store, new ObjectMapper());
            MockHttpServletResponse res = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request("/public/q/super-secret-token", "9.9.9.9"), res, chain);

            assertEquals(1, appender.list.size(), "exactly one rejection, exactly one log line");
            ILoggingEvent event = appender.list.get(0);
            assertEquals(Level.WARN, event.getLevel());
            String message = event.getFormattedMessage();
            assertTrue(message.contains("public-share"), "must name the policy: " + message);
            assertTrue(message.contains("9.9.9.9"), "must name the client: " + message);
            assertFalse(message.contains("super-secret-token"), "must never log the share token: " + message);
            assertFalse(message.contains("/public/q"), "must never log the request URI/path: " + message);
        } finally {
            logbackLogger.detachAppender(appender);
        }
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
