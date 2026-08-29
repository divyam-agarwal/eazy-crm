package com.easycrm.platform.ratelimit;

import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    /**
     * Ahead of Spring Security, whose chain registers at
     * SecurityProperties.DEFAULT_FILTER_ORDER (-100). Anything less than that runs first.
     * If this number ever drifts above -100, the auth-route limit silently stops seeing
     * failed logins — RateLimitIntegrationTest's ordering test is what catches that.
     */
    public static final int FILTER_ORDER = -110;

    @Bean
    RateLimitStore rateLimitStore(RateLimitProperties properties) {
        return new InMemoryRateLimitStore(properties);
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimitProperties properties, RateLimitStore store, ObjectMapper objectMapper) {
        FilterRegistrationBean<RateLimitFilter> registration =
            new FilterRegistrationBean<>(new RateLimitFilter(properties, store, objectMapper));
        registration.setOrder(FILTER_ORDER);
        registration.addUrlPatterns("/*");
        registration.setName("rateLimitFilter");
        return registration;
    }
}
