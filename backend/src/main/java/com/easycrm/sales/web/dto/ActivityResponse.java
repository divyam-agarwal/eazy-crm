package com.easycrm.sales.web.dto;

import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.sales.Activity;
import com.easycrm.sales.ActivitySource;
import com.easycrm.sales.ActivityType;

import java.time.Instant;
import java.util.UUID;

public record ActivityResponse(
    UUID id, SubjectType subjectType, UUID subjectId, ActivityType type,
    String body, String outcome, Instant occurredAt, UUID loggedBy,
    ActivitySource source, Instant createdAt) {

    public static ActivityResponse of(Activity a) {
        return new ActivityResponse(a.getId(), a.getSubjectType(), a.getSubjectId(),
            a.getType(), a.getBody(), a.getOutcome(), a.getOccurredAt(),
            a.getLoggedBy(), a.getSource(), a.getCreatedAt());
    }
}
