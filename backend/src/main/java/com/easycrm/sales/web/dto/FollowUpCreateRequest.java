package com.easycrm.sales.web.dto;

import com.easycrm.platform.visibility.SubjectType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * dueAt carries no @Future constraint on purpose: "I should have called them yesterday"
 * is real and useful to record, and it lands in scope=OVERDUE where it belongs. Rejecting
 * it would only push users into entering a fake date, which is worse data (spec §7.2).
 */
public record FollowUpCreateRequest(
        @NotNull SubjectType subjectType,
        @NotNull UUID subjectId,
        @NotNull Instant dueAt,
        @NotNull UUID assignedTo,
        @Size(max = 500) String note) {}
