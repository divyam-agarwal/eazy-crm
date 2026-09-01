package com.easycrm.sales;

import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.visibility.VisibleFinder;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * One tenant's worth of quotation auto-expiry. Takes {@code asOf} as a parameter and never
 * reads a clock: determinism in tests comes from passing the date in, because no test may
 * override the Clock bean without forking the Spring context every IntegrationTest shares
 * (see ClockConfig).
 *
 * <p>Assumes it is already running inside TenantJobRunner's per-tenant transaction with the
 * tenant context bound -- so the quotations it loads are MANAGED and the status change is
 * flushed by dirty checking. It deliberately does not inject QuotationRepository: that
 * repository is guarded, and every read goes through VisibleFinder.
 */
@Component
public class QuotationExpirySweep {

    private final VisibleFinder finder;
    private final QuotationVersionRepository versions;
    private final ApplicationEventPublisher events;

    public QuotationExpirySweep(
            VisibleFinder finder, QuotationVersionRepository versions, ApplicationEventPublisher events) {
        this.finder = finder;
        this.versions = versions;
        this.events = events;
    }

    /** Expires every lapsed SENT quotation in the current tenant. Returns how many. */
    public int run(LocalDate asOf) {
        List<Quotation> due = finder.listQuotations(QuotationSpecifications.expirableAsOf(asOf));
        for (Quotation q : due) {
            QuotationVersion version = versions.findById(q.getCurrentVersionId())
                    .orElseThrow(() -> new NotFoundException("quotation version not found"));
            q.expire();
            events.publishEvent(
                    new QuotationExpiredEvent(q.getId(), q.getQuoteNo(), version.getId(), version.getValidUntil()));
        }
        return due.size();
    }
}
