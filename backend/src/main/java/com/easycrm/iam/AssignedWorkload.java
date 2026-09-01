package com.easycrm.iam;

import java.util.UUID;

/**
 * One kind of open work a member can still hold, and therefore one thing that can block
 * disabling them. See spec 2026-09-01-members-management-design.md §4.
 *
 * <p>This port is declared in {@code iam} and implemented in {@code crm} and {@code sales}
 * so that iam never imports either. Those packages already depend on iam (all four of
 * CustomerService, EnquiryService, ActivityService and FollowUpService import
 * AssignableUsers), so implementing an iam interface adds no new edge and the package graph
 * stays acyclic. A MemberService that called those repositories directly would invert the
 * arrow and create this codebase's first iam-to-sales cycle.
 *
 * <p>Implementations MUST NOT apply visibility filtering. This is an invariant check, not a
 * user-facing read: a count that hides rows would let a disable through while work remains
 * assigned to the disabled member. That is why the count methods are on the shared
 * ALLOWED_METHODS list in VisibilityScopingArchTest rather than routed through VisibleFinder.
 *
 * <p>Quotations and orders are deliberately absent: they carry no assigned_to and derive
 * their visibility from their customer, so reassigning the customer carries them.
 */
public interface AssignedWorkload {

    /** Stable, human-meaningful plural used in the 409 message and as its field key. */
    String label();

    /** Open items assigned to this member within the current tenant. Never filtered. */
    long countOpenFor(UUID userId);
}
