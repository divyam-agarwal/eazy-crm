package com.easycrm.sales;

/**
 * Note what is ABSENT: there is no OVERDUE. Overdue is a read-time predicate
 * (status = PENDING AND due_at &lt; now), not stored state — so there is no job that can
 * fall behind and leave a row lying about itself. See spec
 * 2026-08-30-activity-follow-up-design.md §3.
 */
public enum FollowUpStatus {
    PENDING, DONE, CANCELLED;

    public boolean isTerminal() { return this != PENDING; }
}
