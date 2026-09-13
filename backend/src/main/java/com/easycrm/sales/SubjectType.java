package com.easycrm.sales;

/**
 * The aggregates an activity or follow-up may hang off. Lived in the platform module's visibility
 * package until Wave 1.6, on the argument that VisibleFinder owned the gate that switches over it;
 * that class is gone and every user of this enum is in {@code sales}, so it lives here now. See
 * spec 2026-08-30-activity-follow-up-design.md §5.
 *
 * <p>Adding a value here is a visibility decision: {@code SalesVisibility.requireVisibleSubject}
 * must gain a matching branch, or the new subject type resolves to nothing and every activity
 * against it 404s.
 */
public enum SubjectType {
    CUSTOMER,
    ENQUIRY,
    QUOTATION,
    ORDER
}
