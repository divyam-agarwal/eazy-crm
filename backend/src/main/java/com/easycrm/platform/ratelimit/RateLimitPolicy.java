package com.easycrm.platform.ratelimit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * One named limit bound to a path pattern: {@code capacity} requests per
 * {@code refillPeriod}, per client IP.
 *
 * <p>This is a plain four-component record so Spring Boot's configuration binder can
 * bind it through its canonical constructor from YAML. A fifth, precompiled
 * {@link PathPattern} component would have no source in configuration and no
 * converter, which breaks binding. Instead the compiled pattern is resolved lazily
 * through {@link #PATTERN_CACHE}, keyed on {@code path}.
 *
 * <p>Validated on bind (see {@code @Valid} on {@link RateLimitProperties#policies()}):
 * {@code capacity: 0} would otherwise bind happily and then deny every single request
 * on whatever route it's attached to — a self-inflicted outage that should fail at
 * startup, not surface as "every request 429s" in production.
 */
public record RateLimitPolicy(
        @NotBlank String name,
        @NotBlank String path,
        @Positive long capacity,
        @NotNull Duration refillPeriod) {

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
