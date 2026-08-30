package com.easycrm.platform.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The codebase's first Clock bean. Services take it rather than calling Instant.now() so
 * that time-dependent logic is expressed as a value they are handed.
 *
 * <p>Note that no test overrides this bean: doing so would fork the Spring context shared
 * by every IntegrationTest subclass. Determinism comes instead from passing an explicit
 * {@code now} into the aggregates and into DueWindow, both of which are pure. See spec
 * 2026-08-30-activity-follow-up-design.md §7.3.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() { return Clock.systemUTC(); }
}
