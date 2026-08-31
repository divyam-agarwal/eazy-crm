package com.easycrm.sales;

import com.easycrm.platform.visibility.SubjectType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AND-composes whichever filters are non-null. Tenant scoping comes from RLS and
 * visibility from VisibilityPolicy — neither is expressed here.
 *
 * <p>Uses string-keyed {@code root.get(...)} like the four specification classes that
 * preceded it, so a field rename fails at runtime rather than at compile time. That is a
 * known limitation (deferred-backlog item 9), and the item's own instruction is to fix all
 * of them together or none — adding a fifth consistent class is the correct move here.
 */
public final class FollowUpSpecifications {

    private FollowUpSpecifications() {}

    public static Specification<FollowUp> filter(FollowUpScope scope, FollowUpStatus status,
                                                 UUID assignedTo, SubjectType subjectType,
                                                 UUID subjectId, Instant now,
                                                 Instant endOfToday) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (status != null)       ps.add(cb.equal(root.get("status"), status));
            if (assignedTo != null)   ps.add(cb.equal(root.get("assignedTo"), assignedTo));
            if (subjectType != null)  ps.add(cb.equal(root.get("subjectType"), subjectType));
            if (subjectId != null)    ps.add(cb.equal(root.get("subjectId"), subjectId));

            // The due scopes are inherently about PENDING work, so each one implies
            // status = PENDING. ALL adds no due_at predicate and no implied status,
            // leaving the explicit status filter above as the only one.
            if (scope != null && scope != FollowUpScope.ALL) {
                ps.add(cb.equal(root.get("status"), FollowUpStatus.PENDING));
                switch (scope) {
                    case OVERDUE -> ps.add(cb.lessThan(root.get("dueAt"), now));
                    case DUE_TODAY -> {
                        ps.add(cb.greaterThanOrEqualTo(root.get("dueAt"), now));
                        ps.add(cb.lessThan(root.get("dueAt"), endOfToday));
                    }
                    case UPCOMING -> ps.add(cb.greaterThanOrEqualTo(root.get("dueAt"), endOfToday));
                    case ALL -> { /* unreachable — guarded above */ }
                }
            }
            return cb.and(ps.toArray(new Predicate[0])); // empty -> always-true conjunction
        };
    }
}
