package com.easycrm.sales.web.dto;

import com.easycrm.sales.Quotation;

import java.util.UUID;

public record QuotationResponse(UUID id, String quoteNo, UUID customerId, UUID enquiryId,
                                String status, QuotationVersionResponse currentVersion) {

    public static QuotationResponse of(Quotation q, QuotationVersionResponse currentVersion) {
        return new QuotationResponse(q.getId(), q.getQuoteNo(), q.getCustomerId(), q.getEnquiryId(),
            q.getStatus().name(), currentVersion);
    }
}
