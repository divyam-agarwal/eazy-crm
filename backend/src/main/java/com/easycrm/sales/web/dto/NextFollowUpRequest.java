package com.easycrm.sales.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * The nested half of the log-and-schedule flow (spec §6.1). No subject: it is always the
 * enclosing activity's subject, which has already been resolved once — one gate, one 404
 * decision, and no window in which the two rows could disagree about what they hang off.
 */
public record NextFollowUpRequest(
        @NotNull Instant dueAt,
        @NotNull UUID assignedTo,
        @Size(max = 500) String note) {}
