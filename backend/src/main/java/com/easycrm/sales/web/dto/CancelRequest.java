package com.easycrm.sales.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The @NotBlank here and Order.cancel's own blank check are both intended: bean
 * validation rejects a blank reason at the HTTP edge with 400, while the entity guard
 * still protects non-HTTP callers with 422. Mirrors LoseRequest / Enquiry.lose.
 */
public record CancelRequest(@NotBlank String cancelReason) {}
