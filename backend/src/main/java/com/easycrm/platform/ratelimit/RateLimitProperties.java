package com.easycrm.platform.ratelimit;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Limits are configuration, not code: a deployment retunes them without a release.
 *
 * <p>{@code enabled} defaults to TRUE. The test harness turns it off explicitly
 * (see IntegrationTest) — a default of false would mean a config typo silently ships
 * an unprotected application.
 *
 * <p>{@code @Validated} + {@code @Valid} on {@code policies} make a malformed policy a
 * startup failure instead of a silent production outage: without it, {@code capacity: 0}
 * binds without complaint and then denies every request on that policy's route.
 */
@ConfigurationProperties("easycrm.rate-limit")
@Validated
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue @Valid List<RateLimitPolicy> policies) {

    /** First match wins; an unmatched path is unlimited. */
    public Optional<RateLimitPolicy> policyFor(String requestPath) {
        return policies.stream().filter(p -> p.matches(requestPath)).findFirst();
    }
}
