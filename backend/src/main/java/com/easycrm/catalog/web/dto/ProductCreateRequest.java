package com.easycrm.catalog.web.dto;

import com.easycrm.catalog.Uom;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ProductCreateRequest(
    @NotBlank String sku,
    @NotBlank String name,
    String hsnCode,
    @NotNull Uom uom,
    @NotNull BigDecimal gstRate,
    @NotNull BigDecimal baseRate) {}
