package com.easycrm.platform.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Guards the shipped configuration itself, not the mechanism.
 *
 * <p>IntegrationTest disables the limiter suite-wide, which means no other test in the
 * codebase would notice if the production default flipped to false. This one reads
 * application.yml directly, so "off in tests" cannot quietly become "off everywhere".
 */
class RateLimitDefaultsTest {

    private static RateLimitProperties shippedProperties() throws Exception {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        StandardEnvironment env = new StandardEnvironment();
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return new Binder(ConfigurationPropertySources.get(env))
                .bind("easycrm.rate-limit", RateLimitProperties.class)
                .get();
    }

    @Test
    void productionConfigShipsTheLimiterEnabled() throws Exception {
        assertTrue(
                shippedProperties().enabled(),
                "application.yml must ship enabled:true — the test harness disables it "
                        + "separately, and nothing else would catch a production default of false");
    }

    @Test
    void bothPublicAndAuthRoutesAreCovered() throws Exception {
        RateLimitProperties props = shippedProperties();

        RateLimitPolicy share = props.policyFor("/public/q/some-token")
                .orElseThrow(() -> new AssertionError("the public share route is unprotected"));
        assertEquals("public-share", share.name());
        assertEquals(60, share.capacity());
        assertEquals(Duration.ofHours(1), share.refillPeriod());

        RateLimitPolicy auth = props.policyFor("/api/v1/auth/login")
                .orElseThrow(() -> new AssertionError("the login route is unprotected"));
        assertEquals("auth", auth.name());
        assertEquals(30, auth.capacity());
        assertEquals(Duration.ofMinutes(1), auth.refillPeriod());

        assertTrue(
                props.policyFor("/api/v1/customers").isEmpty(),
                "authenticated API routes are deliberately unlimited for now");
        assertTrue(
                props.policyFor("/actuator/health").isEmpty(),
                "throttling health checks would take the app out of its own load balancer");
    }
}
