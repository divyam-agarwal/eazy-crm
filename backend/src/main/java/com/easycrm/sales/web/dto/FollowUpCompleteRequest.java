package com.easycrm.sales.web.dto;

import com.easycrm.sales.ActivityType;
import jakarta.validation.constraints.Size;

/**
 * The optional activity is the mirror of the log-and-schedule flow: closing a task and
 * recording what happened are one user intention (spec §6.2). An activity is written only
 * when {@code type} is present.
 */
public record FollowUpCompleteRequest(
        @Size(max = 500) String note,
        ActivityType type,
        @Size(max = 2000) String body,
        @Size(max = 200) String outcome) {}
