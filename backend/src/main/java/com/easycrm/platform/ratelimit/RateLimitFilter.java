package com.easycrm.platform.ratelimit;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

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

    private final RateLimitProperties properties;
    private final RateLimitStore store;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimitProperties properties, RateLimitStore store,
                           ObjectMapper objectMapper) {
        this.properties = properties;
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {
        if (!properties.enabled()) {
            chain.doFilter(request, response);
            return;
        }
        Optional<RateLimitPolicy> policy = properties.policyFor(request.getRequestURI());
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
        objectMapper.writeValue(response.getOutputStream(), Map.of("error", Map.of(
            "code", "RATE_LIMITED",
            "message", "too many requests; please retry shortly")));
    }
}
