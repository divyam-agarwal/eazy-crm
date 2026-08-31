package com.easycrm.sales.web.dto;

/**
 * The dashboard tile. These three ALWAYS sum to the caller's total pending follow-ups,
 * because FollowUpScope's three due scopes partition PENDING exactly (spec §9).
 */
public record FollowUpSummaryResponse(long overdue, long dueToday, long upcoming) {}
