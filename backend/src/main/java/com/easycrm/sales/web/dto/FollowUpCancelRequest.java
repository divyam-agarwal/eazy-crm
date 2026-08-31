package com.easycrm.sales.web.dto;

import jakarta.validation.constraints.Size;

/**
 * The reason is NOT annotated @NotBlank here: the aggregate rejects a blank one, and
 * letting it do so keeps the invariant in one place and yields the same 422 with the same
 * field key. Mirrors how Order.cancel handles its own reason.
 */
public record FollowUpCancelRequest(@Size(max = 500) String reason) {}
