package com.easycrm.sales.web.dto;

import com.easycrm.sales.EnquirySource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record EnquiryCreateRequest(
        UUID customerId,
        @NotBlank String contactName,
        @NotBlank String contactPhone,
        String contactEmail,
        @NotNull EnquirySource source,
        String requirementText,
        UUID assignedTo,
        BigDecimal expectedValue) {}
