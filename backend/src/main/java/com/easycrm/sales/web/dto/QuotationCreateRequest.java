package com.easycrm.sales.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record QuotationCreateRequest(
        @NotNull UUID customerId,
        UUID enquiryId,
        LocalDate validUntil,
        String paymentTerms,
        String deliveryTerms,
        String notes,
        @NotEmpty @Valid List<ItemRequest> items) {}
