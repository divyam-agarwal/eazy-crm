package com.easycrm.sales;

import com.easycrm.iam.AuditService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class OrderStatusChangedAuditListener {

    private final AuditService audit;

    public OrderStatusChangedAuditListener(AuditService audit) {
        this.audit = audit;
    }

    // Synchronous, runs in the publisher's transaction (Spring default) — the audit row
    // commits or rolls back together with the status change (challenge #3 atomicity).
    // Sibling of OrderAcceptedAuditListener; both move to after-commit + outbox when the
    // first external-I/O slice lands (challenge #22).
    @EventListener
    public void on(OrderStatusChangedEvent e) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("orderId", e.orderId().toString());
        detail.put("orderNo", e.orderNo());
        detail.put("from", e.from().name());
        if (e.cancelReason() != null) {
            detail.put("cancelReason", e.cancelReason());
        }
        audit.record("ORDER_" + e.to().name(), e.actorUserId(), detail);
    }
}
