package com.easycrm.sales;

import com.easycrm.iam.AuditService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Sits beside OrderAcceptedAuditListener and is wired the same way -- synchronous, in the
 * publisher's transaction (Spring default) -- so the audit row commits or rolls back with
 * the expiry itself (challenge #3 atomicity).
 *
 * <p>The actor is null: no human did this. AuditLog.actorUserId is nullable precisely so a
 * system-initiated change can say so rather than borrow someone's id.
 */
@Component
public class QuotationExpiredAuditListener {

    private final AuditService audit;

    public QuotationExpiredAuditListener(AuditService audit) {
        this.audit = audit;
    }

    @EventListener
    public void on(QuotationExpiredEvent e) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("quotationId", e.quotationId().toString());
        detail.put("quoteNo", e.quoteNo());
        detail.put("quotationVersionId", e.quotationVersionId().toString());
        detail.put("validUntil", e.validUntil() == null ? null : e.validUntil().toString());
        audit.record("QUOTATION_EXPIRED", null, detail);
    }
}
