package com.easycrm.platform.visibility;

/**
 * The aggregates an activity or follow-up may hang off. Lives in this package rather than
 * in sales because VisibleFinder owns the resolve gate that switches over it — see spec
 * 2026-08-30-activity-follow-up-design.md §5.
 *
 * <p>Adding a value here is a visibility decision: VisibleFinder.requireVisibleSubject must
 * gain a matching branch, or the new subject type resolves to nothing and every activity
 * against it 404s.
 */
public enum SubjectType {
    CUSTOMER,
    ENQUIRY,
    QUOTATION,
    ORDER
}
