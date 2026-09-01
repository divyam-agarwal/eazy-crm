package com.easycrm.sales;

import com.easycrm.platform.job.TenantJobRunner;
import com.easycrm.platform.time.DueWindow;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly quotation auto-expiry. Deliberately thin: it resolves today's IST date and hands
 * off. Every test drives QuotationExpirySweep or TenantJobRunner directly, so no test ever
 * waits on a cron.
 *
 * <p>The zone is pinned to Asia/Kolkata rather than inherited from the server, so the job
 * fires at 00:30 IST wherever it is deployed -- just after the IST midnight at which a
 * lapsed quotation becomes expirable, so the flip is prompt rather than half a day late.
 */
@Component
public class QuotationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(QuotationExpiryJob.class);
    private static final String JOB_NAME = "quotation-expiry";

    private final TenantJobRunner runner;
    private final QuotationExpirySweep sweep;
    private final Clock clock;

    public QuotationExpiryJob(TenantJobRunner runner, QuotationExpirySweep sweep, Clock clock) {
        this.runner = runner;
        this.sweep = sweep;
        this.clock = clock;
    }

    /**
     * Catches everything on purpose. TenantJobRunner already isolates per-tenant failures,
     * but the tenant-list read itself sits outside that loop, and an exception escaping a
     * @Scheduled method is logged by Spring and then forgotten -- with no summary line, so
     * the run looks like it simply found nothing to do. Logging it here keeps a failed run
     * distinguishable from an empty one.
     */
    @Scheduled(cron = "${easycrm.jobs.quotation-expiry.cron}", zone = "Asia/Kolkata")
    public void run() {
        try {
            LocalDate asOf = DueWindow.todayDate(clock.instant());
            TenantJobRunner.JobSummary summary = runner.forEachTenant(JOB_NAME, tenantId -> sweep.run(asOf));
            log.info(
                    "quotation-expiry as of {}: {} expired across {} tenants ({} failed)",
                    asOf,
                    summary.itemsProcessed(),
                    summary.tenantsSwept(),
                    summary.tenantsFailed());
        } catch (RuntimeException e) {
            log.error("quotation-expiry run failed before it could sweep any tenant", e);
        }
    }
}
