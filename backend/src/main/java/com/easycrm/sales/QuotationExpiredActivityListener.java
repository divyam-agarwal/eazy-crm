package com.easycrm.sales;

import com.easycrm.platform.format.IndianFormats;
import com.easycrm.platform.visibility.SubjectType;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Puts the auto-expiry on the quotation's own timeline, so a salesperson sees why a quote
 * stopped being live instead of finding a status that changed overnight with no explanation.
 * Mirrors QuotationAcceptedActivityListener exactly, including being synchronous and in the
 * publisher's transaction.
 *
 * <p>The actor is null -- logSystem's actorUserId is nullable for exactly this case.
 */
@Component
public class QuotationExpiredActivityListener {

    private final ActivityService activities;

    public QuotationExpiredActivityListener(ActivityService activities) {
        this.activities = activities;
    }

    @EventListener
    public void on(QuotationExpiredEvent e) {
        activities.logSystem(
                SubjectType.QUOTATION,
                e.quotationId(),
                ActivityType.NOTE,
                "Quotation " + e.quoteNo() + " expired — it was valid until " + IndianFormats.date(e.validUntil()),
                null);
    }
}
