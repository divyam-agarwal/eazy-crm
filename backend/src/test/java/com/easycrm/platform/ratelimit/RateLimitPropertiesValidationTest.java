package com.easycrm.platform.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Guards the spec's claim that {@link RateLimitProperties} is "validated": without
 * {@code @Validated} + the constraints on {@link RateLimitPolicy}, {@code capacity: 0}
 * binds without complaint and then denies EVERY request on that policy's route — a
 * self-inflicted outage discovered in production instead of at startup.
 */
class RateLimitPropertiesValidationTest {

    @Configuration
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class EnableRateLimitProperties {}

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(EnableRateLimitProperties.class);

    @Test
    void zeroCapacityFailsFastAtStartupInsteadOfDenyingEveryRequestInProduction() {
        contextRunner
                .withPropertyValues(
                        "easycrm.rate-limit.policies[0].name=test",
                        "easycrm.rate-limit.policies[0].path=/x/*",
                        "easycrm.rate-limit.policies[0].capacity=0",
                        "easycrm.rate-limit.policies[0].refill-period=1m")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void negativeCapacityFailsFastAtStartup() {
        contextRunner
                .withPropertyValues(
                        "easycrm.rate-limit.policies[0].name=test",
                        "easycrm.rate-limit.policies[0].path=/x/*",
                        "easycrm.rate-limit.policies[0].capacity=-1",
                        "easycrm.rate-limit.policies[0].refill-period=1m")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void blankNameOrPathFailsFastAtStartup() {
        contextRunner
                .withPropertyValues(
                        "easycrm.rate-limit.policies[0].name=",
                        "easycrm.rate-limit.policies[0].path=/x/*",
                        "easycrm.rate-limit.policies[0].capacity=10",
                        "easycrm.rate-limit.policies[0].refill-period=1m")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aValidPolicyStartsCleanly() {
        contextRunner
                .withPropertyValues(
                        "easycrm.rate-limit.policies[0].name=test",
                        "easycrm.rate-limit.policies[0].path=/x/*",
                        "easycrm.rate-limit.policies[0].capacity=10",
                        "easycrm.rate-limit.policies[0].refill-period=1m")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
