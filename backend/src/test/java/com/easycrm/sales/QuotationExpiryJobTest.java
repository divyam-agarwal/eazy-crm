package com.easycrm.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.easycrm.platform.job.TenantJobRunner;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.UUID;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.support.CronExpression;

/**
 * Plain JUnit, no Spring context, no database. Every other test in the suite drives
 * QuotationExpirySweep or TenantJobRunner directly -- this is the one link in the chain
 * that composes them, and it is the one place a UTC-vs-IST date regression would hide: if
 * {@code DueWindow.todayDate(clock.instant())} were ever swapped for {@code
 * LocalDate.now()}, all other tests would stay green while the job read yesterday's date
 * at its real 00:30 IST fire time.
 */
class QuotationExpiryJobTest {

    /** 00:30 IST on 1 Sep 2026 -- the job's real fire time, expressed as the UTC instant. */
    private static final Instant FIRE_TIME = Instant.parse("2026-08-31T19:00:00Z");

    @Test
    void derivesTheIstDateAndHandsItToTheSweep() {
        TenantJobRunner runner = mock(TenantJobRunner.class);
        QuotationExpirySweep sweep = mock(QuotationExpirySweep.class);
        Clock clock = Clock.fixed(FIRE_TIME, ZoneOffset.UTC);

        when(runner.forEachTenant(anyString(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ToIntFunction<UUID> body = invocation.getArgument(1);
            body.applyAsInt(UUID.randomUUID());
            return new TenantJobRunner.JobSummary(1, 0, 1);
        });

        QuotationExpiryJob job = new QuotationExpiryJob(runner, sweep, clock);
        job.run();

        // The UTC instant is 19:00 on 31 Aug -- the IST date is already 1 Sep. Asserting
        // 2026-09-01 here, not 2026-08-31, is what pins the IST derivation rather than a
        // naive LocalDate.now() that would read the wrong day at this exact fire time.
        verify(sweep).run(LocalDate.of(2026, 9, 1));
    }

    @Test
    void aFailureReadingTheTenantListDoesNotEscapeRun() {
        TenantJobRunner runner = mock(TenantJobRunner.class);
        QuotationExpirySweep sweep = mock(QuotationExpirySweep.class);
        Clock clock = Clock.fixed(FIRE_TIME, ZoneOffset.UTC);
        when(runner.forEachTenant(anyString(), any())).thenThrow(new RuntimeException("boom"));

        QuotationExpiryJob job = new QuotationExpiryJob(runner, sweep, clock);

        // An exception escaping a @Scheduled method is logged by Spring and then the task
        // is simply never re-triggered until the next cron fire -- run() must swallow this
        // itself so a bad tenant-list read on one night does not look like a crash loop.
        assertThatCode(job::run).doesNotThrowAnyException();
    }

    /**
     * Nothing else in the suite loads the real production cron: every integration test
     * overrides it with "-" (see QuotationExpiryJobSchedulingTest), so a malformed
     * expression in application.yml would only ever surface as a production startup
     * failure. This reads the actual file rather than asserting a literal typed into the
     * test, so it would catch a typo in application.yml, not just in this test.
     */
    @Test
    void theProductionCronIsValidAndFiresAt0030Daily() throws Exception {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        factory.afterPropertiesSet();
        Properties props = factory.getObject();

        String cron = props.getProperty("easycrm.jobs.quotation-expiry.cron");

        assertThat(cron).isNotNull();
        CronExpression expr = CronExpression.parse(cron);
        assertThat(expr.next(LocalDateTime.of(2026, 8, 31, 0, 0))).isEqualTo(LocalDateTime.of(2026, 8, 31, 0, 30));
    }
}
