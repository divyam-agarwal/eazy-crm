package com.easycrm.support;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.easycrm.platform.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Mirror guard for {@code RateLimitIntegrationTest}'s
 * {@code theLimiterIsEnabledInThisTestContext}: that test asserts the limiter is ON in
 * its own tiny-limits context; this one asserts the limiter is OFF in the ordinary
 * shared harness context that every other {@code IntegrationTest} subclass uses. The
 * pair guards opposite directions of the same {@code easycrm.rate-limit.enabled}
 * default.
 *
 * <p>This class deliberately does nothing special beyond extending {@link
 * IntegrationTest} — no extra {@code @DynamicPropertySource} or
 * {@code @TestPropertySource} — so it shares the one cached context that ~60 other
 * integration test classes share, and therefore observes the harness's actual default,
 * not a bespoke one.
 *
 * <p>Why this matters: {@link IntegrationTest} sets that default via
 * {@code @TestPropertySource}, the lowest-precedence property source in play (see that
 * class's javadoc and engineering challenge #40). Nothing in the build enforces that
 * the default stays there or stays {@code false} — a future subclass declaring
 * {@code @TestPropertySource(inheritProperties = false)}, or someone simply deleting
 * the annotation, would silently re-enable the limiter for the shared context. Every
 * MockMvc request in every plain integration test then originates from the same
 * loopback address, so auth-touching requests from ALL test classes would accumulate
 * into one bucket and blow the 30/minute auth policy partway through the run — tests
 * failing as a function of how many others happened to run first, with no clear signal
 * pointing at the actual cause. This test fails first and says so.
 */
class HarnessRateLimitDisabledTest extends IntegrationTest {

    @Autowired
    RateLimitProperties properties;

    @Test
    void theLimiterIsDisabledInTheOrdinarySharedHarnessContext() {
        assertFalse(
                properties.enabled(),
                "the rate limiter is ENABLED in the shared harness context — the suite-wide "
                        + "default (easycrm.rate-limit.enabled=false on IntegrationTest) has been "
                        + "lost. Every MockMvc request in this shared context comes from the same "
                        + "loopback address, so unrelated integration tests will now accumulate "
                        + "into one bucket and start failing in proportion to how many ran first.");
    }
}
