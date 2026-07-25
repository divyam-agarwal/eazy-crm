package com.easycrm.crm.web.dto;

import com.easycrm.crm.CustomerSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CustomerRequest(
    @NotBlank String businessName,
    String gstin,
    String stateCode,
    String billingAddress,
    String shippingAddress,
    Integer creditDays,
    UUID assignedTo,
    UUID priceListId,
    @NotNull CustomerSource source) {}
