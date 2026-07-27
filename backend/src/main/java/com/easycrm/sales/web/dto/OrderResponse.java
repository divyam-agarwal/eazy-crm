package com.easycrm.sales.web.dto;

import com.easycrm.sales.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record OrderResponse(UUID id, String orderNo, UUID quotationId, UUID quotationVersionId,
                            UUID customerId, String status, String poReference, LocalDate poDate,
                            BigDecimal subTotal, BigDecimal totalTax, BigDecimal grandTotal,
                            Instant createdAt) {

    public static OrderResponse of(Order o) {
        return new OrderResponse(o.getId(), o.getOrderNo(), o.getQuotationId(),
            o.getQuotationVersionId(), o.getCustomerId(), o.getStatus().name(),
            o.getPoReference(), o.getPoDate(), o.getSubTotal(), o.getTotalTax(),
            o.getGrandTotal(), o.getCreatedAt());
    }
}
