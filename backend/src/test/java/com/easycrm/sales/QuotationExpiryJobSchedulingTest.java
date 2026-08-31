package com.easycrm.sales;

import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the auto-expiry cron really is disabled for the test suite. Without this, every
 * integration test would race a live nightly job against its own fixtures -- and the
 * failure would be intermittent and blamed on something else.
 *
 * <p>Asserting the registered task list, not just the property value: the property is the
 * mechanism, an unregistered task is the outcome. See spec 2026-08-31 §7 and challenge #42
 * on this project's test-property precedence.
 */
@SpringBootTest
class QuotationExpiryJobSchedulingTest extends IntegrationTest {

    @Autowired Environment environment;
    @Autowired ScheduledAnnotationBeanPostProcessor scheduledPostProcessor;
    @Autowired QuotationExpiryJob job;

    @Test
    void theCronIsDisabledForTheTestSuite() {
        assertThat(environment.getProperty("easycrm.jobs.quotation-expiry.cron")).isEqualTo("-");
    }

    @Test
    void noScheduledTaskIsRegisteredForTheJob() {
        assertThat(scheduledPostProcessor.getScheduledTasks()).isEmpty();
    }

    @Test
    void theJobBeanStillExistsSoTheWiringIsReal() {
        // A disabled cron must not mean a missing bean -- otherwise this test class would
        // pass for the wrong reason and production would have no job at all.
        assertThat(job).isNotNull();
    }
}
