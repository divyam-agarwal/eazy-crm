package com.easycrm.sales.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Full-header-replace, per the house convention shared with EnquiryController.patch and
 * QuotationController.patch: an omitted nullable field is CLEARED, not preserved. The
 * subject is not editable — moving a follow-up to a different enquiry is a new follow-up.
 */
public record FollowUpUpdateRequest(
    @NotNull Instant dueAt,
    @NotNull UUID assignedTo,
    @Size(max = 500) String note) {}
