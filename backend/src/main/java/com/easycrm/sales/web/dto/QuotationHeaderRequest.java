package com.easycrm.sales.web.dto;

import java.time.LocalDate;

public record QuotationHeaderRequest(LocalDate validUntil, String paymentTerms, String deliveryTerms, String notes) {}
