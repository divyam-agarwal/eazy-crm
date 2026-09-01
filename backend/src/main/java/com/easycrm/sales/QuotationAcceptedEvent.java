package com.easycrm.sales;

import java.math.BigDecimal;
import java.util.UUID;

public record QuotationAcceptedEvent(
        UUID quotationId,
        UUID orderId,
        UUID quotationVersionId,
        BigDecimal grandTotal,
        String orderNo,
        UUID actorUserId) {}
