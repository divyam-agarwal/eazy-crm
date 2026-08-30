package com.easycrm.sales.web.dto;

import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.sales.FollowUp;
import com.easycrm.sales.FollowUpStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code overdue} is DERIVED at render time, not stored — see spec §3. That is the whole
 * reason this slice ships no scheduler: there is no column to fall out of date.
 */
public record FollowUpResponse(
    UUID id, SubjectType subjectType, UUID subjectId, Instant dueAt, UUID assignedTo,
    FollowUpStatus status, String note, boolean overdue, Instant completedAt,
    String completionNote, UUID createdBy, Instant createdAt) {

    public static FollowUpResponse of(FollowUp f, Instant now) {
        boolean overdue = f.getStatus() == FollowUpStatus.PENDING && f.getDueAt().isBefore(now);
        return new FollowUpResponse(f.getId(), f.getSubjectType(), f.getSubjectId(),
            f.getDueAt(), f.getAssignedTo(), f.getStatus(), f.getNote(), overdue,
            f.getCompletedAt(), f.getCompletionNote(), f.getCreatedBy(), f.getCreatedAt());
    }
}
