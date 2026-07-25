package com.easycrm.catalog.web.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record PriceListItemRequest(
    @NotNull UUID productId,
    BigDecimal overrideRate,
    BigDecimal discountPct) {}
