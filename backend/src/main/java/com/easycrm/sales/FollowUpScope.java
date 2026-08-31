package com.easycrm.sales;

/**
 * The three scopes are DISJOINT and exhaustive over PENDING, which is a decision rather
 * than an accident. The naive reading — OVERDUE is due_at &lt; now, DUE_TODAY is anything
 * falling inside today — puts a 9am follow-up read at 3pm in BOTH, so the dashboard's
 * three counts would double-count and would not sum to the pending total. A tile whose
 * parts do not sum to its whole is a bug report waiting to happen.
 *
 * <p>See spec 2026-08-30-activity-follow-up-design.md §9.
 */
public enum FollowUpScope {
    /** PENDING and already past due: {@code due_at < now}. */
    OVERDUE,
    /** PENDING and still to do today: {@code now <= due_at < endOfTodayIST}. */
    DUE_TODAY,
    /** PENDING and later: {@code due_at >= endOfTodayIST}. */
    UPCOMING,
    /** No due_at predicate at all; an explicit status filter still applies. */
    ALL
}
