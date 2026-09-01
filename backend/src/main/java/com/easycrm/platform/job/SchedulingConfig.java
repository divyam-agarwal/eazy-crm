package com.easycrm.platform.job;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on @Scheduled processing. Separate from any job so that "does this app run
 * scheduled work at all" is one grep, and so a test can reason about the scheduler without
 * pulling in a job's dependencies.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
