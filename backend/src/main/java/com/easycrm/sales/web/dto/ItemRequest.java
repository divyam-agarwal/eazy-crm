package com.easycrm.sales.web.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

// rate is optional: null → resolved from the customer's price list. discountPct optional (0 if null).
public record ItemRequest(@NotNull UUID productId, @NotNull BigDecimal qty, BigDecimal rate, BigDecimal discountPct) {}
