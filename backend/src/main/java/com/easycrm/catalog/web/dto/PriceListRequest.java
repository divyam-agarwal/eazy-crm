package com.easycrm.catalog.web.dto;

import jakarta.validation.constraints.NotBlank;

public record PriceListRequest(@NotBlank String name) {}
