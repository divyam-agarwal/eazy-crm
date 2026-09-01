package com.easycrm.crm;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class CustomerSpecifications {

    private CustomerSpecifications() {}

    /** AND-composes whichever filters are non-null. Tenant scoping comes from RLS, not here. */
    public static Specification<Customer> filter(Boolean active) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (active != null) ps.add(cb.equal(root.get("active"), active));
            return cb.and(ps.toArray(new Predicate[0])); // empty -> always-true conjunction
        };
    }
}
