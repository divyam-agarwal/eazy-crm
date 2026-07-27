package com.easycrm.sales.web.dto;

import com.easycrm.sales.QuotationItem;

import java.math.BigDecimal;
import java.util.UUID;

public record ItemResponse(UUID id, UUID productId, String name, String hsn, String uom,
                           BigDecimal qty, BigDecimal rate, BigDecimal discountPct, BigDecimal gstRate,
                           BigDecimal taxableValue, BigDecimal cgst, BigDecimal sgst, BigDecimal igst,
                           BigDecimal lineTotal) {

    public static ItemResponse of(QuotationItem i) {
        return new ItemResponse(i.getId(), i.getProductId(), i.getNameSnapshot(), i.getHsnSnapshot(),
            i.getUomSnapshot(), i.getQty(), i.getRate(), i.getDiscountPct(), i.getGstRate(),
            i.getTaxableValue(), i.getCgst(), i.getSgst(), i.getIgst(), i.getLineTotal());
    }
}
