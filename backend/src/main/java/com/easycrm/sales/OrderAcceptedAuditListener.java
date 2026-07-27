package com.easycrm.sales;

import com.easycrm.iam.AuditService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OrderAcceptedAuditListener {

    private final AuditService audit;

    public OrderAcceptedAuditListener(AuditService audit) { this.audit = audit; }

    // Synchronous, runs in the publisher's transaction (Spring default) — the audit row
    // commits or rolls back together with the order (challenge #3 atomicity).
    @EventListener
    public void on(QuotationAcceptedEvent e) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("quotationId", e.quotationId().toString());
        detail.put("orderId", e.orderId().toString());
        detail.put("orderNo", e.orderNo());
        detail.put("grandTotal", e.grandTotal().toPlainString());
        audit.record("QUOTATION_ACCEPTED", e.actorUserId(), detail);
    }
}
