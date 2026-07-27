package com.easycrm.sales.web.dto;

import com.easycrm.sales.QuotationItem;
import com.easycrm.sales.QuotationVersion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record QuotationVersionResponse(UUID id, int versionNo, String status, LocalDate validUntil,
                                       String paymentTerms, String deliveryTerms, String notes,
                                       String placeOfSupply, BigDecimal subTotal, BigDecimal totalTax,
                                       BigDecimal grandTotal, Instant sentAt, List<ItemResponse> items) {

    public static QuotationVersionResponse of(QuotationVersion v, List<QuotationItem> items) {
        return new QuotationVersionResponse(v.getId(), v.getVersionNo(), v.getStatus().name(),
            v.getValidUntil(), v.getPaymentTerms(), v.getDeliveryTerms(), v.getNotes(),
            v.getPlaceOfSupply(), v.getSubTotal(), v.getTotalTax(), v.getGrandTotal(), v.getSentAt(),
            items.stream().map(ItemResponse::of).toList());
    }
}
