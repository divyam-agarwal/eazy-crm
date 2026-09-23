package com.easycrm.crm;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class CustomerSpecifications {

    private CustomerSpecifications() {}

    /** AND-composes whichever filters are non-null. Tenant scoping comes from RLS, not here. */
    public static Specification<Customer> filter(Boolean active, String q) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (active != null) ps.add(cb.equal(root.get("active"), active));
            String needle = q == null ? null : q.trim();
            if (needle != null && !needle.isEmpty()) {
                // lower(col) LIKE lower(%needle%) matches V36's lower(col) gin_trgm_ops indexes.
                // An index on the raw column would not be used by this expression.
                String pattern = "%" + needle.toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("businessName")), pattern),
                        cb.like(cb.lower(root.get("gstin")), pattern)));
            }
            return cb.and(ps.toArray(new Predicate[0])); // empty -> always-true conjunction
        };
    }
}
