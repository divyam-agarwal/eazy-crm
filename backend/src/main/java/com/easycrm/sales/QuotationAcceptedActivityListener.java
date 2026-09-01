package com.easycrm.sales;

import com.easycrm.platform.visibility.SubjectType;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Records quotation acceptance on the quotation's own timeline. Sits beside
 * OrderAcceptedAuditListener and is wired the same way — synchronous, in the publisher's
 * transaction (Spring's default), so the activity commits or rolls back with the order
 * exactly as the audit row does (challenge #3).
 *
 * <p>The parent design spec promised that new behaviour on acceptance arrives as a new
 * subscriber rather than an edit to QuotationService. This class is that promise being
 * collected: QuotationService is not touched. See spec
 * 2026-08-30-activity-follow-up-design.md §6.3.
 */
@Component
public class QuotationAcceptedActivityListener {

    private final ActivityService activities;

    public QuotationAcceptedActivityListener(ActivityService activities) {
        this.activities = activities;
    }

    @EventListener
    public void on(QuotationAcceptedEvent e) {
        activities.logSystem(
                SubjectType.QUOTATION,
                e.quotationId(),
                ActivityType.NOTE,
                "Quotation accepted — order " + e.orderNo() + " created for Rs. "
                        + e.grandTotal().toPlainString(),
                e.actorUserId());
    }
}
