package com.easycrm.platform.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Optional;

/**
 * Limits are configuration, not code: a deployment retunes them without a release.
 *
 * <p>{@code enabled} defaults to TRUE. The test harness turns it off explicitly
 * (see IntegrationTest) — a default of false would mean a config typo silently ships
 * an unprotected application.
 */
@ConfigurationProperties("easycrm.rate-limit")
public record RateLimitProperties(@DefaultValue("true") boolean enabled,
                                  @DefaultValue List<RateLimitPolicy> policies) {

    /** First match wins; an unmatched path is unlimited. */
    public Optional<RateLimitPolicy> policyFor(String requestPath) {
        return policies.stream().filter(p -> p.matches(requestPath)).findFirst();
    }
}
