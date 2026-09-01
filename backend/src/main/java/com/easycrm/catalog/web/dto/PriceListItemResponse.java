package com.easycrm.catalog.web.dto;

import com.easycrm.catalog.PriceListItem;
import java.math.BigDecimal;
import java.util.UUID;

public record PriceListItemResponse(
        UUID id, UUID priceListId, UUID productId, BigDecimal overrideRate, BigDecimal discountPct) {

    public static PriceListItemResponse of(PriceListItem i) {
        return new PriceListItemResponse(
                i.getId(), i.getPriceListId(), i.getProductId(), i.getOverrideRate(), i.getDiscountPct());
    }
}
