package com.easycrm.sales.web.dto;

import jakarta.validation.constraints.NotBlank;

public record LoseRequest(@NotBlank String lostReason) {}
