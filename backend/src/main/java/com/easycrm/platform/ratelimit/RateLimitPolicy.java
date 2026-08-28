package com.easycrm.platform.ratelimit;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One named limit bound to a path pattern: {@code capacity} requests per
 * {@code refillPeriod}, per client IP.
 *
 * <p>This is a plain four-component record so Spring Boot's configuration binder can
 * bind it through its canonical constructor from YAML. A fifth, precompiled
 * {@link PathPattern} component would have no source in configuration and no
 * converter, which breaks binding. Instead the compiled pattern is resolved lazily
 * through {@link #PATTERN_CACHE}, keyed on {@code path}.
 */
public record RateLimitPolicy(String name, String path, long capacity, Duration refillPeriod) {

    // Keyed on `path`, which comes only from configuration (application.yml), never
    // from request input. The number of distinct keys is therefore bounded by the
    // number of configured policies, so this cache cannot be grown by a caller.
    private static final ConcurrentHashMap<String, PathPattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    public boolean matches(String requestPath) {
        PathPattern compiled = PATTERN_CACHE.computeIfAbsent(path, PathPatternParser.defaultInstance::parse);
        return compiled.matches(PathContainer.parsePath(requestPath));
    }

    /**
     * Whole seconds for the {@code Retry-After} header, rounded UP and floored at 1.
     * Rounding down would emit {@code Retry-After: 0} for any sub-second wait, which
     * tells a client to retry immediately — the opposite of the point.
     */
    public static long retryAfterSeconds(long nanosToWaitForRefill) {
        if (nanosToWaitForRefill <= 0) return 1;
        return Math.max(1, (nanosToWaitForRefill + 999_999_999L) / 1_000_000_000L);
    }
}
