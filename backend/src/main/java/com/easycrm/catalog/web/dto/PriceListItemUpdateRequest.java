package com.easycrm.catalog.web.dto;

import java.math.BigDecimal;

/**
 * Rate-only update. No {@code productId}: changing which product a line refers to is a delete plus a
 * create, not an edit — the same reasoning that keeps {@code sku} out of ProductUpdateRequest.
 *
 * <p>Exactly one of the two must be set; enforced in PriceListItemService, not by annotations,
 * because the rule is a relationship between fields rather than a constraint on either one.
 */
public record PriceListItemUpdateRequest(BigDecimal overrideRate, BigDecimal discountPct) {}
