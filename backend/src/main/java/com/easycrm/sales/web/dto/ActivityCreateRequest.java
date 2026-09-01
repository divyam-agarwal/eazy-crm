package com.easycrm.sales.web.dto;

import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.sales.ActivityType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * subjectType and subjectId are required on every activity route, read and write — see
 * spec 2026-08-30-activity-follow-up-design.md §9. occurredAt is optional and defaults to
 * now; a future value is rejected by the aggregate, not here, because the comparison needs
 * the service's Clock.
 *
 * <p>nextFollowUp is added in Task 12 (the log-and-schedule flow).
 */
public record ActivityCreateRequest(
        @NotNull SubjectType subjectType,
        @NotNull UUID subjectId,
        @NotNull ActivityType type,
        @Size(max = 2000) String body,
        @Size(max = 200) String outcome,
        Instant occurredAt,
        @Valid NextFollowUpRequest nextFollowUp) {}
