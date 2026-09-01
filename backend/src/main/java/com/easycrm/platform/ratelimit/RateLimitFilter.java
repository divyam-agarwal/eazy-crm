package com.easycrm.platform.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-IP token bucket, in front of everything.
 *
 * <p><b>Ordering is a correctness requirement.</b> This filter runs BEFORE Spring
 * Security (see RateLimitConfig). Two reasons: work must be capped before it is done,
 * and on the auth routes the traffic worth limiting is traffic that FAILS
 * authentication — behind the security chain, a credential-stuffing attempt would
 * short-circuit to 401 and this filter would never see it.
 *
 * <p>Running that early means there is no Authentication and no TenantContext, so the
 * only available key is the client address. That is exactly the key the design wants
 * (spec section 3): a share link is meant to be forwarded, so a per-token bucket would
 * miss token-space scans while denying legitimate recipients.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    // getPathWithinApplication, not getRequestURI: the URI includes the servlet context
    // path (server.servlet.context-path). Matching on the raw URI means every configured
    // policy path (e.g. "/public/q/*") stops matching the instant a context path is set,
    // and policyFor(...) silently returns empty for every request — the limiter becomes a
    // total no-op with every existing test still green, because no test sets a context
    // path. This mirrors the ordering test's failure class: a misconfiguration that looks
    // like nothing changed.
    private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;

    private final RateLimitProperties properties;
    private final RateLimitStore store;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimitProperties properties, RateLimitStore store, ObjectMapper objectMapper) {
        this.properties = properties;
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        if (!properties.enabled()) {
            chain.doFilter(request, response);
            return;
        }
        Optional<RateLimitPolicy> policy = properties.policyFor(PATH_HELPER.getPathWithinApplication(request));
        if (policy.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        RateLimitPolicy p = policy.get();
        // getRemoteAddr() ONLY. X-Forwarded-For is client-supplied: reading it here would
        // let any caller mint a fresh bucket per request. Behind a trusted proxy the right
        // fix is server.forward-headers-strategy=framework, which is a deployment
        // statement and fixes getRemoteAddr() for every consumer at once.
        //
        // The key passed here is the client identifier ALONE — the store namespaces per
        // policy internally (see RateLimitStore's @param key javadoc), so prefixing the
        // policy name here too would double-namespace the bucket.
        RateLimitStore.Decision decision = store.tryConsume(request.getRemoteAddr(), p);

        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        // WARN, not silence: without this line a token-space scan against the public
        // route is throttled but completely invisible, and an operator has no way to
        // distinguish "limiter working" from "limiter matching nothing" (see
        // policyFor above). Deliberately NOT logged: the request URI/path — on
        // /public/q/{token} it contains the share token, and PublicShareController's
        // javadoc is explicit that the token must never reach a log.
        log.warn("rate limit exceeded: policy={} client={}", p.name(), request.getRemoteAddr());
        reject(response, RateLimitPolicy.retryAfterSeconds(decision.nanosToWaitForRefill()));
    }

    /**
     * The 429 body is written here rather than by ApiExceptionHandler because an exception
     * thrown from a servlet filter never reaches @RestControllerAdvice. This is a knowing
     * duplication of the error envelope in exactly one place; RateLimitIntegrationTest
     * asserts the two shapes agree so they cannot drift silently.
     *
     * <p>No X-RateLimit-* headers: nothing consumes them and they advertise the limit.
     */
    private void reject(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        objectMapper.writeValue(
                response.getOutputStream(),
                Map.of(
                        "error",
                        Map.of(
                                "code", "RATE_LIMITED",
                                "message", "too many requests; please retry shortly")));
    }
}
